#include "quickjs_engine.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <mutex>
#include <string>
#include <utility>

#include "quickjs.h"

namespace dstu::script {
namespace {

using Clock = std::chrono::steady_clock;
constexpr std::size_t kAllocationHeader = alignof(std::max_align_t);

struct AllocationHeader {
    std::size_t size;
};

enum class StopReason {
    None,
    Cancelled,
    WallTime,
    InstructionLimit,
    MemoryLimit,
    ToolCallLimit,
    PolicyViolation,
};

std::string Truncate(std::string value, std::size_t maximum) {
    if (value.size() <= maximum) return value;
    value.resize(maximum);
    return value + "...";
}

std::string ValueText(JSContext* context, JSValueConst value) {
    const char* text = JS_ToCString(context, value);
    if (text == nullptr) return {};
    std::string result(text);
    JS_FreeCString(context, text);
    return result;
}

std::string ExceptionMessage(JSContext* context) {
    JSValue exception = JS_GetException(context);
    std::string message;
    if (JS_IsObject(exception)) {
        JSValue property = JS_GetPropertyStr(context, exception, "message");
        if (!JS_IsException(property) && !JS_IsUndefined(property) && !JS_IsNull(property)) {
            message = ValueText(context, property);
        }
        JS_FreeValue(context, property);
    }
    if (message.empty()) message = ValueText(context, exception);
    JS_FreeValue(context, exception);
    return Truncate(message.empty() ? "script failed" : message, 300);
}

std::string KindForMessage(const std::string& message) {
    if (message.find("out of memory") != std::string::npos) return "MEMORY_LIMIT";
    if (message.find("stack overflow") != std::string::npos) return "STACK_LIMIT";
    if (message.find("interrupted") != std::string::npos) return "CANCELLED";
    return "SCRIPT";
}

JSValue NewErrorObject(JSContext* context, const std::string& message) {
    JSValue result = JS_NewObject(context);
    if (JS_IsException(result)) return result;
    JS_SetPropertyStr(context, result, "ok", JS_NewBool(context, false));
    JS_SetPropertyStr(context, result, "error", JS_NewString(context, message.c_str()));
    return result;
}

JSValue NewSuccessObject(JSContext* context, const std::string& value_json) {
    JSValue output = JS_ParseJSON(context, value_json.c_str(), value_json.size(), "<tool-result>");
    if (JS_IsException(output)) return output;
    JSValue result = JS_NewObject(context);
    if (JS_IsException(result)) {
        JS_FreeValue(context, output);
        return result;
    }
    JS_SetPropertyStr(context, result, "ok", JS_NewBool(context, true));
    JS_SetPropertyStr(context, result, "output", output);
    return result;
}

}  // namespace

struct Engine::Impl {
    Impl(Host& host_value, Limits limits_value)
        : host(host_value), limits(std::move(limits_value)) {
        started = Clock::now();
        deadline = started + std::chrono::milliseconds(limits.wall_time_ms);
        runtime = JS_NewRuntime2(&kMallocFunctions, this);
        if (runtime == nullptr) return;
        JS_SetRuntimeOpaque(runtime, this);
        JS_SetMaxStackSize(runtime, limits.stack_bytes);
        JS_UpdateStackTop(runtime);
        JS_SetCanBlock(runtime, 0);
        JS_SetInterruptHandler(runtime, &Impl::InterruptCallback, this);
        context = JS_NewContext(runtime);
        if (context == nullptr) return;
        InstallCall();
    }

    ~Impl() {
        if (context != nullptr) JS_FreeContext(context);
        if (runtime != nullptr) JS_FreeRuntime(runtime);
    }

