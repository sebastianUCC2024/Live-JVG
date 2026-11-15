'use strict';

// Persist the wire body, not a recipe that would create a new identity on retry.
class PendingOperations {
  constructor({ storage, fetch: transport, uuid, now, locks, notify = () => {}, timeoutMs = 10000 }) {
    Object.assign(this, { storage, transport, uuid, now, locks, notify, timeoutMs });
    this.key = 'live-jvg.pending-operation.v1';
    this.inFlight = false;
  }

  read() {
    const raw = this.storage.getItem(this.key);
    if (raw === null) return null;
    const operation = JSON.parse(raw);
    const body = JSON.parse(operation.body);
    if (operation.version !== 1 || typeof operation.matchId !== 'string' || !operation.matchId
        || typeof body.event_id !== 'string' || !body.event_id || typeof body.timestamp !== 'string'
        || !['prepared', 'sending', 'accepted', 'uncertain', 'rejected'].includes(operation.phase)) {
      throw new Error('La operación guardada no es legible. No se enviará ni eliminará automáticamente.');
    }
    if (operation.phase === 'sending') {
      operation.phase = 'uncertain';
      operation.uncertain = true;
    }
    return operation;
  }

  save(operation) {
    this.storage.setItem(this.key, JSON.stringify(operation));
    this.notify();
  }

  async exclusive(action) {
    if (this.inFlight) return false;
    if (!this.locks) throw new Error('Se requieren Web Locks en un navegador local compatible para publicar de forma coordinada.');
    this.inFlight = true;
    try {
      return await this.locks.request(this.key, { ifAvailable: true }, async lock => {
        if (!lock) throw new Error('Otra pestaña está gestionando la operación. Espera antes de reintentar.');
        return action();
      });
    } finally {
      this.inFlight = false;
      this.notify();
    }
  }

  async begin(matchId, fields) {
    return this.exclusive(async () => {
      if (this.read()) return false;
      const body = { ...fields, event_id: this.uuid(), timestamp: this.now() };
      const operation = { version: 1, matchId, body: JSON.stringify(body), phase: 'prepared', uncertain: false };
      this.save(operation); // Failure to persist means no POST is attempted.
      return this.send(operation);
    });
  }

  async request(url, options) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await this.transport(url, { ...options, signal: controller.signal });
      let data = {};
      try { data = await response.json(); }
      catch (failure) { if (controller.signal.aborted) throw failure; }
      return { ok: response.ok, status: response.status, data };
    }
    finally { clearTimeout(timer); }
  }

  async send(operation) {
    try {
      this.save({ ...operation, phase: 'sending' });
      const response = await this.request(`/api/matches/${encodeURIComponent(operation.matchId)}/events`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: operation.body,
      });
      const result = response.data;
      if (response.ok && result.event_id === JSON.parse(operation.body).event_id) {
        this.save({ ...operation, phase: 'accepted', uncertain: true,
          message: 'Kafka confirmó el envío. Esperando su procesamiento en el marcador.' });
      } else {
        const definite = !operation.uncertain && [400, 401, 403, 404, 405, 415, 422].includes(response.status);
        this.save({ ...operation, phase: definite ? 'rejected' : 'uncertain', uncertain: !definite,
          message: definite ? 'Solicitud rechazada. Revisa el evento antes de iniciar otra operación.'
            : 'Resultado incierto. Consulta o reintenta la misma operación; no generes otro evento.' });
      }
    } catch {
      this.save({ ...operation, phase: 'uncertain', uncertain: true,
        message: 'No se pudo confirmar la respuesta. El evento puede haberse publicado; conserva esta operación.' });
    }
    return true;
  }

  async checkProcessed(operation) {
    const id = JSON.parse(operation.body).event_id;
    const response = await this.request(`/api/matches/${encodeURIComponent(operation.matchId)}/events/${encodeURIComponent(id)}`, {});
    if (!response.ok) throw new Error('No se pudo comprobar el procesamiento. Se conserva la operación.');
    const result = response.data;
    if (typeof result.processed !== 'boolean') throw new Error('Respuesta de consulta inválida. Se conserva la operación.');
    if (!result.processed) return false;
    this.storage.removeItem(this.key);
    this.notify('Evento procesado y recibido en el marcador.');
    return true;
  }

  async retry() {
    return this.exclusive(async () => {
      const operation = this.read();
      if (!operation || operation.phase === 'rejected') return false;
      // A failed lookup is not permission to publish another event.
      if (await this.checkProcessed(operation)) return true;
      return this.send(operation);
    });
  }

  async reconcile() {
    if (!this.read() || this.inFlight) return false;
    return this.exclusive(async () => {
      const operation = this.read();
      return operation ? this.checkProcessed(operation) : false;
    });
  }

  async discard(confirm) {
    return this.exclusive(async () => {
      const warning = 'Olvidar la operación NO cancela el evento. Puede estar publicado o procesado. '
        + 'Crear otro podría duplicar la jugada. ¿Quieres olvidarla de todos modos?';
      if (!confirm(warning)) return false;
      this.storage.removeItem(this.key);
      this.notify('Operación olvidada por decisión explícita. No se creó ningún evento nuevo.');
      return true;
    });
  }
}

if (typeof module !== 'undefined' && module.exports) module.exports = { PendingOperations };
else globalThis.PendingOperations = PendingOperations;
