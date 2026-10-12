#include "dev2go_server.h"

#include "llama.h"

// Order matters: cli-server.h includes only http.h and relies on the includer having
// already pulled in the full common_params definition. Including it first leaves
// common_params incomplete and every use of it fails.
#include "common.h"
#include "arg.h"
#include "http.h"
#include "cli-server.h"
#include "server-stream.h"

#include <chrono>
#include <string>
#include <vector>

namespace dev2go {

namespace {

// llama_server() brings the listener up inside its own thread, so for a moment after
// the thread starts there is nothing bound yet. /health is the right readiness probe
// rather than a raw connect: llama.cpp answers 503 while the model is still loading
// and 200 only once it can actually serve, which is the distinction the Kotlin side
// needs when it decides whether the provider is usable.
bool wait_ready(int port, std::atomic<bool> & should_stop, int timeout_ms) {
    const std::string address = "http://127.0.0.1:" + std::to_string(port);
    const auto         deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);

    while (std::chrono::steady_clock::now() < deadline) {
        if (should_stop.load()) {
            return false;
        }
        auto [client, parts] = common_http_client(address);
        client.set_connection_timeout(1, 0);
        auto res = client.Get("/health");
        if (res && res->status == 200) {
            return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    return false;
}

} // namespace

Server & Server::instance() {
    static Server server;
    return server;
}

Server::~Server() {
    stop();
}

bool Server::start(const ServerConfig & config) {
    if (running_.load()) {
        return false;
    }
    stopping_.store(false);

    // common_init() and the stream session manager are started by the argc/argv entry
    // point, which we do not go through: the app supplies a model it downloaded, not a
    // --model argument. They must be paired with their stop calls in Server::stop().
    common_init();
    server_stream_session_manager_start();
    llama_backend_init();
    llama_numa_init(GGML_NUMA_STRATEGY_DISABLED);

    common_params params;

    // Build the same argv llama-server itself would get and hand it to
    // common_params_parse, rather than assigning fields by hand. Hand-assigning looks
    // equivalent and is not: common_params carries a lot of interdependent state that
    // the parser normalises, and getting it subtly wrong fails deep inside model load
    // with a nonsensical allocation size rather than at the point of the mistake. Going
    // through the parser also means a new parameter in a future llama.cpp is handled
    // correctly for free.
    std::string n_ctx        = std::to_string(config.n_ctx);
    std::string n_threads    = std::to_string(config.n_threads);
    std::string n_batch      = std::to_string(config.n_batch);
    std::string n_ubatch     = std::to_string(config.n_ubatch);
    std::string n_gpu_layers = std::to_string(config.n_gpu_layers);
    std::string port         = std::to_string(config.port);

    std::vector<std::string> owned = {
        "llama-server",
        "--model",        config.model_path,
        "--host",         "127.0.0.1",
        "--port",         port,
        "--ctx-size",     n_ctx,
        "--threads",      n_threads,
        "--batch-size",   n_batch,
        "--ubatch-size",  n_ubatch,
        "--n-gpu-layers", n_gpu_layers,
        // Tool calling is rendered through the GGUF's own chat template. Without this
        // the agent's tool definitions are dropped silently, which surfaces as an agent
        // that never calls a tool rather than as an error.
        "--jinja",
    };
    if (!config.warmup) {
        owned.push_back("--no-warmup");
    }

    std::vector<char *> argv;
    argv.reserve(owned.size());
    for (auto & arg : owned) {
        argv.push_back(arg.data());
    }

    if (!common_params_parse((int) argv.size(), argv.data(), params, LLAMA_EXAMPLE_SERVER)) {
        stop();
        return false;
    }

    running_.store(true);
    worker_ = std::thread([params, this]() mutable {
        // argc/argv are only used by router mode. This is the same call the `llama`
        // CLI makes in cli-server.h, so the route set we get is identical to
        // llama-server's: /v1/models, /v1/chat/completions, /health, /props, and the
        // rest, including llama.cpp's own UI.
        const int res = llama_server(params, 0, nullptr);
        if (res != 0) {
            fprintf(stderr, "llama_server exited with code %d\n", res);
        }
        running_.store(false);
    });

    if (!wait_ready(config.port, stopping_, config.ready_timeout_ms)) {
        stop();
        return false;
    }
    return true;
}

void Server::stop() {
    if (stopping_.exchange(true)) {
        return;
    }
    if (worker_.joinable()) {
        llama_server_terminate();
        worker_.join();
    }
    running_.store(false);
    llama_backend_free();
    server_stream_session_manager_stop();
}

} // namespace dev2go