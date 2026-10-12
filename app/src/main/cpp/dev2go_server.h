// Dev2go's on-device LLM runtime.
//
// Embeds llama.cpp's own HTTP server in-process rather than shipping llama-server as a
// second process. tools/cli/cli-server.h is upstream's own supported way to do this
// (`llama_server` is documented there as "available as a dynamic library symbol"), and
// using it means the route set we get is exactly llama-server's: /v1/models,
// /v1/chat/completions, /v1/completions, /v1/responses, /health, /props.
//
// Reusing those routes is the point. The Ollama-native /api/* surface is built in
// Kotlin on top as a translation layer (OllamaFacade.kt): llama.cpp v0.6.0 ships no
// /api/* routes at all, and translating in Kotlin keeps the whole inference path —
// chat templating, grammar-guided tool-call parsing — upstream's, where it already works.
#pragma once

#include <atomic>
#include <string>
#include <thread>

namespace dev2go {

struct ServerConfig {
    std::string model_path;
    int         port             = 8080;
    int         n_ctx            = 4096;
    int         n_threads        = 4;
    int         n_batch          = 256;
    int         n_ubatch         = 256;
    // Android has no CUDA or Metal in llama.cpp's sense; inference runs on the CPU.
    int         n_gpu_layers     = 0;
    bool        warmup           = false;
    // Loading a multi-gigabyte GGUF from phone storage is slow enough that a short
    // timeout would report failure for a model that is merely still arriving.
    int         ready_timeout_ms = 120000;
};

// Owns the one running server. llama.cpp's backend and its shutdown handler are
// process-wide, so a second concurrent instance is not meaningful — one runtime, one
// model, one port at a time.
class Server {
  public:
    static Server & instance();

    // Returns false when the model could not be loaded or the port is taken. Safe to
    // call again after stop().
    bool start(const ServerConfig & config);
    void stop();
    bool running() const { return running_.load(); }

  private:
    Server() = default;
    ~Server();
    Server(const Server &) = delete;
    Server & operator=(const Server &) = delete;

    std::thread       worker_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopping_{false};
};

} // namespace dev2go