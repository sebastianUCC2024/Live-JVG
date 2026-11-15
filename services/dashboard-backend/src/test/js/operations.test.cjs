const { test } = require('node:test');
const assert = require('node:assert/strict');
const { PendingOperations } = require('../../main/resources/static/operations.js');

function memory() {
  const values = new Map();
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
}
function locks() {
  let held = false;
  return { async request(name, options, action) {
    if (held) return action(null);
    held = true;
    try { return await action({ name }); } finally { held = false; }
  } };
}
function response(status, data) { return { ok: status >= 200 && status < 300, status, json: async () => data }; }
function harness(options = {}) {
  const storage = options.storage || memory();
  const coordinator = options.locks || locks();
  const requests = [];
  let ids = 0, timestamps = 0;
  const instance = new PendingOperations({ storage, locks: coordinator,
    uuid: () => `id-${++ids}`, now: () => { timestamps++; return '2026-11-15T00:00:00.123Z'; },
    fetch: async (url, init) => {
      requests.push({ url, ...init });
      if (options.fetch) return options.fetch(url, init, storage);
      return init.method === 'POST' ? response(202, JSON.parse(init.body)) : response(200, { processed: false });
    }, timeoutMs: options.timeoutMs || 50,
  });
  return { instance, storage, coordinator, requests, ids: () => ids, timestamps: () => timestamps };
}
const fields = { event_type: 'GOAL', team_id: 'home', minute: 10, reference_event_id: null };

test('success persists the entire body before sending, then retains it until processing is confirmed', async () => {
  const h = harness({ fetch: async (url, init, storage) => {
    const saved = JSON.parse(storage.getItem('live-jvg.pending-operation.v1'));
    assert.equal(saved.body, init.body);
    return response(202, JSON.parse(init.body));
  } });
  await h.instance.begin('m', fields);
  assert.equal(h.instance.read().phase, 'accepted');
  assert.deepEqual(JSON.parse(h.instance.read().body), { ...fields, event_id: 'id-1', timestamp: '2026-11-15T00:00:00.123Z' });
  assert.equal(h.ids(), 1); assert.equal(h.timestamps(), 1);
});

test('timeout is uncertain and preserves the operation', async () => {
  const h = harness({ timeoutMs: 5, fetch: (url, init) => new Promise((resolve, reject) => {
    init.signal.addEventListener('abort', () => reject(new Error('timeout')), { once: true });
  }) });
  await h.instance.begin('m', fields);
  assert.equal(h.instance.read().phase, 'uncertain');
  assert.equal(h.instance.read().uncertain, true);
  assert.equal(h.ids(), 1);
});

test('timeout also covers reading a response body', async () => {
  const h = harness({ timeoutMs: 5, fetch: async (url, init) => ({ ok: true, status: 202,
    json: () => new Promise((resolve, reject) => init.signal.addEventListener('abort', () => reject(new Error('body lost')))) }) });
  await h.instance.begin('m', fields);
  assert.equal(h.instance.read().phase, 'uncertain');
});

test('lost response retries with byte-identical UUID, timestamp, reference and body', async () => {
  let posts = 0;
  const h = harness({ fetch: async (url, init) => {
    if (init.method !== 'POST') return response(200, { processed: false });
    if (++posts === 1) throw new TypeError('response lost');
    return response(202, JSON.parse(init.body));
  } });
  await h.instance.begin('m', fields);
  const original = h.instance.read().body;
  await h.instance.retry();
  const bodies = h.requests.filter(r => r.method === 'POST').map(r => r.body);
  assert.deepEqual(bodies, [original, original]);
  assert.equal(h.ids(), 1); assert.equal(h.timestamps(), 1);
});

test('double click cannot start another operation while the first request is pending', async () => {
  let acknowledge;
  const h = harness({ fetch: (url, init) => new Promise(resolve => { acknowledge = () => resolve(response(202, JSON.parse(init.body))); }) });
  const first = h.instance.begin('m', fields);
  assert.equal(await h.instance.begin('m', fields), false);
  acknowledge(); await first;
  assert.equal(h.requests.length, 1); assert.equal(h.ids(), 1);
});

