import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { LiveDashboardPage } from './LiveDashboardPage'
import { snapshot, stompHarness } from '../test/stompHarness'

describe('Dashboard conectado', () => {
  it('muestra marcador, VAR, eventos únicos y estado de conexión', async () => {
    const harness = stompHarness()
    const user = userEvent.setup()
    const view = render(<LiveDashboardPage factory={harness.factory} url="ws://localhost:8081/ws" />)
    expect(screen.getByText('Esperando marcador')).toBeInTheDocument()
    act(() => { harness.connect(); harness.send('/app/matches/match-001/snapshot', { available: true, snapshot: snapshot({ home_score: 1, event_type: 'GOAL' }) }) })
    expect(screen.getByText('Conectado')).toBeInTheDocument()
    expect(screen.getByLabelText('Local 1, Visitante 0')).toBeInTheDocument()
    expect(screen.getByText('El mensaje no incluye nombres de equipos.')).toBeInTheDocument()
    act(() => harness.send('/topic/matches/match-001/score', snapshot({ revision: 2, event_id: 'var', event_type: 'VAR_CHECK', var_status: 'IN_PROGRESS', home_score: 1 })))
    expect(screen.getByText('Revisión VAR en curso')).toBeInTheDocument()
    const annulled = snapshot({ revision: 3, event_id: 'annulled', event_type: 'GOAL_ANNULLED', reference_event_id: 'goal' })
    act(() => { harness.send('/topic/matches/match-001/score', annulled); harness.send('/topic/matches/match-001/score', annulled) })
    expect(screen.getByLabelText('Local 0, Visitante 0')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Ver detalle/ }))
    expect(within(screen.getByRole('region', { name: 'Eventos deportivos' })).getAllByRole('listitem')).toHaveLength(3)
    act(() => harness.close())
    expect(screen.getByText('Reconectando')).toBeInTheDocument()
    expect(screen.getByText('Sin actualizar')).toBeInTheDocument()
    view.unmount()
    expect(harness.transport.deactivate).toHaveBeenCalled()
  })
  it('permite cambiar los partidos y rechaza identificadores de suscripción wildcard', async () => {
    const harness = stompHarness(), user = userEvent.setup()
    render(<LiveDashboardPage factory={harness.factory} />)
    const input = screen.getByLabelText('Partidos a seguir · IDs separados por comas')
    await user.clear(input); await user.type(input, '*')
    await user.click(screen.getByRole('button', { name: 'Seguir partidos' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Indica entre 1 y 20 IDs')
    await user.clear(input); await user.type(input, 'match-002')
    await user.click(screen.getByRole('button', { name: 'Seguir partidos' }))
    act(() => harness.connect())
    expect(harness.handlers.has('/topic/matches/match-002/score')).toBe(true)
  })
})
