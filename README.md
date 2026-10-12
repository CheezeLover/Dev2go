# Dev2go

An Android app that runs the [opencode](https://github.com/anomalyco/opencode) coding agent
**on the phone**, with the features and appearance of OpenChamber, plus local models served
from the device itself.

Everything runs on-device. The agent is the real opencode, cross-compiled to arm64 Android.
The models are GGUF files the user picks from a catalog and downloads, served by an embedded
llama.cpp through an Ollama-compatible API so the agent registers them as an ordinary
provider.

## Status

The two riskiest parts are done and measured. See
[`docs/verified-findings.md`](docs/verified-findings.md) for every claim and the command
that produced it.

**Working:**

- opencode v2.0.27 cross-compiles to a 192 MB arm64 Android binary
  (`bun build --compile --target=bun-android-arm64`), linking only libc/libm/libdl.
- An embedded llama.cpp server binds, serves `/v1/models`, `/props` and
  `/v1/chat/completions`, and returns real generated tokens — proven against a real GGUF by
  `tools/dev2go_probe.cpp`, which links the same library the app does.
- **A signed, installable release APK builds**, with `libdev2go.so` exporting all four JNI
  entry points for arm64-v8a and x86_64.

**What the current APK does:** loads, picks a GGUF from device storage through the Storage
Access Framework, copies it into app storage, starts the embedded server, and shows what
`/v1/models` reports. That is a test harness, deliberately — it answers the one question no
amount of x86-64 testing can, which is whether the runtime works on real phone hardware and
real phone storage.

**Not yet built:** the OpenChamber UI, the Ollama `/api/*` facade, the model catalog and
downloader, and the agent process supervisor.

**Unverified:** nothing has run on a physical phone. Every measurement so far is x86-64.

## Why it is built this way

- **The agent is opencode, not a reimplementation.** The requirement is opencode's full
  feature set running locally — providers, MCP, plugins, permissions. All of that lives in the
  MIT-licensed upstream codebase. Reimplementing the agent loop in Kotlin could not reproduce
  it, so the binary ships in the APK.
- **The model server is llama.cpp, not Ollama.** There is no official Ollama build for
  Android. llama.cpp's server embeds in-process via `cli-server.h` and already speaks the
  OpenAI-compatible surface opencode's Ollama provider consumes. Ollama-native `/api/*`
  routes are added in Kotlin on top; llama.cpp ships none.
- **The UI is native Compose.** WebView gives faster feature parity but feels like a website.
  The stack here is the same one Chuse already proves on device.

## Known trade-offs

Embedding the agent in-process costs two features that have no Android equivalent:

- **Recursive directory watching** — `@parcel/watcher` has no android prebuild. File and
  single-entry watches still work via `node:fs.watch`.
- **Host terminal sessions** — no PTY binary is embedded.

## Layout

```
core/                      plain JVM: catalog, ollama naming, protocol shapes. No Android.
app/src/main/cpp/          embedded llama.cpp server, JNI shim, host probe
  tools/dev2go_probe.cpp   runs the real server against a real GGUF, no phone needed
app/src/main/java/com/dev2go/
  agent/                   supervises the opencode process
  models/                  catalog, downloader, model store
  runtime/                 JNI bindings for the LLM server
  ui/                      Compose
scripts/                   pinned, checksum-verified fetchers
patches/                   the opencode Android adaptation
third_party/               fetched, never committed
```

`core` is a plain JVM module so the protocol and catalog logic is testable without an
emulator. `scripts/fetch-*.sh` keep tens or hundreds of megabytes of upstream source out of
git while staying reproducible.

## Building

```bash
./scripts/fetch-llama.sh      # pinned llama.cpp v0.6.0, checksum-verified
./scripts/fetch-opencode.sh   # pinned opencode v2.0.27 + android adaptation (needs bun)
```

Probe the native layer on a workstation, against any GGUF:

```bash
cmake -S app/src/main/cpp -B build/probe -DCMAKE_BUILD_TYPE=Release
cmake --build build/probe --target dev2go_probe -j
LD_LIBRARY_PATH=build/probe/bin build/probe/dev2go_probe <model.gguf> 18080
```

Build the APK:

```bash
./gradlew :core:test                # catalog and provider-registration tests
./gradlew :app:assembleRelease
./scripts/sign-test-apk.sh \
    app/build/outputs/apk/release/app-release-unsigned.apk \
    dist/Dev2go-test.apk
```

The signing key is self-signed and generated on first use. **It is not for distribution** —
the password lives in the script, so anyone can produce an update the phone will accept.
Set `DEV2GO_KEYSTORE` and `DEV2GO_KEY_PASS` to sign with your own.

## Build requirements

JDK 17+, Android SDK (platform 35, build-tools 35.0.1+), NDK 28.2.13676358, CMake 3.22.1,
Bun 1.4.3+.

## Licence

Dev2go is not yet licensed. It depends on opencode (MIT) and llama.cpp (MIT).