test('reload restores sending state as uncertain without publishing or generating an identity', async () => {
  const h = harness({ fetch: async () => { throw new TypeError('lost'); } });
  await h.instance.begin('m', fields);
  const saved = h.instance.read(); saved.phase = 'sending'; h.instance.save(saved);
  const reloaded = harness({ storage: h.storage, locks: h.coordinator });
  assert.equal(reloaded.instance.read().phase, 'uncertain');
  assert.equal(reloaded.requests.length, 0); assert.equal(reloaded.ids(), 0);
  await reloaded.instance.retry();
  assert.equal(reloaded.requests.find(r => r.method === 'POST').body, saved.body);
  assert.equal(reloaded.ids(), 0);
});

test('definite initial validation rejection is retained for explicit dismissal and is not retried', async () => {
  const h = harness({ fetch: async () => response(400, { error: 'invalid' }) });
  await h.instance.begin('m', fields);
  assert.equal(h.instance.read().phase, 'rejected');
  assert.equal(await h.instance.retry(), false);
  assert.equal(await h.instance.begin('m', fields), false);
  assert.equal(h.requests.length, 1);
  assert.equal(await h.instance.discard(() => false), false);
  assert.ok(h.instance.read());
  await h.instance.discard(warning => { assert.match(warning, /NO cancela/); return true; });
  assert.equal(h.instance.read(), null); assert.equal(h.ids(), 1);
});

test('a validation rejection after a lost response cannot erase uncertainty about the first attempt', async () => {
  let posts = 0;
  const h = harness({ fetch: async (url, init) => {
    if (init.method !== 'POST') return response(200, { processed: false });
    if (++posts === 1) throw new Error('lost');
    return response(400, {});
  } });
  await h.instance.begin('m', fields); await h.instance.retry();
  assert.equal(h.instance.read().phase, 'uncertain');
});

test('already processed operation is recovered by identity without another POST, even outside recent history', async () => {
  let processed = false;
  const h = harness({ fetch: async (url, init) => {
    if (init.method === 'POST') { processed = true; throw new Error('response lost after acceptance'); }
    assert.match(url, /\/events\/id-1$/);
    return response(200, { processed });
  } });
  await h.instance.begin('m', fields); await h.instance.retry();
  assert.equal(h.instance.read(), null);
  assert.equal(h.requests.filter(r => r.method === 'POST').length, 1);
});

test('5xx and malformed successful replies are uncertain, never definitive rejection', async () => {
  for (const result of [response(503, {}), response(202, {}), response(202, { event_id: 'different' })]) {
    const h = harness({ fetch: async () => result });
    await h.instance.begin('m', fields); assert.equal(h.instance.read().phase, 'uncertain');
  }
});

test('storage unavailable means no POST; corrupt storage is never silently removed', async () => {
  const storage = memory(); storage.setItem = () => { throw new Error('quota'); };
  const h = harness({ storage });
  await assert.rejects(h.instance.begin('m', fields)); assert.equal(h.requests.length, 0);
  const broken = memory(); broken.setItem('live-jvg.pending-operation.v1', 'broken');
  const other = harness({ storage: broken });
  await assert.rejects(other.instance.begin('m', fields));
  assert.equal(broken.getItem(other.instance.key), 'broken');
});

test('two tabs cannot send or discard concurrently and a failed status lookup does not trigger a POST', async () => {
  const storage = memory(), coordinator = locks(); let release;
  const one = harness({ storage, locks: coordinator, fetch: (url, init) => new Promise(resolve => { release = () => resolve(response(202, JSON.parse(init.body))); }) });
  const two = harness({ storage, locks: coordinator });
  const sending = one.instance.begin('m', fields);
  await assert.rejects(two.instance.retry(), /Otra pestaña/);
  await assert.rejects(two.instance.discard(() => true), /Otra pestaña/);
  release(); await sending;
  const offline = harness({ storage, locks: coordinator, fetch: async () => { throw new Error('offline'); } });
  await assert.rejects(offline.instance.retry());
  assert.ok(offline.instance.read());
  assert.equal(offline.requests.filter(r => r.method === 'POST').length, 0);
});

test('forgetting an uncertain operation requires explicit confirmation and does not publish a replacement', async () => {
  const h = harness({ fetch: async () => { throw new Error('lost'); } });
  await h.instance.begin('m', fields);
  await h.instance.discard(() => false); assert.ok(h.instance.read());
  await h.instance.discard(warning => { assert.match(warning, /duplicar/); return true; });
  assert.equal(h.instance.read(), null); assert.equal(h.requests.length, 1); assert.equal(h.ids(), 1);
});
