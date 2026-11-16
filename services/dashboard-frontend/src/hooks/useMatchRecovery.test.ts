import { describe, expect, it } from 'vitest'
import { mergeRecovery } from './useMatchRecovery'
import { matchDetail, restSnapshot, scoreEvent } from '../test/durableFixtures'
import { snapshot } from '../test/stompHarness'

const rest = () => ({ detail: matchDetail(), snapshot: restSnapshot() })
describe('Coordinación REST / STOMP', () => {
  it('muestra marcador e historial REST antes de conectar STOMP', () => {
    expect(mergeRecovery(undefined, rest())).toEqual({ snapshot: restSnapshot(), events: matchDetail().events })
  })
  it('una respuesta REST tardía no retrocede una revisión nueva', () => {
    const live = snapshot({ revision: 3, event_id: 'away', away_score: 1 })
    const merged = mergeRecovery({ snapshot: live, events: [live, restSnapshot()], recovery: 'ready' }, rest())
    expect(merged.snapshot).toEqual(live)
    expect(merged.events.map(event => event.event_id)).toEqual(['away', 'goal-1', 'start'])
  })
  it('deduplica eventos REST y STOMP conservando orden de secuencia', () => {
    const duplicate = restSnapshot()
    expect(mergeRecovery({ snapshot: duplicate, events: [duplicate, duplicate], recovery: 'ready' }, rest()).events)
      .toEqual(matchDetail().events)
  })
  it('descarta historial y snapshot de otra época persistente', () => {
    const live = snapshot({ stream_id: 'new-epoch', revision: 1 })
    expect(mergeRecovery({ snapshot: live, events: [live], recovery: 'ready' }, rest())).toEqual({ snapshot: live, events: [live] })
  })
  it('una recuperación STOMP vacía descarta REST antiguo', () => {
    expect(mergeRecovery({ snapshot: null, events: [], recovery: 'unavailable' }, rest())).toEqual({ snapshot: null, events: [] })
  })
  it('limita a 30 y conserva orden aunque timestamps no sean cronológicos', () => {
    const recovery = rest()
    recovery.detail.events = Array.from({ length: 30 }, (_, i) => scoreEvent({ event_id: `past-${i}` }))
    const live = snapshot({ revision: 3, event_id: 'latest', updated_at: '2026-11-13T00:00:00Z' })
    const events = mergeRecovery({ snapshot: live, events: [live], recovery: 'ready' }, recovery).events
    expect(events).toHaveLength(30)
    expect(events[0].event_id).toBe('latest')
    expect(events[29].event_id).toBe('past-28')
  })
})
