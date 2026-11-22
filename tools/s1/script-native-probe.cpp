#include "quickjs_engine.h"

#include <chrono>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>

namespace {

using dstu::script::Host;
using dstu::script::HostResponse;
using dstu::script::HostStatus;
using dstu::script::Limits;
using dstu::script::Outcome;

void Require(bool condition, const std::string& label) {
    if (!condition) {
        std::cerr << "FAIL " << label << "\n";
        std::exit(1);
    }
}

std::string ReadFile(const std::string& path) {
    std::ifstream input(path);
    Require(input.good(), "open fixture " + path);
    std::ostringstream output;
    output << input.rdbuf();
    return output.str();
}

class ProbeHost final : public Host {
public:
    HostResponse Call(const std::string& tool, const std::string& args_json) override {
        calls += 1;
        if (tool == "script_echo" || tool == "builtin-script_echo") {
            return {HostStatus::Success, args_json, {}, {}};
        }
        if (tool == "script_add" || tool == "builtin-script_add") {
            return {HostStatus::Success, R"({"sum":42})", {}, {}};
        }
        if (tool == "script_fail") {
            return {HostStatus::Failure, {}, "TOOL_FAILED", "failed"};
        }
        if (tool == "script_deny") {
            return {HostStatus::Fatal, {}, "POLICY_VIOLATION", "denied"};
        }
        return {HostStatus::Failure, {}, "UNKNOWN_TOOL", "unknown tool"};
    }

    int calls = 0;
};

void ExpectFailure(
    const std::string& label,
    const std::string& source,
    const Limits& limits,
    const std::string& kind
) {
    ProbeHost host;
    dstu::script::Engine engine(host, limits);
    const Outcome outcome = engine.Execute(source);
    if (outcome.success || outcome.failure_kind != kind) {
        std::cerr << "FAIL " << label << " expected=" << kind
                  << " actual=" << outcome.failure_kind << " message=" << outcome.message << "\n";
        std::exit(1);
    }
    std::cout << "PROBE_FAILURE " << label << " kind=" << outcome.failure_kind
              << " calls=" << outcome.calls_used << " ticks=" << outcome.engine_ticks
              << " duration_ms=" << outcome.duration_ms << "\n";
}

}  // namespace

