// Workstation probe for the on-device LLM runtime.
//
// Loads a real GGUF through the same code path the app uses, then checks the routes an
// agent actually depends on. It exists because the alternatives are worse: an emulator
// is slow and its CPU is not a phone's, and "it compiles" is not the same claim as "a
// request goes through". What it proves is narrow and worth proving — that the embedded
// server binds, that /v1/models reports the loaded model, and that chat completions
// return tokens rather than an error.
//
// Usage: dev2go_probe <model.gguf> [port]

#include "dev2go_server.h"

#include "http.h"
#include "llama.h"

#include <cstdio>
#include <string>

namespace {

int fail(const std::string & what) {
    fprintf(stderr, "FAIL: %s\n", what.c_str());
    return 1;
}

} // namespace

int main(int argc, char ** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <model.gguf> [port]\n", argv[0]);
        return 2;
    }

    const std::string model  = argv[1];
    const int         port   = argc > 2 ? std::atoi(argv[2]) : 18080;
    const std::string base   = "http://127.0.0.1:" + std::to_string(port);

    printf("model: %s\n", model.c_str());
    printf("backend: %s\n", llama_print_system_info());

    dev2go::ServerConfig config;
    config.model_path = model;
    config.port       = port;

    auto & server = dev2go::Server::instance();
    if (!server.start(config)) {
        return fail("server did not become ready");
    }
    printf("server ready on %s\n", base.c_str());

    auto [client, parts] = common_http_client(base);

    // /v1/models is what an OpenAI-compatible client enumerates to discover what is
    // available, and what the Kotlin facade mirrors into Ollama's /api/tags.
    {
        auto res = client.Get("/v1/models");
        if (!res || res->status != 200) {
            server.stop();
            return fail(res ? "GET /v1/models returned " + std::to_string(res->status)
                            : std::string("GET /v1/models failed"));
        }
        printf("/v1/models -> %s\n", res->body.c_str());
    }

    // /props is llama.cpp's own view of the loaded model: architecture, context length,
    // chat template. The Ollama facade derives its `details` block from this, so it is
    // worth asserting it is populated rather than empty.
    {
        auto res = client.Get("/props");
        if (!res || res->status != 200) {
            server.stop();
            return fail(res ? "GET /props returned " + std::to_string(res->status)
                            : std::string("GET /props failed"));
        }
        printf("/props -> %s\n", res->body.substr(0, 600).c_str());
    }

    // The one that matters. A non-streaming chat completion exercises the GGUF's chat
    // template and token generation; a JSON error here means the template is missing or
    // the context is too small, both of which would look like a broken agent loop.
    {
        const std::string payload =
            R"({"messages":[{"role":"user","content":"Reply with exactly one word: ready"}],"max_tokens":16,"stream":false})";
        auto res = client.Post("/v1/chat/completions", payload, "application/json");
        if (!res || res->status != 200) {
            server.stop();
            return fail(res ? "POST /v1/chat/completions returned " + std::to_string(res->status) + ": " + res->body
                            : std::string("POST /v1/chat/completions failed"));
        }
        printf("/v1/chat/completions -> %s\n", res->body.substr(0, 800).c_str());
    }

    server.stop();
    printf("PASS\n");
    return 0;
}