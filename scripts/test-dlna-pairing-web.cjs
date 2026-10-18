// Local DOM/fetch doubles only; no server, sockets, browser, or devices.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/js/dlna-pair.js'), 'utf8');
function page(code, outcome = 'ok', language = 'en') {
    const nodes = Object.fromEntries(['title', 'instructions', 'pair', 'duration', 'status'].map(id => [id, {
        textContent: '', disabled: true, addEventListener(type, fn) { this.click = fn; }
    }]));
    const requests = [];
    const locations = [];
    vm.runInNewContext(source, {
        document: { getElementById: id => nodes[id] }, navigator: { language }, URLSearchParams,
        location: { pathname: '/dlna-pair.html', hash: code ? '#code=' + code : '' },
        history: { replaceState: (_, __, url) => locations.push(url) },
        fetch: async (url, options) => {
            requests.push({url, options});
            if (outcome === 'network') throw new Error('offline');
            return { ok: outcome === 'ok' };
        }
    });
    return { nodes, requests, locations };
}
(async () => {
    for (const code of [null, 'invalid', 'a'.repeat(63), 'a'.repeat(65)]) {
        const result = page(code);
        assert.equal(result.nodes.pair.disabled, true);
        assert.equal(result.requests.length, 0);
        assert.match(result.nodes.status.textContent, /scan/i);
    }
    for (const locale of ['en', 'zh-CN', 'zh-TW']) {
        const result = page('a'.repeat(64), 'ok', locale);
        assert.equal(result.requests.length, 0, 'QR previews must not automatically pair');
        assert.deepEqual(result.locations, ['/dlna-pair.html'], 'Remove the code from browser history');
        assert.equal(result.nodes.pair.disabled, false);
        await result.nodes.pair.click();
        assert.equal(result.requests.length, 1);
        const {url, options} = result.requests[0];
        assert.equal(url, '/dlna-pair');
        assert.equal(options.method, 'POST');
        assert.equal(options.headers['X-Pairing-Code'], 'a'.repeat(64));
        assert.equal(options.cache, 'no-store');
        assert.equal(result.nodes.pair.disabled, true);
        assert.match(result.nodes.status.textContent, /Paired|配对成功|配對成功/);
    }
    for (const outcome of ['forbidden', 'network']) {
        const result = page('b'.repeat(64), outcome);
        await result.nodes.pair.click();
        assert.match(result.nodes.status.textContent, /failed/i);
        assert.equal(result.nodes.pair.disabled, true);
    }
    console.log('PASS: invalid codes, explicit pairing, fragment removal, POST secret, localized success, and failure handling.');
})().catch(error => { console.error(error); process.exitCode = 1; });