    Outcome Execute(const std::string& source) {
        if (runtime == nullptr || context == nullptr) return Failed("ENGINE", "engine unavailable");
        if (source.size() > limits.source_bytes) {
            return Failed("SOURCE_LIMIT", "script source exceeds the source budget");
        }
        if (IsInterrupted()) {
            return Failed("CANCELLED", "script cancelled");
        }
        JSValue value = JS_Eval(
            context, source.c_str(), source.size(), "<ptc>",
            JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_BACKTRACE_BARRIER
        );
        if (JS_IsException(value)) {
            std::string message = ExceptionMessage(context);
            const StopReason reason = stop_reason.load();
            std::string kind = reason == StopReason::None ? KindForMessage(message) : StopKind(reason);
            JS_FreeValue(context, value);
            return Failed(kind, message);
        }
        if (IsInterrupted() || stop_reason.load() != StopReason::None) {
            JS_FreeValue(context, value);
            const StopReason reason = stop_reason.load();
            return Failed(reason == StopReason::None ? "CANCELLED" : StopKind(reason), "script interrupted");
        }
        JSValue json = JS_JSONStringify(context, value, JS_UNDEFINED, JS_UNDEFINED);
        JS_FreeValue(context, value);
        if (JS_IsException(json) || JS_IsUndefined(json)) {
            if (!JS_IsException(json)) JS_FreeValue(context, json);
            if (IsInterrupted() || stop_reason.load() != StopReason::None) {
                const StopReason reason = stop_reason.load();
                return Failed(reason == StopReason::None ? "CANCELLED" : StopKind(reason), "script interrupted");
            }
            return Failed("SCRIPT", "script result is not JSON serializable");
        }
        if (IsInterrupted() || stop_reason.load() != StopReason::None) {
            JS_FreeValue(context, json);
            const StopReason reason = stop_reason.load();
            return Failed(reason == StopReason::None ? "CANCELLED" : StopKind(reason), "script interrupted");
        }
        std::string result = ValueText(context, json);
        JS_FreeValue(context, json);
        if (result.size() > limits.result_bytes) {
            return Failed("RESULT_LIMIT", "script result exceeds the result budget");
        }
        Outcome outcome;
        outcome.success = true;
        outcome.status = "ok";
        outcome.value_json = result;
        FillStats(outcome);
        return outcome;
    }

    void Interrupt() {
        stop_reason.store(StopReason::Cancelled);
        interrupted.store(true);
    }

    bool IsInterrupted() {
        if (Clock::now() >= deadline && stop_reason.load() == StopReason::None) {
            stop_reason.store(StopReason::WallTime);
            interrupted.store(true);
        }
        return interrupted.load() || Clock::now() >= deadline;
    }

    Outcome Failed(const std::string& kind, const std::string& message) const {
        Outcome outcome;
        outcome.success = false;
        outcome.status = kind == "CANCELLED" ? "cancelled" : "error";
        outcome.failure_kind = kind;
        outcome.message = Truncate(message, 300);
        FillStats(outcome);
        return outcome;
    }

    void FillStats(Outcome& outcome) const {
        outcome.calls_used = calls.load();
        outcome.engine_ticks = ticks.load();
        if (runtime != nullptr) {
            JSMemoryUsage usage{};
            JS_ComputeMemoryUsage(runtime, &usage);
            outcome.current_memory_bytes = usage.malloc_size;
            UpdatePeak(outcome.current_memory_bytes);
        }
        outcome.duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            Clock::now() - started
        ).count();
    }

    static int InterruptCallback(JSRuntime*, void* opaque) {
        auto* self = static_cast<Impl*>(opaque);
        if (self->interrupted.load()) {
            self->stop_reason.store(StopReason::Cancelled);
            return 1;
        }
        if (Clock::now() >= self->deadline) {
            self->stop_reason.store(StopReason::WallTime);
            self->interrupted.store(true);
            return 1;
        }
        const auto count = self->ticks.fetch_add(1) + 1;
        if (count > self->limits.max_engine_ticks) {
            self->stop_reason.store(StopReason::InstructionLimit);
            self->interrupted.store(true);
            return 1;
        }
        return 0;
    }

    static JSValue CallEntry(
        JSContext* context,
        JSValueConst,
        int argc,
        JSValueConst* argv
    ) {
        auto* self = static_cast<Impl*>(JS_GetRuntimeOpaque(JS_GetRuntime(context)));
        return self == nullptr
            ? JS_ThrowInternalError(context, "script engine unavailable")
            : self->Call(context, argc, argv);
    }

