# JavaScript bundle sources

Run `npm ci --ignore-scripts --no-audit --no-fund`, then `node build.mjs`, using Node 24.
The lockfile records exact resolved package versions and integrity hashes. Build in
this directory or copy these sources to an isolated build directory and set
WUMING_SOURCE_ROOT to the repository root. esbuild is a build tool, not a runtime
artifact. No node_modules tree belongs in the public source snapshot.

Entry adapters are project-authored under the root GPL-3.0 license. Bundled packages
retain their own licenses; build.mjs copies their license/notice files and records
output hashes under third_party/javascript. No opaque inherited bundle is reused.
The API preserves the legacy cat/cheerio, CryptoJS global, and GBK helper entry points.
