const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');

// Executes the real scripts and button handlers with a minimal DOM and simulated HTTP.
// This is not a browser or broker integration test.
async function page({ enabled = true, pending = null, hold = false, catalogFails = 0, publicationStatus = 202 } = {}) {
  class Element {
    constructor() { this.value = ''; this.disabled = false; this.textContent = ''; this.children = []; this.classList = { toggle() {}, add() {}, remove() {} }; }
    replaceChildren() { this.children = []; this.value = ''; }
    add(option) { this.children.push(option); if (!this.value) this.value = option.value; }
    append(...children) { this.children.push(...children); }
  }
  const elements = new Map([...fs.readFileSync(path.join(root, 'index.html'), 'utf8').matchAll(/id="([^"]+)"/g)]
    .map(match => [match[1], new Element()]));
  elements.get('minute').value = '10';
  const storage = new Map(pending ? [['live-jvg.pending-operation.v1', JSON.stringify(pending)]] : []);
  const requests = []; let uuids = 0, held = false, acknowledge;
  const lifecycle = new Map();
  const state = { match_id: 'm', home_team_id: 'home', away_team_id: 'away', home_name: 'Home', away_name: 'Away',
    home_score: 0, away_score: 0, var_status: 'CLEAR', last_event_id: null, updated_at: null, events: [], active_goals: [] };
  const response = data => ({ ok: true, status: 200, json: async () => data });
  const context = vm.createContext({
    document: { getElementById: id => elements.get(id), createElement: () => new Element() },
    Option: function(text, value) { return { text, value }; },
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
    navigator: { locks: { async request(name, options, action) {
      if (held) return action(null); held = true;
      try { return await action({ name }); } finally { held = false; }
    } } },
    crypto: { randomUUID: () => `ui-${++uuids}` },
    window: { addEventListener(name, action) { lifecycle.set(name, action); }, confirm: () => false },
    setTimeout: (action, delay) => { const timer=setTimeout(action,delay);timer.unref();return timer; }, clearTimeout, AbortController, console,
    fetch: async (url, init = {}) => {
      requests.push({ url, ...init });
      if (init.method === 'POST') {
        assert.equal(JSON.parse(storage.get('live-jvg.pending-operation.v1')).body, init.body);
        const event = JSON.parse(init.body);
        if (publicationStatus !== 202) return { ok: false, status: publicationStatus, json: async () => ({
          code: publicationStatus === 500 ? 'EVENT_PREPARATION_FAILED' : 'PUBLICATION_UNCONFIRMED',
          error: 'private-exception-details', event: { ...event, match_id: 'm' },
        }) };
        const finish = () => {
          state.events = [{ ...event, home_score: 1, away_score: 0, updated_at: event.timestamp }];
          state.home_score = 1; state.last_event_id = event.event_id;
          return { ok: true, status: 202, json: async () => event };
        };
        return hold ? new Promise(resolve => { acknowledge = () => resolve(finish()); }) : finish();
      }
      if (url === '/api/simulator/config') return response({ write_enabled: enabled });
      if (url === '/api/matches') return catalogFails-->0?{ok:false,status:503,json:async()=>({})}:response([state]);
      if (/\/events\//.test(url)) return response({ processed: state.events.some(event => url.endsWith('/' + event.event_id)) });
      if (url === '/api/matches/m') return response(state);
      throw new Error('Unexpected URL ' + url);
    },
  });
  vm.runInContext(fs.readFileSync(path.join(root, 'operations.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(root, 'loading.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), context);
  for (let i = 0; i < 20 && elements.get('connection').textContent !== 'Conectado al servidor'; i++)
    await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  return { elements, requests, storage, uuids: () => uuids, acknowledge: () => acknowledge(), dispose: () => lifecycle.get('pagehide')() };
}

test('enabled local configuration lets the real goal button publish once and double-click is blocked', async t => {
  const ui = await page({ hold: true });
  t.after(ui.dispose);
  const button = ui.elements.get('goal-home');
  assert.equal(button.disabled, false);
  const first = button.onclick();
  await button.onclick(); // Handler must be safe even if called while the DOM button is disabled.
  assert.equal(button.disabled, true);
  assert.equal(ui.requests.filter(r => r.method === 'POST').length, 1);
  ui.acknowledge(); await first;
  assert.equal(ui.elements.get('home-score').textContent, 1);
  assert.equal(ui.storage.size, 0);
  assert.equal(ui.uuids(), 1);
});

test('disabled local configuration keeps real controls read-only and does not POST', async t => {
  const ui = await page({ enabled: false });
  t.after(ui.dispose);
  assert.equal(ui.elements.get('goal-home').disabled, true);
  await ui.elements.get('goal-home').onclick();
  assert.equal(ui.requests.filter(r => r.method === 'POST').length, 0);
});

test('page reload restores the full pending operation and does not generate or send another event', async t => {
  const pending = { version: 1, matchId: 'm', body: JSON.stringify({ event_id: 'saved-id', timestamp: '2026-11-15T00:00:00Z',
    event_type: 'GOAL', team_id: 'home', minute: 10, reference_event_id: null }), phase: 'sending', uncertain: false };
  const ui = await page({ pending });
  t.after(ui.dispose);
  assert.equal(ui.elements.get('goal-home').disabled, true);
  assert.equal(ui.elements.get('retry-operation').disabled, false);
  assert.match(ui.elements.get('pending-operation').textContent, /saved-id/);
  assert.equal(ui.requests.filter(r => r.method === 'POST').length, 0);
  assert.equal(ui.uuids(), 0);
  assert.equal(JSON.parse(ui.storage.get('live-jvg.pending-operation.v1')).body, pending.body);
});

test('catalog recovery retains the pending body and never publishes during recovery',async t=>{
 const pending={version:1,matchId:'m',body:JSON.stringify({event_id:'recover-id',timestamp:'2026-11-15T00:00:00Z'}),phase:'uncertain',uncertain:true};
 const ui=await page({pending,catalogFails:1});t.after(ui.dispose);
 assert.match(ui.elements.get('catalog-status').textContent,/No se pudieron/);
 const before=ui.storage.get('live-jvg.pending-operation.v1');
 await ui.elements.get('reload-catalog').onclick();
 assert.equal(ui.elements.get('connection').textContent,'Conectado al servidor');
 assert.equal(ui.storage.get('live-jvg.pending-operation.v1'),before);
 assert.equal(ui.uuids(),0);assert.equal(ui.requests.filter(r=>r.method==='POST').length,0);
});

for (const publicationStatus of [500, 503]) {
  test(`real button displays safe HTTP ${publicationStatus} feedback while retaining the original operation`, async t => {
    const ui = await page({ publicationStatus }); t.after(ui.dispose);
    await ui.elements.get('goal-home').onclick();
    const saved = JSON.parse(ui.storage.get('live-jvg.pending-operation.v1'));
    assert.equal(saved.phase, 'uncertain'); assert.equal(saved.uncertain, true);
    assert.equal(JSON.parse(saved.body).event_id, 'ui-1'); assert.equal(ui.uuids(), 1);
    assert.equal(ui.requests.filter(request => request.method === 'POST').length, 1);
    assert.match(ui.elements.get('feedback').textContent, publicationStatus === 500 ? /preparar/ : /no confirmada/);
    assert.doesNotMatch(ui.elements.get('feedback').textContent, /private-exception-details|Kafka confirmó/);
    assert.match(ui.elements.get('pending-operation').textContent, /ui-1/);
    assert.equal(ui.elements.get('goal-home').disabled, true);
    assert.equal(ui.elements.get('retry-operation').disabled, false);
  });
}
