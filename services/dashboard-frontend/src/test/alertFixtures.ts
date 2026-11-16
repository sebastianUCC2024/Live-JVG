import type { LiveAlert, AlertPage } from '../types/alert'

/** Local fixtures copied from AlertController's shape; never represent live integration evidence. */
export const liveAlert = (index = 1, changes: Partial<LiveAlert> = {}): LiveAlert => ({
  contract_version: '1.0', alert_id: `a27801fc-e484-45d4-a17e-${String(index).padStart(12, '0')}`,
  match_id: 'match-001', alert_type: 'GOAL', source_event_id: `event-${index}`,
  source_updated_at: '2026-11-16T15:30:00.123456789Z', created_at: '2026-11-16T15:30:01Z',
  reference_event_id: null, home_score: 1, away_score: 0, var_status: 'CLEAR',
  content: { locale: 'es', title: 'Gol', message: 'Marcador confirmado: 1–0.' }, ...changes,
})
export const alertPage = (items: LiveAlert[] = [], cursor = 'cursor-1', more = false): AlertPage => ({ items, next_cursor: cursor, has_more: more })
