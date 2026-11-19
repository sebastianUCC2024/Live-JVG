import type { DemoMatch, MatchLoader } from '../types/match'

/** Datos ficticios de demostración; no realizan conexiones HTTP ni WebSocket. */
export const demoMatches: DemoMatch[] = [
  {
    snapshot: { stream_id: 'demo-session', revision: 12, match_id: 'demo-001', home_score: 2, away_score: 1,
      event_id: 'demo-var-1', event_type: 'VAR_CHECK', updated_at: '2026-11-14T20:42:00.079218900Z',
      var_status: 'IN_PROGRESS', reference_event_id: 'demo-goal-1' },
    home: { name: 'Atlético Capital', initials: 'AC', color: 'wine' },
    away: { name: 'Deportivo Norte', initials: 'DN', color: 'blue' }, competition: 'Liga Nacional', minute: 67,
  },
  {
    snapshot: { stream_id: 'demo-session', revision: 11, match_id: 'demo-002', home_score: 0, away_score: 0,
      event_id: 'demo-continue-2', event_type: 'MATCH_CONTINUES', updated_at: '2026-11-14T20:40:00Z',
      var_status: 'CLEAR', reference_event_id: null },
    home: { name: 'Unión del Valle', initials: 'UV', color: 'green' },
    away: { name: 'Sporting Costa', initials: 'SC', color: 'orange' }, competition: 'Liga Nacional', minute: 34,
  },
  {
    snapshot: { stream_id: 'demo-session', revision: 10, match_id: 'demo-003', home_score: 1, away_score: 1,
      event_id: 'demo-annulment-3', event_type: 'GOAL_ANNULLED', updated_at: '2026-11-14T20:38:00Z',
      var_status: 'CLEAR', reference_event_id: 'demo-goal-3' },
    home: { name: 'Real Andino', initials: 'RA', color: 'purple' },
    away: { name: 'Club del Pacífico', initials: 'CP', color: 'teal' }, competition: 'Copa Regional', minute: 82,
  },
  {
    snapshot: { stream_id: 'demo-session', revision: 9, match_id: 'demo-004', home_score: 3, away_score: 0,
      event_id: 'demo-goal-4', event_type: 'GOAL', updated_at: '2026-11-14T20:35:00Z',
      var_status: 'CLEAR', reference_event_id: null },
    home: { name: 'Deportivo Central', initials: 'DC', color: 'blue' },
    away: { name: 'Estrella del Sur', initials: 'ES', color: 'wine' }, competition: 'Copa Regional', minute: 56,
  },
]

export const loadDemoMatches: MatchLoader = (scenario, signal) => new Promise((resolve, reject) => {
  const abort = () => {
    clearTimeout(timer)
    reject(new DOMException('Carga cancelada', 'AbortError'))
  }
  const timer = scenario === 'loading' ? undefined : setTimeout(() => {
    signal.removeEventListener('abort', abort)
    if (scenario === 'error') reject(new Error('No se pudo cargar el escenario de demostración.'))
    else resolve(scenario === 'empty' ? [] : structuredClone(demoMatches))
  }, 250)
  if (signal.aborted) abort()
  else signal.addEventListener('abort', abort, { once: true })
})
