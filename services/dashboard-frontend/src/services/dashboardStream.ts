import { Client, ReconnectionTimeMode, type StompConfig } from '@stomp/stompjs'
import { isMatchSnapshot, type MatchSnapshot } from '../types/match'

export interface Transport {
  activate(): void
  deactivate(): Promise<void>
  forceDisconnect(): void
  subscribe(destination: string, callback: (message: { body: string }) => void): { unsubscribe(): void }
}
export type TransportFactory = (config: StompConfig) => Transport
export const createTransport: TransportFactory = config => new Client(config)
export type ConnectionStatus = 'connecting' | 'connected' | 'reconnecting' | 'failed'
export interface WatchedMatch {
  snapshot: MatchSnapshot | null
  events: MatchSnapshot[]
  recovery: 'pending' | 'ready' | 'unavailable'
}
export interface StreamState {
  status: ConnectionStatus
  matches: Record<string, WatchedMatch>
  invalidMessages: number
  attempts: number
}

export const initialStreamState = (ids: string[]): StreamState => ({
  status: 'connecting', invalidMessages: 0, attempts: 0,
  matches: Object.fromEntries(ids.map(id => [id, { snapshot: null, events: [], recovery: 'pending' }])),
})

/** Recupera por conexión y controla revisiones por stream, sin recalcular reglas de negocio. */
export class SnapshotStore {
  private buffers = new Map<string, MatchSnapshot[]>()
  private seen = new Map<string, Set<string>>()
  constructor(private entries: Record<string, WatchedMatch>) {}

  begin() {
    this.buffers.clear()
    this.entries = Object.fromEntries(Object.entries(this.entries).map(([id, entry]) =>
      [id, { ...entry, recovery: 'pending' }]))
  }

  get matches() { return this.entries }

  live(id: string, snapshot: MatchSnapshot) {
    if (this.entries[id].recovery === 'pending') {
      this.buffers.set(id, [...(this.buffers.get(id) ?? []), snapshot].slice(-20))
    } else this.apply(id, snapshot)
  }

  recover(id: string, snapshot: MatchSnapshot | null) {
    const previous = this.entries[id]
    const buffer = this.buffers.get(id) ?? []
    const stream = snapshot?.stream_id ?? buffer.at(-1)?.stream_id
    if (!stream || previous.snapshot?.stream_id !== stream) {
      this.seen.delete(id)
      this.entries = { ...this.entries, [id]: { snapshot: null, events: [], recovery: 'ready' } }
    }
    if (snapshot) this.apply(id, snapshot)
    for (const update of buffer) if (update.stream_id === stream) this.apply(id, update)
    this.buffers.delete(id)
    this.entries = { ...this.entries, [id]: { ...this.entries[id],
      recovery: this.entries[id].snapshot ? 'ready' : 'unavailable' } }
  }

  private apply(id: string, snapshot: MatchSnapshot) {
    const previous = this.entries[id]
    if (previous.snapshot && previous.snapshot.stream_id !== snapshot.stream_id) return
    if (previous.snapshot && previous.snapshot.revision >= snapshot.revision) return
    const seen = this.seen.get(id) ?? new Set<string>()
    const duplicate = seen.has(snapshot.event_id)
    // Limita la caché visible del cliente; el historial persistente permanece en el backend.
    seen.add(snapshot.event_id)
    if (seen.size > 10000) seen.delete(seen.values().next().value!)
    this.seen.set(id, seen)
    this.entries = { ...this.entries, [id]: { ...previous, snapshot, recovery: 'ready',
      events: duplicate ? previous.events : [snapshot, ...previous.events].slice(0, 30) } }
  }
}

export function startDashboardStream(ids: string[], url: string, notify: (state: StreamState) => void,
  factory: TransportFactory = createTransport) {
  let state = initialStreamState(ids)
  const store = new SnapshotStore(state.matches)
  let stopped = false, connected = false, epoch = 0, failures = 0
  let recoveryTimer: ReturnType<typeof setTimeout> | undefined
  const emit = () => { if (!stopped) notify({ ...state, matches: store.matches }) }
  const invalid = () => { state = { ...state, invalidMessages: state.invalidMessages + 1 }; emit() }
  const clearRecovery = () => clearTimeout(recoveryTimer)
  const parse = (body: string): unknown => { try { return JSON.parse(body) } catch { return undefined } }
  const current = (token: number) => !stopped && connected && token === epoch
  const client = factory({
    brokerURL: url,
    reconnectDelay: 1000,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    maxReconnectDelay: 30000,
    connectionTimeout: 8000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    discardWebsocketOnCommFailure: true,
    debug: () => {},
    onConnect: () => {
      if (stopped) return
      connected = true
      const token = ++epoch
      store.begin()
      state = { ...state, status: 'connected', attempts: 0 }
      const pending = new Set(ids)
      clearRecovery()
      recoveryTimer = setTimeout(() => { if (pending.size && current(token)) client.forceDisconnect() }, 8000)
      for (const id of ids) {
        const segment = encodeURIComponent(id)
        client.subscribe(`/topic/matches/${segment}/score`, message => {
          if (!current(token)) return
          const data = parse(message.body)
          if (!isMatchSnapshot(data) || data.match_id !== id) { invalid(); return }
          store.live(id, data)
          emit()
        })
        client.subscribe(`/app/matches/${segment}/snapshot`, message => {
          if (!current(token) || !pending.has(id)) return
          const data = parse(message.body)
          if (!data || typeof data !== 'object') { invalid(); return }
          const reply = data as Record<string, unknown>
          if (reply.available === false && reply.snapshot === null) store.recover(id, null)
          else if (reply.available === true && isMatchSnapshot(reply.snapshot) && reply.snapshot.match_id === id)
            store.recover(id, reply.snapshot)
          else { invalid(); return }
          pending.delete(id)
          if (pending.size === 0) { clearRecovery(); failures = 0 }
          emit()
        })
      }
      emit()
    },
    onWebSocketClose: () => {
      connected = false
      epoch++
      clearRecovery()
      if (stopped) return
      failures++
      store.begin()
      state = { ...state, status: failures >= 8 ? 'failed' : 'reconnecting', attempts: failures }
      emit()
      if (failures >= 8) void client.deactivate()
    },
    onStompError: () => client.forceDisconnect(),
  })
  emit()
  client.activate()
  return () => {
    stopped = true
    connected = false
    epoch++
    clearRecovery()
    void client.deactivate()
  }
}
