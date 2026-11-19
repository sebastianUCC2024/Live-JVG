import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LiveDashboardPage } from './LiveDashboardPage'
import { App } from '../App'
import { catalogMatch, jsonResponse, matchDetail, restSnapshot } from '../test/durableFixtures'
import { snapshot, stompHarness } from '../test/stompHarness'

afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState({}, '', '/') })
const endpoint = 'ws://127.0.0.1:18080/ws'
function server(ids = ['match-001', 'match-002']) {
  const fetcher = vi.fn(async (input: string) => {
    if (input.endsWith('/api/matches')) return jsonResponse(ids.map(catalogMatch))
    const id = input.includes('match-002') ? 'match-002' : 'match-001'
    return jsonResponse(input.endsWith('/score') ? restSnapshot(id) : matchDetail(id))
  })
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}
describe('Dashboard durable con transporte controlado', () => {
  it('muestra carga, catálogo, nombres reales y selección de partido', async () => {
    const fetcher = server()
    const harness = stompHarness(), user = userEvent.setup()
    render(<LiveDashboardPage url={endpoint} factory={harness.factory} />)
    expect(screen.getByText('Cargando catálogo…')).toBeInTheDocument()
    const selector = await screen.findByRole('combobox', { name: 'Partidos disponibles' })
    expect(within(selector).getAllByRole('option')).toHaveLength(3)
    expect(await screen.findByLabelText('Atlético Real 1, Unión Real 0')).toBeInTheDocument()
    await user.selectOptions(selector, 'match-002')
    await waitFor(() => expect(fetcher.mock.calls.some(([url]) => url.endsWith('/matches/match-002'))).toBe(true))
    act(() => harness.connect())
    expect(harness.handlers.has('/topic/matches/match-002/score')).toBe(true)
    expect(harness.handlers.has('/app/matches/match-002/snapshot')).toBe(true)
  })
  it('muestra catálogo vacío sin inventar partidos', async () => {
    server([])
    render(<LiveDashboardPage url={endpoint} factory={stompHarness().factory} />)
    expect(await screen.findByText('No hay partidos disponibles.')).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
  it('reporta error y permite reintentar la carga del catálogo', async () => {
    const fetcher = server()
    let fail = true
    fetcher.mockImplementation(async input => {
      if (input.endsWith('/api/matches')) {
        if (fail) { fail = false; return jsonResponse({}, 503) }
        return jsonResponse([catalogMatch()])
      }
      return jsonResponse(input.endsWith('/score') ? restSnapshot() : matchDetail())
    })
    render(<LiveDashboardPage url={endpoint} factory={stompHarness().factory} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 503')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar catálogo' }))
    expect(await screen.findByRole('combobox')).toBeInTheDocument()
  })
  it('restaura el historial tras recargar y deduplica REST/WebSocket', async () => {
    server(['match-001'])
    const user = userEvent.setup(), harness = stompHarness()
    let view = render(<LiveDashboardPage url={endpoint} factory={harness.factory} />)
    await screen.findByLabelText('Atlético Real 1, Unión Real 0')
    await user.click(screen.getByRole('button', { name: /Ver detalle/ }))
    expect(within(screen.getByRole('region', { name: 'Eventos deportivos' })).getAllByRole('listitem')).toHaveLength(2)
    act(() => {
      harness.connect()
      harness.send('/app/matches/match-001/snapshot', { available: true, snapshot: restSnapshot() })
      harness.send('/topic/matches/match-001/score', restSnapshot())
    })
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Eventos deportivos' })).getAllByRole('listitem')).toHaveLength(2))
    view.unmount()
    view = render(<LiveDashboardPage url={endpoint} factory={stompHarness().factory} />)
    await screen.findByLabelText('Atlético Real 1, Unión Real 0')
    await user.click(screen.getByRole('button', { name: /Ver detalle/ }))
    expect(within(screen.getByRole('region', { name: 'Eventos deportivos' })).getAllByRole('listitem')).toHaveLength(2)
    view.unmount()
  })
  it('una respuesta REST tardía no sobrescribe el marcador STOMP', async () => {
    const fetcher = server(['match-001'])
    const pending: Array<() => void> = []
    fetcher.mockImplementation(async input => {
      if (input.endsWith('/api/matches')) return jsonResponse([catalogMatch()])
      if (input.endsWith('/score')) return jsonResponse(restSnapshot())
      return new Promise<Response>(resolve => { pending.push(() => resolve(jsonResponse(matchDetail()))) })
    })
    const harness = stompHarness()
    render(<LiveDashboardPage url={endpoint} factory={harness.factory} />)
    await screen.findByRole('combobox')
    act(() => {
      harness.connect()
      harness.send('/app/matches/match-001/snapshot', { available: true, snapshot: restSnapshot() })
      harness.send('/topic/matches/match-001/score', snapshot({ revision: 3, event_id: 'away', home_score: 1, away_score: 1 }))
    })
    await waitFor(() => expect(pending.length).toBeGreaterThan(1))
    await act(async () => { for (const resolve of pending) resolve() })
    await waitFor(() => expect(screen.queryByText('Recuperando historial…')).not.toBeInTheDocument())
    expect(await screen.findByLabelText('Atlético Real 1, Unión Real 1')).toBeInTheDocument()
  })
  it('ignora respuestas del partido anterior después de seleccionar otro', async () => {
    const fetcher = server()
    let resolveOld: (() => void) | undefined
    fetcher.mockImplementation(async input => {
      if (input.endsWith('/api/matches')) return jsonResponse(['match-001', 'match-002'].map(catalogMatch))
      if (input.endsWith('/match-001')) return new Promise<Response>(resolve => { resolveOld = () => resolve(jsonResponse(matchDetail())) })
      const id = input.includes('match-002') ? 'match-002' : 'match-001'
      return jsonResponse(input.endsWith('/score') ? restSnapshot(id) : matchDetail(id))
    })
    render(<LiveDashboardPage url={endpoint} factory={stompHarness().factory} />)
    await userEvent.setup().selectOptions(await screen.findByRole('combobox'), 'match-002')
    await screen.findByLabelText('Atlético Real 1, Unión Real 0')
    await act(async () => resolveOld?.())
    expect(screen.getAllByText('Partido match-002').length).toBeGreaterThan(0)
    expect(screen.queryByText('Partido match-001')).not.toBeInTheDocument()
  })
  it('reconsulta REST al reconectar y conserva el nuevo snapshot', async () => {
    const fetcher = server(['match-001']), harness = stompHarness()
    render(<LiveDashboardPage url={endpoint} factory={harness.factory} />)
    await screen.findByLabelText('Atlético Real 1, Unión Real 0')
    act(() => { harness.connect(); harness.send('/app/matches/match-001/snapshot', { available: true, snapshot: restSnapshot() }) })
    await waitFor(() => expect(screen.queryByText('Recuperando historial…')).not.toBeInTheDocument())
    const before = fetcher.mock.calls.length
    act(() => harness.close())
    expect(screen.getByText('Reconectando')).toBeInTheDocument()
    act(() => { harness.connect(); harness.send('/app/matches/match-001/snapshot', { available: true, snapshot: restSnapshot() }) })
    await waitFor(() => expect(fetcher.mock.calls.length).toBeGreaterThan(before))
    expect(screen.getByLabelText('Atlético Real 1, Unión Real 0')).toBeInTheDocument()
  })
  it('la demostración no consulta REST ni abre WebSocket', async () => {
    window.history.replaceState({}, '', '/?mode=demo')
    const fetcher = vi.fn(), socket = vi.fn()
    vi.stubGlobal('fetch', fetcher); vi.stubGlobal('WebSocket', socket)
    render(<App />)
    await screen.findByText('Atlético Capital')
    expect(fetcher).not.toHaveBeenCalled()
    expect(socket).not.toHaveBeenCalled()
  })
})
