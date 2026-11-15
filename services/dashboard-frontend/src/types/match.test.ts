import { describe, expect, it } from 'vitest'
import { demoMatches } from '../services/demoMatches'
import { isMatchSnapshot, type SnapshotReply } from './match'

describe('DTO real del dashboard-backend', () => {
  it('acepta fixtures snake_case, nanosegundos y referencias VAR', () => {
    expect(demoMatches.every(match => isMatchSnapshot(match.snapshot))).toBe(true)
    const reply: SnapshotReply = { available: false, snapshot: null }
    expect(reply.snapshot).toBeNull()
  })
  it.each([
    { home_score: -1 }, { away_score: 1.2 }, { revision: 0 }, { revision: Number.MAX_SAFE_INTEGER + 1 },
    { match_id: '' }, { stream_id: null }, { event_type: 'PENALTY' }, { var_status: 'CHECK' },
    { updated_at: 123 }, { updated_at: 'invalid' }, { reference_event_id: '' },
    { event_type: 'GOAL_ANNULLED', reference_event_id: null },
  ])('rechaza un snapshot inválido: %o', changed => {
    expect(isMatchSnapshot({ ...demoMatches[0].snapshot, ...changed })).toBe(false)
  })
})
