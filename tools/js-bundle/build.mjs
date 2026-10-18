import { build } from 'esbuild';
import { readFileSync, writeFileSync, mkdirSync, readdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const root = process.env.WUMING_SOURCE_ROOT || resolve(here, '../..');
const output = resolve(root, 'libraries/js-runtime/src/main/resources/js/lib');
const notices = resolve(root, 'third_party/javascript');
mkdirSync(notices, { recursive: true });
const common = { absWorkingDir: here, bundle: true, platform: 'browser', target: 'es2020', minify: true,
    legalComments: 'inline', alias: { crypto: resolve(here, 'empty-crypto.js') }, logLevel: 'warning' };
const artifacts = [];
for (const [source, file, format] of [['cat-entry.js','cat.js','esm'],['cheerio-entry.js','cheerio.min.js','esm'],
        ['gbk-entry.js','gbk.js','esm'],['crypto-entry.js','crypto-js.js','iife']]) {
    const outfile = resolve(output, file);
    await build({ ...common, entryPoints: [source], outfile, format });
    artifacts.push({ path: relative(root, outfile), sha256: createHash('sha256').update(readFileSync(outfile)).digest('hex') });
}
const lock = JSON.parse(readFileSync(resolve(here, 'package-lock.json')));
const packages = [];
for (const [path, info] of Object.entries(lock.packages)) {
    if (!path || info.dev) continue;
    const dir = resolve(here, path);
    const metadata = JSON.parse(readFileSync(resolve(dir, 'package.json')));
    const name = metadata.name;
    const files = readdirSync(dir).filter(f => /^(license|licence|copying|notice)(\.|$)/i.test(f));
    const fallback = name === 'boolbase' ? resolve(here, 'upstream-licenses/boolbase-LICENSE') : null;
    if (!files.length && !fallback) throw new Error('Missing license file for ' + name);
    const target = resolve(notices, name.replaceAll('/', '__') + '-' + metadata.version);
    mkdirSync(target, { recursive: true });
    if (!files.length) writeFileSync(resolve(target, 'LICENSE'), readFileSync(fallback));
    for (const file of files) writeFileSync(resolve(target, file), readFileSync(resolve(dir, file)));
    packages.push({ name, version: metadata.version, license: metadata.license, resolved: info.resolved,
        integrity: info.integrity, noticeDirectory: relative(root, target) });
}
writeFileSync(resolve(notices, 'inventory.json'), JSON.stringify({ recipe:'tools/js-bundle/build.mjs',
    lockfile: 'tools/js-bundle/package-lock.json', artifacts, packages }, null, 2) + '\n');
console.log('Built four JS libraries and preserved package license files.');
