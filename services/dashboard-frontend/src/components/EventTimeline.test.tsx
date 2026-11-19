import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { EventTimeline, type TimelineEvent } from './EventTimeline'

const events: TimelineEvent[] = [
  { event_id: 'goal', event_type: 'GOAL', home_score: 1, away_score: 0, updated_at: '2026-11-17T18:02:00Z', reference_event_id: null },
  { event_id: 'var', event_type: 'VAR_CHECK', home_score: 1, away_score: 0, updated_at: '2026-11-17T18:01:00Z', reference_event_id: 'goal' },
  { event_id: 'annul', event_type: 'GOAL_ANNULLED', home_score: 0, away_score: 0, updated_at: '2026-11-17T18:03:00Z', reference_event_id: 'goal' },
]
describe('Timeline de presentación', () => {
  it('conserva el orden recibido aunque los timestamps difieran y no recalcula el marcador', () => {
    render(<EventTimeline events={events} />)
    const rows = within(screen.getByRole('list', { name: 'Línea de tiempo del partido' })).getAllByRole('listitem')
    expect(rows.map(row => row.querySelector('strong')?.textContent)).toEqual(['Gol', 'Revisión VAR', 'Gol anulado'])
    expect(within(rows[2]).getByLabelText('Marcador tras el evento: 0 a 0')).toBeInTheDocument()
    expect(rows[2]).toHaveTextContent('Referencia: goal')
    expect(rows[0].querySelector('time')).toHaveAttribute('datetime', events[0].updated_at)
  })
  it('no crea eventos en una lista vacía', () => {
    render(<EventTimeline events={[]} />)
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument()
  })
  it('identifica expresamente la actualización de demostración', () => {
    render(<EventTimeline events={[events[0]]} simulated />)
    expect(screen.getByRole('list', { name: 'Última actualización simulada' })).toBeInTheDocument()
    expect(screen.getByText('Bogotá · muestra')).toBeInTheDocument()
  })
})
