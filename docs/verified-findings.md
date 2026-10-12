# Verified findings

Everything here was measured, not inferred. Each claim names the command that produced
it so it can be re-checked rather than taken on trust.

## 1. opencode can be cross-compiled to Android

**Verified.** `anomalyco/opencode` is MIT licensed and the whole of the agent — providers,
MCP, plugins, permissions, sessions — lives in it, so shipping the real thing is what makes
"all opencode features, locally hosted on the phone" reachable rather than approximated.

```bash
bun build --compile --target=bun-android-arm64 hello.ts --outfile hello-arm64
```

```text
ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked,
interpreter /system/bin/linker64, for Android 28, built by NDK r27c
NEEDED: libc.so, libm.so, libdl.so
```

Those three libraries are all present on Android, and "for Android 28" is why **`minSdk` is
28** — Bun's android runtime is built for API 28, so it cannot go lower without rebuilding
Bun.

**Building the actual agent.** `scripts/fetch-opencode.sh` pins commit `1c74b9f` (v2.0.27)
and `patches/patch-opencode-android.mjs` derives `build-android.ts` from upstream's
`build.ts`. Upstream ships **no android target** — `grep -i android packages/cli/script/`
returns nothing — so this adaptation is ours. It makes three edits and no others:

| # | Change | Why |
|---|---|---|
| 1 | add `{ os: "android", arch: "arm64" }` to the target table | upstream has no android entry |
| 2 | resolve `@parcel/watcher` to a local no-op stub | no `android-arm64` prebuild exists |
| 3 | `targetName()` emits `opencode-android-arm64` | becomes the `bun-android-arm64` compile target |

The stub needs no watcher implementation. `packages/core/src/filesystem/watcher.ts` already
degrades by itself: `getBackend()` has no android case, and `subscribeDirectory()` returns
undefined when the binding is missing. File and single-entry watches keep working through
the `node:fs.watch` path in `nativeLayer`, which never touches parcel. **What is lost is
recursive directory watching** — live reload of an edited file tree.

The PTY needs no patch either: `resolveOpencodePty()` returns undefined for anything that is
not darwin/linux, and the build already emits `export default undefined` for it. **What is
lost is host terminal sessions.**

Result: a 192 MB arm64 Android binary linking only libc/libm/libdl.

```text
-rwxr-xr-x 1 openchamber openchamber 192156872 opencode
ELF 64-bit LSB pie executable, ARM aarch64, interpreter /system/bin/linker64, for Android 28
```

## 2. llama.cpp's server embeds cleanly, and has no Ollama routes

llama.cpp v0.6.0 ships `tools/cli/cli-server.h`, which documents `llama_server` as
"available as a dynamic library symbol" and contains upstream's own `cli_server` struct that
spawns it on a thread. So the server is embedded rather than reimplemented, and the route set
is exactly llama-server's: `/v1/models`, `/v1/chat/completions`, `/v1/completions`,
`/v1/responses`, `/health`, `/props`.

**llama.cpp has no Ollama-native routes at all.** `grep -rn -i ollama tools/server/` returns
**zero** matches at v0.6.0. Those are added in Kotlin (`OllamaFacade.kt`) as a translation
layer, which keeps the entire inference path — chat templating, grammar-guided tool-call
parsing — upstream's.

This does not conflict with how opencode consumes Ollama. A live Ollama serves both surfaces:

```bash
curl http://192.168.3.129:11434/v1/models   # {"object":"list","data":[{"id":"qwen3.5:0.8b",...}]}
curl http://192.168.3.129:11434/api/tags    # {"models":[{"name":"qwen3.5:0.8b",...}]}
```

and opencode's own provider config points at the OpenAI-compatible one — `baseURL` ends in
`/v1`, not `/api`. So `/v1/*` is the surface the agent actually uses, and it is the one
llama.cpp already provides.

## 3. `common_params` must go through `common_params_parse`

**This cost the most time and is the most reusable finding.** Assigning `common_params` fields
by hand looks equivalent to passing CLI flags and is not. It compiled, linked, loaded the
model, and then died deep inside model allocation:

