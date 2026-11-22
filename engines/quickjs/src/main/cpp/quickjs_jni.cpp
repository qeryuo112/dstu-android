#include <jni.h>

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "quickjs_engine.h"

namespace {

std::string EscapeJson(const std::string& input) {
    std::string output;
    output.reserve(input.size() + 16);
    for (unsigned char ch : input) {
        switch (ch) {
            case '"': output += "\\\""; break;
            case '\\': output += "\\\\"; break;
            case '\b': output += "\\b"; break;
            case '\f': output += "\\f"; break;
            case '\n': output += "\\n"; break;
            case '\r': output += "\\r"; break;
            case '\t': output += "\\t"; break;
            default:
                if (ch < 0x20U) {
                    char escaped[7];
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", ch);
                    output += escaped;
                } else {
                    output += static_cast<char>(ch);
                }
        }
    }
    return output;
}

std::string Quote(const std::string& input) {
    return "\"" + EscapeJson(input) + "\"";
}

std::string JavaToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string output;
    output.reserve(static_cast<std::size_t>(length) * 3);
    for (jsize index = 0; index < length; ++index) {
        std::uint32_t code = chars[index];
        if (code >= 0xd800U && code <= 0xdbffU && index + 1 < length) {
            const std::uint32_t low = chars[index + 1];
            if (low >= 0xdc00U && low <= 0xdfffU) {
                code = 0x10000U + ((code - 0xd800U) << 10U) + (low - 0xdc00U);
                ++index;
            }
        }
        if (code <= 0x7fU) {
            output.push_back(static_cast<char>(code));
        } else if (code <= 0x7ffU) {
            output.push_back(static_cast<char>(0xc0U | (code >> 6U)));
            output.push_back(static_cast<char>(0x80U | (code & 0x3fU)));
        } else if (code <= 0xffffU) {
            output.push_back(static_cast<char>(0xe0U | (code >> 12U)));
            output.push_back(static_cast<char>(0x80U | ((code >> 6U) & 0x3fU)));
            output.push_back(static_cast<char>(0x80U | (code & 0x3fU)));
        } else {
            output.push_back(static_cast<char>(0xf0U | (code >> 18U)));
            output.push_back(static_cast<char>(0x80U | ((code >> 12U) & 0x3fU)));
            output.push_back(static_cast<char>(0x80U | ((code >> 6U) & 0x3fU)));
            output.push_back(static_cast<char>(0x80U | (code & 0x3fU)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return output;
}

jstring Utf8ToJava(JNIEnv* env, const std::string& input) {
    std::vector<jchar> output;
    output.reserve(input.size());
    std::size_t index = 0;
    while (index < input.size()) {
        const unsigned char first = static_cast<unsigned char>(input[index++]);
        std::uint32_t code = 0xfffdU;
        int remaining = 0;
        if (first < 0x80U) {
            code = first;
        } else if ((first & 0xe0U) == 0xc0U) {
            code = first & 0x1fU;
            remaining = 1;
        } else if ((first & 0xf0U) == 0xe0U) {
            code = first & 0x0fU;
            remaining = 2;
        } else if ((first & 0xf8U) == 0xf0U) {
            code = first & 0x07U;
            remaining = 3;
        }
        bool valid = first < 0x80U || remaining > 0;
        const std::uint32_t minimum = remaining == 1 ? 0x80U : remaining == 2 ? 0x800U : 0x10000U;
        for (int i = 0; i < remaining && valid; ++i) {
            if (index >= input.size()) { valid = false; break; }
            const unsigned char next = static_cast<unsigned char>(input[index]);
            if ((next & 0xc0U) != 0x80U) {
                valid = false;
                break;
            }
            ++index;
            code = (code << 6U) | (next & 0x3fU);
        }
        if (remaining > 0 && (code < minimum || code > 0x10ffffU || (code >= 0xd800U && code <= 0xdfffU))) valid = false;
        if (!valid) {
            code = 0xfffdU;
        }
        if (code <= 0xffffU) {
            output.push_back(static_cast<jchar>(code));
        } else {
            const std::uint32_t adjusted = code - 0x10000U;
            output.push_back(static_cast<jchar>(0xd800U + (adjusted >> 10U)));
            output.push_back(static_cast<jchar>(0xdc00U + (adjusted & 0x3ffU)));
        }
    }
    return env->NewString(output.data(), static_cast<jsize>(output.size()));
}

class JniHost final : public dstu::script::Host {
public:
    JniHost(JNIEnv* env, jobject host) {
        if (env->GetJavaVM(&java_vm_) != JNI_OK || host == nullptr) return;
        bridge_ = env->NewGlobalRef(host);
        jclass type = env->GetObjectClass(host);
        method_ = env->GetMethodID(type, "onCall", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        env->DeleteLocalRef(type);
    }

    ~JniHost() override {
        if (bridge_ == nullptr) return;
        JNIEnv* env = Env();
        if (env != nullptr) env->DeleteGlobalRef(bridge_);
    }

    dstu::script::HostResponse Call(
        const std::string& tool,
        const std::string& args_json
    ) override {
        JNIEnv* env = Env();
        if (env == nullptr || bridge_ == nullptr || method_ == nullptr) {
            return {dstu::script::HostStatus::Fatal, {}, "HOST_FAILURE", "host unavailable"};
        }
        jstring java_tool = Utf8ToJava(env, tool);
        jstring java_args = Utf8ToJava(env, args_json);
        jobject raw = env->CallObjectMethod(bridge_, method_, java_tool, java_args);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(java_tool);
            env->DeleteLocalRef(java_args);
            return {dstu::script::HostStatus::Fatal, {}, "HOST_FAILURE", "host call failed"};
        }
        std::string response = raw == nullptr ? "" : JavaToUtf8(env, static_cast<jstring>(raw));
        if (raw != nullptr) env->DeleteLocalRef(raw);
        env->DeleteLocalRef(java_tool);
        env->DeleteLocalRef(java_args);
        return Parse(response);
    }

private:
    JNIEnv* Env() {
        if (java_vm_ == nullptr) return nullptr;
        JNIEnv* env = nullptr;
        if (java_vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            return env;
        }
        if (java_vm_->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
        return nullptr;
    }

    static dstu::script::HostResponse Parse(const std::string& raw) {
        if (raw.size() < 2) {
            return {dstu::script::HostStatus::Fatal, {}, "HOST_FAILURE", "invalid host response"};
        }
        const char tag = raw[0];
        if (tag == 'S') {
            return {dstu::script::HostStatus::Success, raw.substr(1), {}, {}};
        }
        const std::size_t split = raw.find('\n', 1);
        const std::string code = split == std::string::npos ? raw.substr(1) : raw.substr(1, split - 1);
        const std::string message = split == std::string::npos ? "tool failed" : raw.substr(split + 1);
        if (tag == 'F') {
            return {dstu::script::HostStatus::Failure, {}, code, message};
        }
        if (tag == 'X') {
            return {dstu::script::HostStatus::Fatal, {}, code, message};
        }
        return {dstu::script::HostStatus::Fatal, {}, "HOST_FAILURE", "invalid host response"};
    }

    JavaVM* java_vm_ = nullptr;
    jobject bridge_ = nullptr;
    jmethodID method_ = nullptr;
};

struct NativeVm {
    NativeVm(JNIEnv* env, jobject bridge, const dstu::script::Limits& limits)
        : host(env, bridge), engine(host, limits) {}

    JniHost host;
    dstu::script::Engine engine;
};

std::mutex registry_lock;
std::unordered_map<jlong, std::shared_ptr<NativeVm>> registry;
jlong next_handle = 1;

std::shared_ptr<NativeVm> From(jlong handle) {
    const std::lock_guard<std::mutex> guard(registry_lock);
    const auto found = registry.find(handle);
    return found == registry.end() ? nullptr : found->second;
}

std::string Envelope(const dstu::script::Outcome& outcome) {
    std::string json = "{";
    json += "\"status\":" + Quote(outcome.status);
    json += ",\"valueJson\":";
    json += outcome.success ? Quote(outcome.value_json) : "null";
    json += ",\"failureKind\":";
    json += outcome.success ? "null" : Quote(outcome.failure_kind);
    json += ",\"message\":";
    json += outcome.success ? "null" : Quote(outcome.message);
    json += ",\"callsUsed\":" + std::to_string(outcome.calls_used);
    json += ",\"engineTicks\":" + std::to_string(outcome.engine_ticks);
    json += ",\"currentMemoryBytes\":" + std::to_string(outcome.current_memory_bytes);
    json += ",\"durationMillis\":" + std::to_string(outcome.duration_ms);
    json += "}";
    return json;
}

dstu::script::Limits ReadLimits(
    jint max_calls,
    jlong wall_ms,
    jlong memory_bytes,
    jlong stack_bytes,
    jint max_ticks,
    jint result_bytes
) {
    dstu::script::Limits limits;
    limits.max_tool_calls = max_calls;
    limits.wall_time_ms = wall_ms;
    limits.memory_bytes = static_cast<std::size_t>(memory_bytes);
    limits.stack_bytes = static_cast<std::size_t>(stack_bytes);
    limits.max_engine_ticks = max_ticks;
    limits.result_bytes = static_cast<std::size_t>(result_bytes);
    return limits;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_deepstudent_engines_quickjs_QuickJsNative_nativeCreate(
    JNIEnv* env, jclass, jobject bridge,
    jint max_calls, jlong wall_ms, jlong memory_bytes, jlong stack_bytes,
    jint max_ticks, jint result_bytes
) {
    if (bridge == nullptr) return 0;
    try {
        auto vm = std::make_shared<NativeVm>(
            env, bridge,
            ReadLimits(max_calls, wall_ms, memory_bytes, stack_bytes, max_ticks, result_bytes)
        );
        const std::lock_guard<std::mutex> guard(registry_lock);
        const jlong handle = next_handle++;
        registry.emplace(handle, std::move(vm));
        return handle;
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_deepstudent_engines_quickjs_QuickJsNative_nativeExecute(
    JNIEnv* env, jclass, jlong handle, jstring source
) {
    const auto vm = From(handle);
    if (vm == nullptr) {
        return Utf8ToJava(env, R"({"status":"error","valueJson":null,"failureKind":"ENGINE","message":"engine unavailable","callsUsed":0,"engineTicks":0,"currentMemoryBytes":0,"durationMillis":0})");
    }
    const dstu::script::Outcome outcome = vm->engine.Execute(JavaToUtf8(env, source));
    return Utf8ToJava(env, Envelope(outcome));
}

extern "C" JNIEXPORT void JNICALL
Java_com_deepstudent_engines_quickjs_QuickJsNative_nativeInterrupt(
    JNIEnv*, jclass, jlong handle
) {
    const auto vm = From(handle);
    if (vm != nullptr) vm->engine.Interrupt();
}

extern "C" JNIEXPORT void JNICALL
Java_com_deepstudent_engines_quickjs_QuickJsNative_nativeDestroy(
    JNIEnv*, jclass, jlong handle
) {
    std::shared_ptr<NativeVm> retired;
    {
        const std::lock_guard<std::mutex> guard(registry_lock);
        const auto found = registry.find(handle);
        if (found != registry.end()) {
            retired = std::move(found->second);
            registry.erase(found);
        }
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_deepstudent_engines_quickjs_QuickJsNative_nativeVersion(JNIEnv* env, jclass) {
    return Utf8ToJava(env, "2026-06-04");
}
