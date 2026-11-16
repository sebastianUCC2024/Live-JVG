import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { LiveDashboardPage } from './LiveDashboardPage'
import { App } from '../App'
import { catalogMatch, jsonResponse, matchDetail, restSnapshot } from '../test/durableFixtures'
import { alertPage, liveAlert } from '../test/alertFixtures'
import { stompHarness, snapshot } from '../test/stompHarness'

const origin = 'http://127.0.0.1:8082'
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); window.history.replaceState({}, '', '/') })
function durableServer() {
  const fetcher = vi.fn(async (input: string) => {
    const url = new URL(input)
    if (url.pathname === '/api/alerts') return jsonResponse(alertPage([liveAlert(1, { match_id: url.searchParams.get('match_id')! })]))
    if (url.pathname === '/api/matches') return jsonResponse(['match-001', 'match-002'].map(catalogMatch))
    const id = input.includes('match-002') ? 'match-002' : 'match-001'
    return jsonResponse(input.endsWith('/score') ? restSnapshot(id) : matchDetail(id))
  })
  vi.stubGlobal('fetch', fetcher); return fetcher
}
it('añade alertas al partido seleccionado sin alterar marcador REST ni STOMP', async () => {
  const fetcher = durableServer(), harness = stompHarness()
  render(<LiveDashboardPage factory={harness.factory} url="ws://127.0.0.1:18080/ws" notificationOrigin={origin} />)
  expect(await screen.findByLabelText('Atlético Real 1, Unión Real 0')).toBeInTheDocument()
  expect(await screen.findByText('Marcador confirmado: 1–0.')).toBeInTheDocument()
  act(() => {
    harness.connect(); harness.send('/app/matches/match-001/snapshot', { available: true, snapshot: restSnapshot() })
    harness.send('/topic/matches/match-001/score', snapshot({ revision: 3, event_id: 'away', home_score: 1, away_score: 1 }))
  })
  expect(screen.getByLabelText('Atlético Real 1, Unión Real 1')).toBeInTheDocument()
  await userEvent.setup().selectOptions(screen.getByRole('combobox'), 'match-002')
  await waitFor(() => expect(fetcher.mock.calls.some(([url]) => url.includes('/api/alerts?match_id=match-002'))).toBe(true))
  expect(screen.getByRole('region', { name: 'Alertas del partido' })).toHaveTextContent('match-002')
})
it('memory conserva selección manual y no consulta notificaciones aunque haya origen configurado', async () => {
  const fetcher = vi.fn(async (_input: string) => jsonResponse({}, 404)); vi.stubGlobal('fetch', fetcher)
  const harness = stompHarness()
  render(<LiveDashboardPage factory={harness.factory} url="ws://127.0.0.1:8081/ws" notificationOrigin={origin} />)
  await screen.findByRole('button', { name: 'Reintentar catálogo' })
  act(() => { harness.connect(); harness.send('/app/matches/match-001/snapshot', { available: true, snapshot: restSnapshot() }) })
  expect(screen.getByLabelText('Local 1, Visitante 0')).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Alertas del partido' })).not.toBeInTheDocument()
  expect(fetcher.mock.calls.some(([url]) => url.includes('/api/alerts'))).toBe(false)
})
it('demo no abre servicios reales aun con alertas configuradas', async () => {
  vi.stubEnv('VITE_NOTIFICATION_ORIGIN', origin)
  window.history.replaceState({}, '', '/?mode=demo')
  const fetcher = vi.fn(), socket = vi.fn(); vi.stubGlobal('fetch', fetcher); vi.stubGlobal('WebSocket', socket)
  render(<App />)
  await screen.findByText('Atlético Capital')
  expect(fetcher).not.toHaveBeenCalled(); expect(socket).not.toHaveBeenCalled()
})
