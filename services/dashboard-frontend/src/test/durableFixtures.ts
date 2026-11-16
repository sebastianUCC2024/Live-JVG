import type { CatalogMatch, MatchDetail, ScoreEvent } from '../services/dashboardApi'
import { snapshot } from './stompHarness'

export const catalogMatch = (id = 'match-001'): CatalogMatch => ({
  match_id: id, home_team_id: 'home', away_team_id: 'away', home_name: 'Atlético Real', away_name: 'Unión Real',
  home_score: 1, away_score: 0, var_status: 'CLEAR', last_event_id: 'goal-1', updated_at: '2026-11-14T20:42:00Z',
})
export const scoreEvent = (changes: Partial<ScoreEvent> = {}): ScoreEvent => ({
  match_id: 'match-001', event_id: 'goal-1', event_type: 'GOAL', home_score: 1, away_score: 0,
  var_status: 'CLEAR', updated_at: '2026-11-14T20:42:00Z', reference_event_id: null, ...changes,
})
export const matchDetail = (id = 'match-001'): MatchDetail => ({ ...catalogMatch(id),
  events: [scoreEvent({ match_id: id }), scoreEvent({ match_id: id, event_id: 'start', event_type: 'MATCH_CONTINUES', home_score: 0 })],
  active_goals: [scoreEvent({ match_id: id })],
})
export const restSnapshot = (id = 'match-001') => snapshot({ match_id: id, event_id: 'goal-1', event_type: 'GOAL', home_score: 1, revision: 2 })
export const jsonResponse = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