    JSValue Call(JSContext* context, int argc, JSValueConst* argv) {
        if (IsInterrupted()) {
            return Fatal(context, StopKind(stop_reason.load()), "script interrupted");
        }
        if (argc < 1) return NewErrorObject(context, "call requires a tool name");
        std::string tool = ValueText(context, argv[0]);
        if (tool.empty()) return NewErrorObject(context, "call requires a tool name");
        if (calls.load() >= limits.max_tool_calls) {
            stop_reason.store(StopReason::ToolCallLimit);
            interrupted.store(true);
            return Fatal(context, "TOOL_CALL_LIMIT", "tool-call budget exhausted");
        }
        std::string args = "null";
        if (argc >= 2 && !JS_IsUndefined(argv[1])) {
            if (JS_IsString(argv[1])) {
                args = ValueText(context, argv[1]);
            } else {
                JSValue json = JS_JSONStringify(context, argv[1], JS_UNDEFINED, JS_UNDEFINED);
                if (JS_IsException(json) || JS_IsUndefined(json)) {
                    if (!JS_IsException(json)) JS_FreeValue(context, json);
                    return NewErrorObject(context, "tool arguments must be JSON serializable");
                }
                args = ValueText(context, json);
                JS_FreeValue(context, json);
            }
        }
        HostResponse response = host.Call(tool, args);
        if (IsInterrupted() && response.status != HostStatus::Fatal) {
            return Fatal(context, StopKind(stop_reason.load()), "script interrupted");
        }
        if (response.status != HostStatus::Failure || response.code != "UNKNOWN_TOOL") {
            calls.fetch_add(1);
        }
        switch (response.status) {
            case HostStatus::Success:
                return NewSuccessObject(context, response.value_json);
            case HostStatus::Failure:
                return NewErrorObject(context, response.message);
            case HostStatus::Fatal:
                stop_reason.store(response.code == "WALL_TIME"
                    ? StopReason::WallTime
                    : StopReason::PolicyViolation);
                interrupted.store(true);
                return Fatal(context, response.code, response.message);
        }
        return NewErrorObject(context, "invalid host response");
    }

    JSValue Fatal(JSContext* context, const std::string& kind, const std::string& message) {
        if (kind == "WALL_TIME") stop_reason.store(StopReason::WallTime);
        if (kind == "TOOL_CALL_LIMIT") stop_reason.store(StopReason::ToolCallLimit);
        if (kind == "POLICY_VIOLATION") stop_reason.store(StopReason::PolicyViolation);
        interrupted.store(true);
        JSValue thrown = JS_ThrowInternalError(context, "%s", message.c_str());
        JS_SetUncatchableException(context, 1);
        return thrown;
    }

    static std::string StopKind(StopReason reason) {
        switch (reason) {
            case StopReason::Cancelled: return "CANCELLED";
            case StopReason::WallTime: return "WALL_TIME";
            case StopReason::InstructionLimit: return "INSTRUCTION_LIMIT";
            case StopReason::MemoryLimit: return "MEMORY_LIMIT";
            case StopReason::ToolCallLimit: return "TOOL_CALL_LIMIT";
            case StopReason::PolicyViolation: return "POLICY_VIOLATION";
            case StopReason::None: return "SCRIPT";
        }
        return "SCRIPT";
    }

    void InstallCall() {
        JSValue global = JS_GetGlobalObject(context);
        JS_SetPropertyStr(
            context,
            global,
            "call",
            JS_NewCFunction(context, &Impl::CallEntry, "call", 2)
        );
        JS_FreeValue(context, global);
    }

    Host& host;
    Limits limits;
    Clock::time_point started;
    Clock::time_point deadline;
    JSRuntime* runtime = nullptr;
    JSContext* context = nullptr;
    std::atomic<bool> interrupted{false};
    std::atomic<StopReason> stop_reason{StopReason::None};
    std::atomic<int> calls{0};
    std::atomic<std::int64_t> ticks{0};
    mutable std::atomic<std::int64_t> allocated{0};
    mutable std::atomic<std::int64_t> peak_memory_bytes{0};