int main(int argc, char** argv) {
    Require(argc == 2, "fixture directory argument");
    const std::string fixtures = argv[1];

    {
        ProbeHost host;
        dstu::script::Engine engine(host, Limits{});
        const Outcome outcome = engine.Execute("({answer: 40 + 2})");
        Require(outcome.success, "last expression success");
        Require(outcome.value_json == R"({"answer":42})", "last expression value " + outcome.value_json);
        std::cout << "PROBE_LAST_EXPRESSION value=" << outcome.value_json << "\n";
    }

    {
        ProbeHost host;
        Limits limits;
        limits.wall_time_ms = 40;
        limits.max_engine_ticks = 1000000000;
        dstu::script::Engine engine(host, limits);
        const Outcome outcome = engine.Execute("({toJSON(){ while (true) {} }})");
        Require(!outcome.success && outcome.failure_kind == "WALL_TIME", "toJSON deadline hard fail");
        std::cout << "PROBE_TOJSON_DEADLINE kind=" << outcome.failure_kind << "\n";
    }

    {
        ProbeHost host;
        Limits limits;
        limits.wall_time_ms = 40;
        limits.max_engine_ticks = 1000000000;
        dstu::script::Engine engine(host, limits);
        const Outcome outcome = engine.Execute("JSON.stringify({toJSON(){ while (true) {} }})");
        Require(!outcome.success && outcome.failure_kind == "WALL_TIME", "json stringify deadline hard fail");
        std::cout << "PROBE_STRINGIFY_DEADLINE kind=" << outcome.failure_kind << "\n";
    }

    {
        ProbeHost host;
        dstu::script::Engine engine(host, Limits{});
        const Outcome outcome = engine.Execute(ReadFile(fixtures + "/ptc-two-tools.js"));
        Require(outcome.success, "two tools success " + outcome.message);
        Require(outcome.value_json.find("\"sum\":42") != std::string::npos, "two tools result");
        Require(outcome.calls_used == 2, "two tools call count");
        Require(host.calls == 2, "host executed two tools");
        std::cout << "PROBE_TWO_TOOLS value=" << outcome.value_json
                  << " calls=" << outcome.calls_used << "\n";
    }

    {
        ProbeHost host;
        dstu::script::Engine engine(host, Limits{});
        const Outcome outcome = engine.Execute(ReadFile(fixtures + "/ptc-tool-failure.js"));
        Require(outcome.success, "tool failure remains script-visible");
        Require(outcome.value_json == "\"failed\"", "tool failure result " + outcome.value_json);
        std::cout << "PROBE_TOOL_FAILURE value=" << outcome.value_json << "\n";
    }

    {
        ProbeHost host;
        dstu::script::Engine engine(host, Limits{});
        const Outcome outcome = engine.Execute(ReadFile(fixtures + "/ptc-deny.js"));
        Require(!outcome.success && outcome.failure_kind == "POLICY_VIOLATION", "uncatchable denial");
        Require(host.calls == 1, "denial host call count");
        std::cout << "PROBE_DENY kind=" << outcome.failure_kind << "\n";
    }

    {
        ProbeHost host;
        dstu::script::Engine engine(host, Limits{});
        const Outcome outcome = engine.Execute("call(\"unknown\", {}).error");
        Require(outcome.success && outcome.value_json == "\"unknown tool\"", "unknown tool no budget");
        Require(outcome.calls_used == 0 && host.calls == 1, "unknown tool does not consume budget");
        std::cout << "PROBE_UNKNOWN_TOOL value=" << outcome.value_json
                  << " calls=" << outcome.calls_used << " hostCalls=" << host.calls << "\n";
    }

    {
        ProbeHost host;
        dstu::script::Engine engine(host, Limits{});
        const Outcome outcome = engine.Execute("call(\"script_echo\", JSON.stringify({value: \"汉😀\"})).output");
        Require(outcome.success, "string args success " + outcome.message);
        Require(outcome.value_json.find("汉😀") != std::string::npos, "string args preserved");
        std::cout << "PROBE_STRING_ARGS value=" << outcome.value_json << "\n";
    }

    {
        Limits limits;
        limits.max_tool_calls = 1;
        ExpectFailure(
            "tool-budget",
            ReadFile(fixtures + "/ptc-over-budget.js"),
            limits,
            "TOOL_CALL_LIMIT"
        );
    }

    {
        Limits limits;
        limits.wall_time_ms = 40;
        limits.max_engine_ticks = 1000000000;
        ExpectFailure("wall-time", "while (true) {}", limits, "WALL_TIME");
    }

    {
        Limits limits;
        limits.wall_time_ms = 10000;
        limits.max_engine_ticks = 10;
        ExpectFailure("instruction-limit", "while (true) {}", limits, "INSTRUCTION_LIMIT");
    }

    {
        Limits limits;
        limits.memory_bytes = 8 * 1024 * 1024;
        ExpectFailure(
            "memory-limit",
            "new Uint8Array(16 * 1024 * 1024)",
            limits,
            "MEMORY_LIMIT"
        );
    }

    {
        Limits limits;
        limits.stack_bytes = 64 * 1024;
        ExpectFailure("stack-limit", "function f(){ return f(); } f()", limits, "STACK_LIMIT");
    }

    {
        Limits limits;
        limits.result_bytes = 100;
        ExpectFailure("result-limit", "\"x\".repeat(1000)", limits, "RESULT_LIMIT");
    }

    {
        Limits limits;
        limits.source_bytes = 10;
        ExpectFailure("source-limit", "\"this source is too long\"", limits, "SOURCE_LIMIT");
    }

    {
        ProbeHost host;
        Limits limits;
        limits.wall_time_ms = 10000;
        limits.max_engine_ticks = 1000000000;
        dstu::script::Engine engine(host, limits);
        std::thread interrupter([&engine] {
            std::this_thread::sleep_for(std::chrono::milliseconds(30));
            engine.Interrupt();
        });
        const Outcome outcome = engine.Execute("while (true) {}");
        interrupter.join();
        Require(!outcome.success && outcome.failure_kind == "CANCELLED", "external cancellation");
        std::cout << "PROBE_CANCELLED kind=" << outcome.failure_kind << "\n";
    }

    std::cout << "SCRIPT_NATIVE_PROBE PASS\n";
    return 0;
}
