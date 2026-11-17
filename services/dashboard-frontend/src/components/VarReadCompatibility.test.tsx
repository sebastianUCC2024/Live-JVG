import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { VarEvent } from './VarEvent'
import { isMatchSnapshot, type EventType, type VarStatus } from '../types/match'
import { isLiveAlert } from '../types/alert'
import { snapshot } from '../test/stompHarness'
import { liveAlert } from '../test/alertFixtures'

// Synthetic read-only examples from the unapproved P2 proposal, not a review engine.
it.each<[EventType, string | null, VarStatus]>([
  ['VAR_CHECK', null, 'IN_PROGRESS'],
  ['VAR_CHECK', 'goal-1', 'IN_PROGRESS'],
  ['MATCH_CONTINUES', 'review-1', 'IN_PROGRESS'],
  ['GOAL_ANNULLED', 'goal-1', 'IN_PROGRESS'],
  ['MATCH_CONTINUES', 'review-2', 'CLEAR'],
])('reads %s with existing references and authoritative %s status', (event_type, reference_event_id, var_status) => {
  expect(isMatchSnapshot(snapshot({ event_type, reference_event_id, var_status }))).toBe(true)
})
it.each<EventType>(['GOAL_ANNULLED', 'MATCH_CONTINUES'])('keeps the review indicator for a partial %s update', event_type => {
  render(<VarEvent snapshot={snapshot({ event_type, reference_event_id: 'source-1', var_status: 'IN_PROGRESS' })} />)
  expect(screen.getByText('Revisión VAR en curso')).toBeInTheDocument()
  expect(screen.queryByText('Sin revisión activa')).not.toBeInTheDocument()
})
it('reads an annulment alert with IN_PROGRESS without deriving any closure', () => {
  expect(isLiveAlert(liveAlert(1, { alert_type: 'GOAL_ANNULLED', reference_event_id: 'goal-1', var_status: 'IN_PROGRESS' }), 'match-001')).toBe(true)
})
