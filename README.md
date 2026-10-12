# Dev2go

An Android app that runs the [opencode](https://github.com/anomalyco/opencode) coding agent
**on the phone**, with the features and appearance of OpenChamber, plus local models served
from the device itself.

Everything runs on-device. The agent is the real opencode, cross-compiled to arm64 Android.
The models are GGUF files the user picks from a catalog and downloads, served by an embedded
llama.cpp through an Ollama-compatible API so the agent registers them as an ordinary
provider.

## Status

See [`docs/verified-findings.md`](docs/verified-findings.md) for every claim and the
command that produced it.

**The UI is OpenChamber's.** Not a reimplementation — `@openchamber/web@2.3.0`'s own
mobile app (`mobile.html` -> `renderMobileApp`), served to a WebView from the app's
loopback HTTP server. OpenChamber already ships a mobile surface, and it is a *direct
opencode client*: of the 180 `/api` paths the desktop UI calls, the mobile one uses exactly
two, both `client-auth` pairing. That is the same architecture this app already had, so
shipping their UI beat rebuilding it.

**Verified working:**

- **The native runtime runs on a real phone** — `libdev2go.so` loads on arm64-v8a and
  reports `NEON | ARM_FMA | LLAMAFILE | OPENMP | KLEIDIAI | REPACK`.
- opencode v2.0.27 cross-compiles to a 192 MB arm64 Android binary, shipped in the APK.
- An embedded llama.cpp server binds and serves `/v1/models`, `/props` and
  `/v1/chat/completions`, proven against a real GGUF by `tools/dev2go_probe.cpp`.
- Signed release APK with OpenChamber's UI, the agent and the runtime bundled: **130 MB**.

**How the pieces fit:**

```
WebView --> WebUiServer --static--> OpenChamber mobile app (313 assets, 24 MB)
    |            |-- /api/client-auth/pairing/redeem --> { clientToken: <password> }
    |            `-- /d2go/{roots,list,read,write}   --> PhoneStorage
    |
    `-- direct, Basic auth --> opencode (bundled arm64 binary)
                                   `-- provider "ondevice" --> OllamaFacade --> llama.cpp
```

Nothing is proxied to opencode: it serves its own API with CORS open, so the app's calls —
including the long-lived `/api/event` SSE stream — go straight there. Only the two pairing
endpoints are answered locally.

**One patch to the bundle:** OpenChamber sends its client token as `Authorization: Bearer`,
but opencode's auth middleware accepts **only** Basic — verified against a live server,
Bearer returns 401 and Basic returns 200. `scripts/patch-openchamber-auth.mjs` rewrites the
header expression at build time. Patching beats proxying precisely because a proxy would
have to re-stream SSE, which the loopback server here does not do.

**Still unverified:** the whole thing has not been run on a phone. The agent binary has been
cross-compiled and bundled but never executed on hardware, and OpenChamber's UI has never
been loaded from the app's own assets.

## Phone files

The UI is a web app with a browser filesystem model, so the only way it can read a file is
over HTTP. `WebUiServer` exposes two roots:

- **The workspace** — the app's own directory, which is also opencode's working directory.
  Everything the agent writes lands here.
- **Mounted trees** — real phone storage via the Storage Access Framework. Grants are
  persisted with `takePersistableUriPermission`, otherwise every mount silently becomes
  unreadable after the app restarts.

```bash
curl http://127.0.0.1:18080/d2go/roots
curl 'http://127.0.0.1:18080/d2go/list?path=/'
curl 'http://127.0.0.1:18080/d2go/read?path=/notes.md'
```

Every path is canonicalised and checked to sit under a declared root before it is opened.
Without that, `../../databases/x.db` would hand the web UI an arbitrary-file-read primitive
on the device — and the UI is remote JavaScript.

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
./scripts/build-agent.sh             # optional: cross-compiles the arm64 opencode binary
./gradlew :core:test                 # catalog and provider-registration tests
./gradlew :app:assembleRelease
./scripts/sign-test-apk.sh \
    app/build/outputs/apk/release/app-release-unsigned.apk \
    dist/Dev2go-test.apk
```

`build-agent.sh` is optional: without `dist/opencode-arm64` the APK still builds and the
agent can be imported from device storage. With it the app is self-contained at 124 MB
rather than 43 MB.

The signing key is self-signed and generated on first use. **It is not for distribution** —
the password lives in the script, so anyone can produce an update the phone will accept.
Set `DEV2GO_KEYSTORE` and `DEV2GO_KEY_PASS` to sign with your own.

## Build requirements

JDK 17+, Android SDK (platform 35, build-tools 35.0.1+), NDK 28.2.13676358, CMake 3.22.1,
Bun 1.4.3+.

## Licence

Dev2go is not yet licensed. It depends on opencode (MIT) and llama.cpp (MIT).