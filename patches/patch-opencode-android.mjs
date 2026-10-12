// Derives packages/cli/script/build-android.ts from upstream build.ts by adding an
// android-arm64 target, then writes the parcel-watcher stub that target points at.
//
// Run from the root of the opencode checkout, via scripts/fetch-opencode.sh. Upstream
// ships no android target, so this file is the whole of our adaptation — three
// edits, listed in the comments below.
//
// The stub is deliberately a no-op rather than a real recursive watcher.
// packages/core/src/filesystem/watcher.ts already degrades on its own: getBackend()
// has no android case so it returns undefined, and subscribeDirectory() returns
// undefined when the native binding is missing. File and single-entry watches keep
// working through the node:fs.watch path in nativeLayer, which never touches parcel.
import { readFileSync, writeFileSync } from "node:fs"
import { resolve } from "node:path"

const src = "packages/cli/script/build.ts"
const dst = "packages/cli/script/build-android.ts"
let s = readFileSync(src, "utf8")
const before = s

const stub = resolve("packages/core/src/filesystem/watcher-android-stub.ts")
writeFileSync(
  stub,
  `// Android has no @parcel/watcher prebuild. Recursive directory watching is
// unsupported there; watcher.ts already logs and no-ops when this is absent.
// Files and single-entry watches still work via node:fs.watch.
export default function loadAndroidWatcherStub(): undefined {
  return undefined
}
`,
)

// 1) add android to the target table
const table = s.match(/const allTargets:[\s\S]*?\n\]\n/)[0]
s = s.replace(table, table.replace(/\]\n$/, `  { os: "android", arch: "arm64" },\n]\n`))

// 2) loosen the target type: android needs neither abi nor avx2
s = s.replace(
  /const allTargets: \{[\s\S]*?\}\[\] =/,
  `const allTargets: { os: string; arch: "arm64" | "x64"; abi?: "musl"; avx2?: false }[] =`,
)

// 3) @parcel/watcher has no android prebuild; resolve it to the local stub so the
//    bundle contains a real module instead of a require that throws at runtime.
s = s.replace(
  'const parcelWatcherPackage = `@parcel/watcher-${item.os}-${item.arch}${item.os === "linux" ? `-${item.abi ?? "glibc"}` : ""}`',
  () =>
    `const parcelWatcherPackage = item.os === "android" ? ${JSON.stringify(stub)} : ` +
    '`@parcel/watcher-${item.os}-${item.arch}${item.os === "linux" ? `-${item.abi ?? "glibc"}` : ""}`',
)

// 4) targetName is what becomes the bun compile target:
//    opencode-android-arm64 -> bun-android-arm64, which Bun understands.
s = s.replace(
  /function targetName\(item:[\s\S]*?\n\}\n/,
  `function targetName(item: { os: string; arch: "arm64" | "x64"; abi?: "musl"; avx2?: false }) {
  return [
    binary,
    item.os === "win32" ? "windows" : item.os,
    item.arch === "arm64" ? "arm64" : "x64",
    item.avx2 === false ? "baseline" : undefined,
    item.abi,
  ]
    .filter(Boolean)
    .join("-")
}
`,
)

writeFileSync(dst, s)
console.log("changed:", s !== before)
console.log("android target:", s.includes('{ os: "android", arch: "arm64" }'))
console.log("stub path inlined:", s.includes(stub))