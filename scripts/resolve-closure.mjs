// Resolves the exact asset closure of OpenChamber's mobile entry point.
//
// The dist/ directory holds the desktop build too — 47 MB of chunks for views this app has
// no use for (GitHub, tunnels, dev servers, browser control). The mobile entry pulls in
// 129 of them; copying the whole directory would ship four times the payload for code that
// is never loaded.
//
// Vite writes its dynamic-import map as a literal array in the entry chunk, so the closure
// is recoverable by reading that map and following it transitively. Following it matters:
// chunks reference further chunks, and a single pass leaves a lot behind.
import { existsSync, readdirSync, readFileSync, mkdirSync, copyFileSync, statSync } from "node:fs"
import { join } from "node:path"

const [, , fromDir, toDir] = process.argv
if (!fromDir || !toDir) {
  console.error("usage: resolve-closure.mjs <assetsDir> <outDir>")
  process.exit(2)
}

const entries = readdirSync(fromDir)
const byName = new Map(entries.map((f) => [f, join(fromDir, f)]))

const seen = new Set()
const queue = []

/** Pulls `assets/foo.js` references out of a chunk's source. */
function refs(file) {
  const source = readFileSync(file, "utf8")
  const found = new Set()
  for (const m of source.matchAll(/(?:"|'|`)assets\/([^"'`]+?)(?:["'`])/g)) {
    found.add(m[1])
  }
  return [...found]
}

function visit(name) {
  if (seen.has(name)) return
  const path = byName.get(name)
  // A reference to something not in dist/assets is not ours to stage.
  if (!path || !existsSync(path)) return
  seen.add(name)
  queue.push(path)
  for (const ref of refs(path)) visit(ref)
}

// Seed from the mobile entry, and from mobile.html itself so anything it preloads counts.
visit("renderMobileApp-gDKxRJQi.js")
const html = join(fromDir, "..", "mobile.html")
try {
  for (const ref of refs(html)) visit(ref)
} catch {
  // html lives outside assets/; refs() still works on the path when it exists.
}

mkdirSync(toDir, { recursive: true })
let bytes = 0
for (const path of queue) {
  copyFileSync(path, join(toDir, path.split("/").pop()))
  bytes += statSync(path).size
}

console.log(`closure: ${queue.length} files, ${(bytes / 1048576).toFixed(1)} MB`)