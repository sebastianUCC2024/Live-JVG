import { afterEach, describe, expect, it, vi } from 'vitest'
import { getAlertPage, notificationConfig } from './notificationApi'
import { alertPage, liveAlert } from '../test/alertFixtures'
import { jsonResponse } from '../test/durableFixtures'
import { isAlertPage, isLiveAlert } from '../types/alert'

const origin = 'http://127.0.0.1:8082'
const signal = () => new AbortController().signal
afterEach(() => vi.unstubAllGlobals())
describe('Cliente REST de alertas', () => {
  it('valida el origen público sin asumir puerto ni permitir credenciales', () => {
    expect(notificationConfig(undefined)).toEqual({ origin: null, error: null })
    expect(notificationConfig('https://example.test/').origin).toBe('https://example.test')
    for (const raw of ['ws://localhost', 'http://user:password@localhost', 'http://localhost/api', 'http://localhost?token=value', 'broken'])
      expect(notificationConfig(raw).origin).toBeNull()
  })
  it('consulta la página inicial con cancelación y sin credenciales', async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse(alertPage([liveAlert()])))
    vi.stubGlobal('fetch', fetcher)
    const abort = signal()
    expect(await getAlertPage(origin, 'match-001', undefined, abort)).toEqual(alertPage([liveAlert()]))
    expect(fetcher).toHaveBeenCalledWith(`${origin}/api/alerts?match_id=match-001&limit=50`, expect.objectContaining({ signal: abort, cache: 'no-store', credentials: 'omit' }))
  })
  it('codifica el cursor como valor opaco y admite listas vacías', async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse(alertPage()))
    vi.stubGlobal('fetch', fetcher)
    expect((await getAlertPage(origin, 'match-001', 'opaque+/=', signal())).items).toEqual([])
    const url = new URL(fetcher.mock.calls[0][0])
    expect(url.searchParams.get('after')).toBe('opaque+/=')
  })
  it.each([400, 409, 503])('distingue HTTP %s sin mostrar el cuerpo del error', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ error: 'internal-secret' }, status)))
    await expect(getAlertPage(origin, 'match-001', undefined, signal())).rejects.toMatchObject({ status })
  })
  it('gestiona fallos de red y abortos', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(getAlertPage(origin, 'match-001', undefined, signal())).rejects.toThrow('No se pudo conectar')
    const controller = new AbortController(); controller.abort()
    await expect(getAlertPage(origin, 'match-001', undefined, controller.signal)).rejects.toThrow('canceló')
  })
  it('rechaza JSON inválido y DTO de otro partido', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response('invalid')).mockResolvedValueOnce(jsonResponse(alertPage([liveAlert(1, { match_id: 'other' })]))))
    await expect(getAlertPage(origin, 'match-001', undefined, signal())).rejects.toThrow('JSON')
    await expect(getAlertPage(origin, 'match-001', undefined, signal())).rejects.toThrow('DTO')
  })
  it.each([
    { contract_version: '2.0' }, { alert_id: 'not-uuid' }, { home_score: -1 }, { away_score: 2147483648 },
    { alert_type: 'UNKNOWN' }, { created_at: 'invalid' }, { reference_event_id: '' },
    { alert_type: 'GOAL_ANNULLED', reference_event_id: null }, { content: { locale: 'es', title: '', message: 'Texto' } },
  ])('rechaza campos inválidos: %o', changes => {
    expect(isLiveAlert({ ...liveAlert(), ...changes }, 'match-001')).toBe(false)
  })
  it('rechaza envolturas sin cursor, backlog vacío o páginas sobredimensionadas', () => {
    expect(isAlertPage(alertPage([], '', false), 'match-001')).toBe(false)
    expect(isAlertPage(alertPage([], 'cursor', true), 'match-001')).toBe(false)
    expect(isAlertPage(alertPage(Array(51).fill(liveAlert())), 'match-001')).toBe(false)
  })
  it.each(['2026-02-30T12:00:00Z', '2025-02-29T12:00:00Z', '2026-04-31T12:00:00Z', '2026-11-16T24:00:00Z'])('rechaza fechas normalizadas silenciosamente por Date.parse: %s', created_at => {
    expect(isLiveAlert(liveAlert(1, { created_at }), 'match-001')).toBe(false)
  })
  it('acepta un día bisiesto y precisión de nanosegundos', () => {
    expect(isLiveAlert(liveAlert(1, { created_at: '2024-02-29T23:59:59.123456789Z' }), 'match-001')).toBe(true)
  })
})
