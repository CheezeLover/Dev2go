// Rewrites OpenChamber's mobile bundle to use HTTP Basic instead of Bearer.
//
// Why: opencode's auth middleware accepts HTTP Basic with username `opencode` and the
// server password. It does not accept Bearer — verified against a running `opencode serve`:
//
//     /api/session   Authorization: Bearer <pw>   -> 401
//     /api/session   Authorization: Basic  …      -> 200
//
// OpenChamber issues a `clientToken` and sends it as Bearer, because that token is minted
// by OpenChamber's own server. Running opencode directly — which is the whole point here —
// means the token *is* the server password and has to go in a Basic header.
//
// Patching the bundle beats proxying the API instead. A proxy would have to translate every
// request, and worse it would have to stream: opencode's `/api/event` is a long-lived SSE
// connection, and the loopback server here is request/response. Rewriting one header
// expression avoids both problems and leaves the mobile app talking to opencode directly,
// exactly as it does on a desktop.
import { readdirSync, readFileSync, writeFileSync } from "node:fs"
import { join } from "node:path"

const dir = process.argv[2]
if (!dir) {
  console.error("usage: patch-openchamber-auth.mjs <assetsDir>")
  process.exit(2)
}

// Matched on the shape, not on the minified identifier: the variable holding the token is
// renamed by every build, so patching `Bearer ${i}` literally would silently stop matching.
const BEARER = /Authorization:\s*`Bearer \$\{([A-Za-z_$][\w$]*)\}`/g
const BASIC = 'Authorization: "Basic " + btoa("opencode:" + $1)'

let patchedFiles = 0
let patchedSites = 0

for (const name of readdirSync(dir)) {
  if (!name.endsWith(".js")) continue
  const path = join(dir, name)
  const source = readFileSync(path, "utf8")
  if (!source.includes("Bearer")) continue

  let count = 0
  const next = source.replace(BEARER, (_m, token) => {
    count++
    return BASIC.replace("$1", token)
  })

  if (count > 0) {
    writeFileSync(path, next)
    patchedFiles++
    patchedSites += count
  }
}

if (patchedSites === 0) {
  console.error("No Bearer header sites found — the bundle layout may have changed.")
  process.exit(1)
}

console.log(`patched ${patchedSites} auth site(s) across ${patchedFiles} file(s) -> HTTP Basic`)