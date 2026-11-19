'use strict';

// GET orchestration only: no operation creation, publication or deletion.
class DashboardLoading {
  constructor({ fetch: transport, onCatalog, onCatalogState, onSelection, onMatch, onMatchError,
      schedule = setTimeout, cancel = clearTimeout, timeoutMs = 8000, pollMs = 1000 }) {
    Object.assign(this, { transport, onCatalog, onCatalogState, onSelection, onMatch, onMatchError,
      schedule, cancel, timeoutMs, pollMs });
    this.running = false;
    this.catalogEpoch = 0; this.matchEpoch = 0;
    this.catalogFailures = 0; this.matchFailures = 0;
    this.currentId = null;
  }
  delay(failures) { return Math.min(30000, 1000 * 2 ** Math.min(failures - 1, 5)); }
  async request(url, controller) {
    const timer = this.schedule(() => controller.abort(), this.timeoutMs);
    try {
      const response = await this.transport(url, { signal: controller.signal });
      if (!response.ok) throw new Error('No se pudo obtener la información del servidor.');
      return await response.json();
    } finally { this.cancel(timer); }
  }
  start() {
    if (this.running) return;
    this.running = true;
    return this.loadCatalog();
  }
  stop() {
    this.running = false;
    this.catalogEpoch++; this.matchEpoch++;
    this.cancel(this.catalogTimer); this.cancel(this.matchTimer);
    this.catalogController?.abort(); this.matchController?.abort();
  }
  async loadCatalog() {
    if (!this.running) return;
    const epoch = ++this.catalogEpoch;
    this.cancel(this.catalogTimer); this.catalogController?.abort();
    const controller = this.catalogController = new AbortController();
    this.onCatalogState('loading', 0);
    try {
      const matches = await this.request('/api/matches', controller);
      if (!this.running || epoch !== this.catalogEpoch) return;
      if (!Array.isArray(matches) || matches.some(match => typeof match?.match_id !== 'string'))
        throw new Error('Catálogo inválido.');
      this.catalogFailures = 0;
      this.onCatalogState(matches.length ? 'ready' : 'empty', 0);
      const selected = this.onCatalog(matches);
      await this.select(selected);
      if (!matches.length && this.running && epoch === this.catalogEpoch)
        this.catalogTimer = this.schedule(() => this.loadCatalog(), 5000);
    } catch (error) {
      if (!this.running || epoch !== this.catalogEpoch) return;
      const delay = this.delay(++this.catalogFailures);
      this.onCatalogState('error', delay);
      this.catalogTimer = this.schedule(() => this.loadCatalog(), delay);
    }
  }
  async select(id) {
    this.cancel(this.matchTimer); this.matchController?.abort();
    ++this.matchEpoch;
    this.currentId = id || null;
    this.matchFailures = 0;
    this.onSelection(this.currentId);
    if (this.running && this.currentId) return this.loadMatch();
  }
  refresh() { return this.running && this.currentId ? this.select(this.currentId) : Promise.resolve(); }
  async loadMatch() {
    if (!this.running || !this.currentId) return;
    const epoch = ++this.matchEpoch, id = this.currentId;
    const controller = this.matchController = new AbortController();
    let delay = this.pollMs;
    try {
      const match = await this.request('/api/matches/' + encodeURIComponent(id), controller);
      if (!this.running || epoch !== this.matchEpoch) return;
      if (match?.match_id !== id) throw new Error('El marcador no corresponde al partido seleccionado.');
      this.matchFailures = 0;
      await this.onMatch(match);
    } catch (error) {
      if (!this.running || epoch !== this.matchEpoch) return;
      delay = this.delay(++this.matchFailures);
      this.onMatchError(error, delay);
    } finally {
      if (this.running && epoch === this.matchEpoch)
        this.matchTimer = this.schedule(() => this.loadMatch(), delay);
    }
  }
}
if (typeof module !== 'undefined' && module.exports) module.exports = { DashboardLoading };
else globalThis.DashboardLoading = DashboardLoading;
