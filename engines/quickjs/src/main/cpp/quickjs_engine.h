#pragma once

#include <cstdint>
#include <memory>
#include <string>

namespace dstu::script {

struct Limits {
    std::size_t source_bytes = 64 * 1024;
    int max_tool_calls = 50;
    std::int64_t wall_time_ms = 120000;
    std::size_t memory_bytes = 32 * 1024 * 1024;
    std::size_t stack_bytes = 512 * 1024;
    int max_engine_ticks = 1000;
    std::size_t result_bytes = 256 * 1024;
};

enum class HostStatus { Success, Failure, Fatal };

struct HostResponse {
    HostStatus status = HostStatus::Failure;
    std::string value_json;
    std::string code;
    std::string message;
};

class Host {
public:
    virtual ~Host() = default;
    virtual HostResponse Call(const std::string& tool, const std::string& args_json) = 0;
};

struct Outcome {
    bool success = false;
    std::string status;
    std::string value_json;
    std::string failure_kind;
    std::string message;
    int calls_used = 0;
    std::int64_t engine_ticks = 0;
    std::int64_t current_memory_bytes = 0;
    std::int64_t duration_ms = 0;
};

class Engine {
public:
    Engine(Host& host, Limits limits);
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    Outcome Execute(const std::string& source);
    void Interrupt();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace dstu::script
