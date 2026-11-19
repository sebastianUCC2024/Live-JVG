import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { NotificationAlerts } from './NotificationAlerts'
import { liveAlert, alertPage } from '../test/alertFixtures'
import { jsonResponse } from '../test/durableFixtures'

afterEach(() => vi.unstubAllGlobals())
it('muestra carga, tipo, contenido y fecha real de las alertas', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(alertPage([
    liveAlert(), liveAlert(2, { alert_type: 'VAR_CHECK', var_status: 'IN_PROGRESS', content: { locale: 'es', title: 'Revisión VAR', message: 'Revisión VAR en curso.' } }),
    liveAlert(3, { alert_type: 'GOAL_ANNULLED', reference_event_id: 'event-1', content: { locale: 'es', title: 'Gol anulado', message: 'Marcador confirmado: 0–0.' } }),
  ]))))
  const view = render(<NotificationAlerts matchId="match-001" origin="http://127.0.0.1:8082" />)
  expect(screen.getByText('Cargando alertas…')).toBeInTheDocument()
  expect(await screen.findByText('Revisión VAR en curso.')).toBeInTheDocument()
  const region = screen.getByRole('region', { name: 'Alertas del partido' })
  expect(within(region).getAllByRole('listitem')).toHaveLength(3)
  expect(within(region).getByRole('heading', { name: 'Gol anulado' })).toBeInTheDocument()
  expect(region.querySelector('time')).toHaveAttribute('dateTime', '2026-11-16T15:30:01Z')
  view.unmount()
})
it('muestra lista vacía sin inventar notificaciones', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(alertPage())))
  render(<NotificationAlerts matchId="match-001" origin="http://127.0.0.1:8082" />)
  expect(await screen.findByText('Todavía no hay alertas para este partido.')).toBeInTheDocument()
})
it('ofrece error accesible y reintento tras 503', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse({}, 503)).mockResolvedValueOnce(jsonResponse(alertPage([liveAlert()])))
  vi.stubGlobal('fetch', fetcher)
  render(<NotificationAlerts matchId="match-001" origin="http://127.0.0.1:8082" />)
  expect(await screen.findByRole('alert')).toHaveTextContent('no está disponible')
  await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar alertas' }))
  expect(await screen.findByText('Marcador confirmado: 1–0.')).toBeInTheDocument()
})
it('sin configuración se mantiene desactivado y no consulta el servidor', () => {
  const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
  render(<NotificationAlerts matchId="match-001" origin={undefined} />)
  expect(screen.getByText(/Alertas desactivadas/)).toBeInTheDocument()
  expect(fetcher).not.toHaveBeenCalled()
})
