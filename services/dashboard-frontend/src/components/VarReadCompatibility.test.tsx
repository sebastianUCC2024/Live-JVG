import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { VarEvent } from './VarEvent'
import { isMatchSnapshot, type EventType, type VarStatus } from '../types/match'
import { isLiveAlert } from '../types/alert'
import { snapshot, stompHarness } from '../test/stompHarness'
import { liveAlert } from '../test/alertFixtures'
import { initialStreamState, startDashboardStream } from '../services/dashboardStream'

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

it('reads synthetic concurrent-review updates without calculating scores or global closure', () => {
  // Proposed read-only output examples: the test does not run or approve P2 rules.
  const examples = [
    snapshot({ revision: 1, event_id: 'goal-1', event_type: 'GOAL', home_score: 1 }),
    snapshot({ revision: 2, event_id: 'goal-2', event_type: 'GOAL', home_score: 2 }),
    snapshot({ revision: 3, event_id: 'review-1', event_type: 'VAR_CHECK', reference_event_id: 'goal-1', home_score: 2, var_status: 'IN_PROGRESS' }),
    snapshot({ revision: 4, event_id: 'review-2', event_type: 'VAR_CHECK', reference_event_id: 'goal-2', home_score: 2, var_status: 'IN_PROGRESS' }),
    snapshot({ revision: 5, event_id: 'goal-3', event_type: 'GOAL', home_score: 3, var_status: 'IN_PROGRESS' }),
    snapshot({ revision: 6, event_id: 'annulment-1', event_type: 'GOAL_ANNULLED', reference_event_id: 'goal-1', home_score: 2, var_status: 'IN_PROGRESS' }),
    snapshot({ revision: 7, event_id: 'closure-2', event_type: 'MATCH_CONTINUES', reference_event_id: 'review-2', home_score: 2, var_status: 'CLEAR' }),
  ]
  const harness = stompHarness()
  let state = initialStreamState(['match-001'])
  const stop = startDashboardStream(['match-001'], 'ws://fixture.invalid/ws', value => { state = value }, harness.factory)
  const view = render(<VarEvent snapshot={examples[0]} />)
  try {
    harness.connect()
    harness.send('/app/matches/match-001/snapshot', { available: false, snapshot: null })
    for (const example of examples) {
      harness.send('/topic/matches/match-001/score', example)
      const received = state.matches['match-001'].snapshot!
      expect(received).toEqual(example)
      view.rerender(<VarEvent snapshot={received} />)
      expect(screen.getByText(example.var_status === 'IN_PROGRESS' ? 'Revisión VAR en curso' : 'Sin revisión activa')).toBeInTheDocument()
      if (example.revision === 6) {
        harness.send('/topic/matches/match-001/score', example)
        harness.send('/topic/matches/match-001/score', examples[2])
        expect(state.matches['match-001'].snapshot).toEqual(example)
        expect(state.matches['match-001'].events).toHaveLength(6)
        expect(screen.queryByText('Sin revisión activa')).not.toBeInTheDocument()
      }
    }
    expect(state.invalidMessages).toBe(0)
    expect(state.matches['match-001'].events.map(event => event.event_id)).toEqual(examples.map(event => event.event_id).reverse())
  } finally { stop(); view.unmount() }
})

it('recovers a pending aggregate review after reconnection and ignores callbacks from the previous connection', () => {
  const original = snapshot({ revision: 8, event_id: 'annulment-1', event_type: 'GOAL_ANNULLED',
    reference_event_id: 'goal-1', home_score: 1, var_status: 'IN_PROGRESS' })
  const recovered = snapshot({ revision: 9, event_id: 'partial-closure', event_type: 'MATCH_CONTINUES',
    reference_event_id: 'review-2', home_score: 1, var_status: 'IN_PROGRESS' })
  const harness = stompHarness()
  let state = initialStreamState(['match-001'])
  const stop = startDashboardStream(['match-001'], 'ws://fixture.invalid/ws', value => { state = value }, harness.factory)
  try {
    harness.connect(); harness.send('/app/matches/match-001/snapshot', { available: true, snapshot: original })
    const old = harness.handlers.get('/topic/matches/match-001/score')!
    harness.close(); harness.connect()
    harness.send('/app/matches/match-001/snapshot', { available: true, snapshot: recovered })
    old({ body: JSON.stringify(snapshot({ revision: 99, event_id: 'obsolete-clear', var_status: 'CLEAR' })) })
    expect(state.matches['match-001'].snapshot).toEqual(recovered)
    const view = render(<VarEvent snapshot={state.matches['match-001'].snapshot!} />)
    expect(screen.getByText('Revisión VAR en curso')).toBeInTheDocument()
    expect(screen.queryByText('Sin revisión activa')).not.toBeInTheDocument()
    view.unmount()
  } finally { stop() }
})
