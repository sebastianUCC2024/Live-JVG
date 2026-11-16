import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { initialStreamState, SnapshotStore, startDashboardStream, type StreamState } from './dashboardStream'
import { snapshot, stompHarness } from '../test/stompHarness'

const live = '/topic/matches/match-001/score'
const recovery = '/app/matches/match-001/snapshot'

describe('STOMP y recuperación', () => {
  let harness: ReturnType<typeof stompHarness>, state: StreamState, stop: () => void
  beforeEach(() => {
    vi.useFakeTimers()
    harness = stompHarness()
    stop = startDashboardStream(['match-001'], 'ws://localhost:8081/ws', value => { state = value }, harness.factory)
    harness.connect()
    harness.send(recovery, { available: false, snapshot: null })
  })
  afterEach(() => { stop(); vi.useRealTimers() })

  it('se suscribe primero al partido y luego a su snapshot real', () => {
    expect(harness.transport.subscribe.mock.calls.map(call => call[0])).toEqual([live, recovery])
    expect(state.status).toBe('connected')
    expect(state.matches['match-001'].recovery).toBe('unavailable')
  })
  it('muestra gol local y visitante usando los marcadores completos recibidos', () => {
    harness.send(live, snapshot({ revision: 2, event_id: 'home', event_type: 'GOAL', home_score: 1 }))
    expect(state.matches['match-001'].snapshot?.home_score).toBe(1)
    harness.send(live, snapshot({ revision: 3, event_id: 'away', event_type: 'GOAL', home_score: 1, away_score: 1 }))
    expect(state.matches['match-001'].snapshot?.away_score).toBe(1)
    expect(state.matches['match-001'].events).toHaveLength(2)
  })
  it('representa revisión VAR y anulación sin restar goles en el cliente', () => {
    harness.send(live, snapshot({ revision: 2, event_id: 'var', event_type: 'VAR_CHECK', home_score: 4, var_status: 'IN_PROGRESS' }))
    expect(state.matches['match-001'].snapshot?.var_status).toBe('IN_PROGRESS')
    harness.send(live, snapshot({ revision: 3, event_id: 'annulled', event_type: 'GOAL_ANNULLED',
      home_score: 2, reference_event_id: 'goal' }))
    expect(state.matches['match-001'].snapshot?.home_score).toBe(2)
    expect(state.matches['match-001'].snapshot?.var_status).toBe('CLEAR')
  })
  it('ignora duplicados y revisiones atrasadas, sin repetir eventos', () => {
    const update = snapshot({ revision: 4, event_id: 'goal', event_type: 'GOAL', home_score: 1 })
    harness.send(live, update); harness.send(live, update)
    harness.send(live, snapshot({ revision: 3, home_score: 9 }))
    harness.send(live, { ...update, revision: 5 })
    expect(state.matches['match-001'].snapshot?.home_score).toBe(1)
    expect(state.matches['match-001'].events).toHaveLength(1)
  })
  it.each(['malformed', '{}', 'null', '{"revision":-1}'])('rechaza el mensaje %s conservando el estado válido', body => {
    harness.send(live, snapshot())
    harness.raw(live, body)
    expect(state.invalidMessages).toBe(1)
    expect(state.matches['match-001'].snapshot).toEqual(snapshot())
  })
  it('rechaza un partido distinto y una respuesta de recuperación inválida', () => {
    harness.send(live, snapshot({ match_id: 'other-match' }))
    harness.close(); harness.connect()
    harness.send(recovery, { available: true, snapshot: snapshot({ match_id: 'other-match' }) })
    expect(state.invalidMessages).toBe(2)
    expect(state.matches['match-001'].snapshot).toBeNull()
  })
  it('desconecta, renueva suscripciones y recupera el estado perdido', () => {
    harness.send(live, snapshot({ home_score: 1 }))
    const oldHandler = harness.handlers.get(live)!
    harness.close()
    expect(state.status).toBe('reconnecting')
    expect(state.matches['match-001'].recovery).toBe('pending')
    harness.connect()
    oldHandler({ body: JSON.stringify(snapshot({ revision: 90, home_score: 90 })) })
    harness.send(recovery, { available: true, snapshot: snapshot({ revision: 2, event_id: 'new', home_score: 2 }) })
    expect(state.matches['match-001'].snapshot?.home_score).toBe(2)
    expect(harness.transport.subscribe).toHaveBeenCalledTimes(4)
  })
  it('aplica el buffer posterior al snapshot sin regresar el marcador', () => {
    harness.close(); harness.connect()
    harness.send(live, snapshot({ revision: 4, event_id: 'latest', home_score: 3 }))
    harness.send(recovery, { available: true, snapshot: snapshot({ revision: 2, home_score: 1 }) })
    expect(state.matches['match-001'].snapshot?.home_score).toBe(3)
  })
  it('reinicia el estado para un stream_id nuevo y limpia el historial anterior', () => {
    harness.send(live, snapshot({ revision: 100, home_score: 4 }))
    harness.close(); harness.connect()
    harness.send(recovery, { available: true, snapshot: snapshot({ stream_id: 'stream-2', revision: 1, home_score: 0 }) })
    expect(state.matches['match-001'].snapshot?.stream_id).toBe('stream-2')
    expect(state.matches['match-001'].snapshot?.home_score).toBe(0)
    expect(state.matches['match-001'].events).toHaveLength(1)
  })
  it('no inventa 0–0 cuando el servidor no tiene estado tras reinicio', () => {
    harness.send(live, snapshot({ home_score: 4 }))
    harness.close(); harness.connect()
    harness.send(recovery, { available: false, snapshot: null })
    expect(state.matches['match-001'].snapshot).toBeNull()
  })
  it('conserva época al reconectar y acepta revisión reiniciada solo después de rotación explícita', () => {
    const original = 'durable-550e8400-e29b-41d4-a716-446655440000-match-001'
    const rotated = 'durable-550e8400-e29b-41d4-a716-446655440001-match-001'
    harness.send(live, snapshot({ stream_id: original, revision: 9000, event_id: 'old', home_score: 7 }))
    harness.close(); harness.connect()
    harness.send(recovery, { available: true, snapshot: snapshot({ stream_id: original, revision: 1, home_score: 0 }) })
    expect(state.matches['match-001'].snapshot?.revision).toBe(9000)
    const oldHandler = harness.handlers.get(live)!
    harness.close(); harness.connect()
    harness.send(live, snapshot({ stream_id: original, revision: 9001, home_score: 9 }))
    harness.send(live, snapshot({ stream_id: rotated, revision: 2, event_id: 'after', home_score: 1 }))
    harness.send(recovery, { available: true, snapshot: snapshot({ stream_id: rotated, revision: 1, home_score: 0 }) })
    oldHandler({ body: JSON.stringify(snapshot({ stream_id: original, revision: 9999, home_score: 99 })) })
    expect(state.matches['match-001'].snapshot?.stream_id).toBe(rotated)
    expect(state.matches['match-001'].snapshot?.revision).toBe(2)
    expect(state.matches['match-001'].snapshot?.home_score).toBe(1)
    expect(state.matches['match-001'].events).toHaveLength(2)
  })
  it('limita los fallos consecutivos de conexión a ocho', () => {
    for (let i = 0; i < 8; i++) harness.close()
    expect(state.status).toBe('failed')
    expect(state.attempts).toBe(8)
    expect(harness.transport.deactivate).toHaveBeenCalledOnce()
  })
  it('fuerza reconexión si el snapshot no responde dentro del plazo', () => {
    harness.close(); harness.connect()
    vi.advanceTimersByTime(8000)
    expect(harness.transport.forceDisconnect).toHaveBeenCalledOnce()
  })
  it('la limpieza detiene transporte y descarta callbacks tardíos', () => {
    const before = state
    stop()
    harness.close(); harness.send(live, snapshot())
    expect(state).toBe(before)
    expect(harness.transport.deactivate).toHaveBeenCalled()
  })
})

describe('Aislamiento de partidos', () => {
  it('conserva snapshots independientes y como máximo treinta eventos visibles', () => {
    const store = new SnapshotStore(initialStreamState(['match-001', 'match-002']).matches)
    store.begin(); store.recover('match-001', null); store.recover('match-002', null)
    for (let revision = 1; revision <= 40; revision++) store.live('match-001', snapshot({ revision, event_id: `e${revision}` }))
    store.live('match-002', snapshot({ match_id: 'match-002', home_score: 7 }))
    expect(store.matches['match-001'].events).toHaveLength(30)
    expect(store.matches['match-001'].snapshot?.home_score).toBe(0)
    expect(store.matches['match-002'].snapshot?.home_score).toBe(7)
  })
})