```text
I cmn init: llama threadpool init, n_threads = 4
E ggml_aligned_malloc: insufficient memory (attempted to allocate 17592186044416.00 MB)
PROBE_EXIT=139   # SIGSEGV
```

`17592186044416 MB` is `2^64` bytes — a size computed from state that was never initialised
the way the parser initialises it. The same flags on the real binary work fine:

```bash
llama-server -m qwen3-0.6b.gguf --host 127.0.0.1 --port 18081 -c 4096 -t 4 -b 256 -ub 256 -ngl 0 --jinja
# -> model loaded / listening on http://127.0.0.1:18081 / {"status":"ok"}
```

`common_params` carries a lot of interdependent state that `common_params_parse` normalises.
Rebuilding an argv and handing it to the parser fixes it and is also more future-proof: a new
parameter in a future llama.cpp is handled correctly for free. **Hand-assigning it fails
vaguely and far from the mistake; use the parser.**

## 4. The embedded server works end to end

`app/src/main/cpp/tools/dev2go_probe.cpp` links the same `dev2go_server` static library the
app's JNI shim links, loads a real GGUF, and asserts the routes an agent depends on.

```bash
dev2go_probe qwen3-0.6b.gguf 18080
```

```text
server ready on http://127.0.0.1:18080
/v1/models -> {... "id":"qwen3-0.6b.gguf", "n_params":751632384, "size":798801920, "ftype":"Q8_0" ...}
/props -> {... "temperature":0.6, "top_p":0.95, ...}
/v1/chat/completions -> {"choices":[{"finish_reason":"length","message":{"role":"assistant",
  "reasoning_content":"Okay, the user wants me to reply with exactly one word: \""}}],
  "usage":{"completion_tokens":16,"prompt_tokens":15,"total_tokens":31},
  "timings":{"prompt_per_second":162.5,"predicted_per_second":40.8}}
PASS
```

The chat completion is the one that matters. A JSON error there would mean the chat template
is missing or the context is too small — both of which look like a broken agent loop rather
than like an error.

Note the model id is the **full path**, not a bare name. The Ollama facade is what turns that
into `qwen3:0.6b` with the `details` block Ollama clients expect.

## 5. Build configuration that silently produces the wrong thing

Three of these cost a build each and none of them fail loudly:

- **`LLAMA_BUILD_COMMON` defaults to OFF** for a library-style build, which means `tools/` is
  never added and `llama-server-impl` does not exist. The failure surfaces as
  `cannot find -lllama-server-impl` — at link time, looking like a path problem. Forced ON in
  `CMakeLists.txt` alongside `LLAMA_BUILD_MTMD`/`TOOLS`/`SERVER`, because Gradle invokes
  CMake with no arguments of its own.
- **`common/http.h` includes `<cpp-httplib/httplib.h>`**, which resolves against llama.cpp's
  `vendor/`. The `cpp-httplib` target carries that privately, so it does not reach a consumer
  of `common/http.h`.
- **`cli-server.h` includes only `http.h`** and assumes the includer already has the full
  `common_params` definition. Include it first and every use of `common_params` fails with a
  confusing "cannot be used as a function".
- Digit separators in a header need C++14, and llama.cpp narrows `CMAKE_CXX_STANDARD` for its
  own targets. `target_compile_features(... cxx_std_17)` did not fix it; using plain literals
  did.

## Not verified

Stated plainly because the gap matters:

- **Nothing has run on a physical phone.** Every measurement above is x86-64. The arm64 build
  compiles and its symbols are correct; on-device behaviour is unproven.
- **The opencode Android binary has never been executed**, only cross-compiled and linked
  against libc/libm/libdl.
- **On-device speed is unknown.** The 40 tokens/s above is a desktop x86 CPU.
- **Tool calling through the chat template is untested.** `--jinja` is set, but no tool-call
  round trip has been run.
- The recursive directory watcher and PTY losses above are inferred from reading the code,
  not observed.

## Build requirements

JDK 17+, Android SDK (platform 36, build-tools 36.0.0), NDK 28.2.13676358, CMake 3.22.1,
Bun 1.4.3+, plus `scripts/fetch-llama.sh` and `scripts/fetch-opencode.sh` before building.