    static void* Allocate(JSMallocState* state, std::size_t size) {
        auto* self = static_cast<Impl*>(state->opaque);
        if (size > std::numeric_limits<std::size_t>::max() - kAllocationHeader) return nullptr;
        const std::size_t total = std::max(size + kAllocationHeader, sizeof(AllocationHeader));
        if (self != nullptr) {
            const std::int64_t next = self->allocated.fetch_add(static_cast<std::int64_t>(total)) + total;
            if (next > static_cast<std::int64_t>(self->limits.memory_bytes)) {
                self->allocated.fetch_sub(static_cast<std::int64_t>(total));
                self->stop_reason.store(StopReason::MemoryLimit);
                self->interrupted.store(true);
                return nullptr;
            }
            self->UpdatePeak(next);
        }
        void* raw = std::malloc(total);
        if (raw == nullptr) {
            if (self != nullptr) {
                self->allocated.fetch_sub(static_cast<std::int64_t>(total));
                self->stop_reason.store(StopReason::MemoryLimit);
                self->interrupted.store(true);
            }
            return nullptr;
        }
        auto* header = static_cast<AllocationHeader*>(raw);
        header->size = total;
        return static_cast<char*>(raw) + kAllocationHeader;
    }

    static void Release(JSMallocState* state, void* pointer) {
        if (pointer == nullptr) return;
        auto* raw = static_cast<char*>(pointer) - kAllocationHeader;
        auto* header = reinterpret_cast<AllocationHeader*>(raw);
        const std::size_t total = header->size;
        auto* self = static_cast<Impl*>(state->opaque);
        if (self != nullptr) self->allocated.fetch_sub(static_cast<std::int64_t>(total));
        std::free(raw);
    }

    static void* Reallocate(JSMallocState* state, void* pointer, std::size_t size) {
        if (pointer == nullptr) return Allocate(state, size);
        if (size == 0) {
            Release(state, pointer);
            return nullptr;
        }
        auto* raw = static_cast<char*>(pointer) - kAllocationHeader;
        auto* header = reinterpret_cast<AllocationHeader*>(raw);
        const std::size_t old_total = header->size;
        if (size > std::numeric_limits<std::size_t>::max() - kAllocationHeader) return nullptr;
        const std::size_t total = std::max(size + kAllocationHeader, sizeof(AllocationHeader));
        auto* self = static_cast<Impl*>(state->opaque);
        if (self != nullptr) {
            const std::int64_t delta = static_cast<std::int64_t>(total) -
                static_cast<std::int64_t>(old_total);
            const std::int64_t next = self->allocated.fetch_add(delta) + delta;
            if (next > static_cast<std::int64_t>(self->limits.memory_bytes)) {
                self->allocated.fetch_sub(delta);
                self->stop_reason.store(StopReason::MemoryLimit);
                self->interrupted.store(true);
                return nullptr;
            }
            self->UpdatePeak(next);
        }
        void* resized = std::realloc(raw, total);
        if (resized == nullptr) {
            if (self != nullptr) self->allocated.fetch_sub(
                static_cast<std::int64_t>(total) - static_cast<std::int64_t>(old_total)
            );
            return nullptr;
        }
        reinterpret_cast<AllocationHeader*>(resized)->size = total;
        return static_cast<char*>(resized) + kAllocationHeader;
    }

    static std::size_t Usable(const void* pointer) {
        if (pointer == nullptr) return 0;
        const std::size_t total = reinterpret_cast<const AllocationHeader*>(
            static_cast<const char*>(pointer) - kAllocationHeader
        )->size;
        return total > kAllocationHeader ? total - kAllocationHeader : 0;
    }

    void UpdatePeak(std::int64_t value) const {
        std::int64_t observed = peak_memory_bytes.load();
        while (value > observed && !peak_memory_bytes.compare_exchange_weak(observed, value)) {}
    }

    static const JSMallocFunctions kMallocFunctions;
};

const JSMallocFunctions Engine::Impl::kMallocFunctions = {
    &Engine::Impl::Allocate,
    &Engine::Impl::Release,
    &Engine::Impl::Reallocate,
    &Engine::Impl::Usable,
};

Engine::Engine(Host& host, Limits limits) : impl_(std::make_unique<Impl>(host, limits)) {}

Engine::~Engine() = default;

Outcome Engine::Execute(const std::string& source) {
    return impl_->Execute(source);
}

void Engine::Interrupt() {
    impl_->Interrupt();
}

}  // namespace dstu::script
