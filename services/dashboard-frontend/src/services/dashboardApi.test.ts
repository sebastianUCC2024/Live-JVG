import { afterEach, describe, expect, it, vi } from 'vitest'
import { createDashboardApi, loadMatchRecovery, resolveRestOrigin } from './dashboardApi'
import { catalogMatch, jsonResponse, matchDetail, restSnapshot } from '../test/durableFixtures'

afterEach(() => vi.unstubAllGlobals())
const signal = () => new AbortController().signal
const api = () => createDashboardApi('ws://127.0.0.1:18080/ws')
describe('REST durable verificado contra ScoreProjection', () => {
  it('deriva HTTP(S) del endpoint STOMP efectivo', () => {
    expect(resolveRestOrigin('ws://127.0.0.1:18080/ws')).toBe('http://127.0.0.1:18080')
    expect(resolveRestOrigin('wss://example.test/ws')).toBe('https://example.test')
    expect(() => resolveRestOrigin('ws://user:secret@example.test/ws')).toThrow()
    expect(() => resolveRestOrigin('ws://example.test/other')).toThrow()
  })
  it('consulta catálogo con cancelación y sin caché', async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse([catalogMatch()]))
    vi.stubGlobal('fetch', fetcher)
    const abort = signal()
    expect(await api().catalog(abort)).toEqual([catalogMatch()])
    expect(fetcher).toHaveBeenCalledWith('http://127.0.0.1:18080/api/matches', expect.objectContaining({ signal: abort, cache: 'no-store' }))
  })
  it('acepta catálogo vacío y rechaza respuesta incompatible', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse([])).mockResolvedValueOnce(jsonResponse([{ id: 'invented' }])))
    expect(await api().catalog(signal())).toEqual([])
    await expect(api().catalog(signal())).rejects.toThrow('Catálogo REST inválido')
  })
  it('reporta errores HTTP y JSON inválido', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse({}, 503)).mockResolvedValueOnce(new Response('invalid')))
    await expect(api().catalog(signal())).rejects.toThrow('HTTP 503')
    await expect(api().catalog(signal())).rejects.toThrow()
  })
  it('presenta errores de red en español', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(api().catalog(signal())).rejects.toThrow('No se pudo conectar con el dashboard')
  })
  it('distingue snapshot ausente de errores de servidor', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse({}, 404)).mockResolvedValueOnce(jsonResponse({}, 500)))
    expect(await api().snapshot('match-001', signal())).toBeNull()
    await expect(api().snapshot('match-001', signal())).rejects.toThrow('HTTP 500')
  })
  it('carga detalle antes de anclarlo a un snapshot real', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse(matchDetail())).mockResolvedValueOnce(jsonResponse(restSnapshot()))
    vi.stubGlobal('fetch', fetcher)
    expect(await loadMatchRecovery(api(), 'match-001', signal())).toEqual({ detail: matchDetail(), snapshot: restSnapshot() })
    expect(fetcher.mock.calls.map(call => call[0])).toEqual([
      'http://127.0.0.1:18080/api/matches/match-001', 'http://127.0.0.1:18080/api/matches/match-001/score',
    ])
  })
  it('reintenta una carrera entre detalle y snapshot y limita intentos', async () => {
    const fetcher = vi.fn().mockImplementation((url: string) => Promise.resolve(jsonResponse(url.endsWith('/score')
      ? { ...restSnapshot(), event_id: 'newer' } : matchDetail())))
    vi.stubGlobal('fetch', fetcher)
    await expect(loadMatchRecovery(api(), 'match-001', signal())).rejects.toThrow('El partido cambió')
    expect(fetcher).toHaveBeenCalledTimes(4)
  })
  it('acepta una recuperación consistente después de una carrera', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse(matchDetail()))
      .mockResolvedValueOnce(jsonResponse({ ...restSnapshot(), event_id: 'intermediate' }))
      .mockResolvedValueOnce(jsonResponse(matchDetail())).mockResolvedValueOnce(jsonResponse(restSnapshot())))
    expect((await loadMatchRecovery(api(), 'match-001', signal())).snapshot).toEqual(restSnapshot())
  })
  it('acepta un partido configurado antes del primer evento', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse({ ...matchDetail(), last_event_id: null, updated_at: null, events: [], active_goals: [] }))
      .mockResolvedValueOnce(jsonResponse({}, 404)))
    expect((await loadMatchRecovery(api(), 'match-001', signal())).snapshot).toBeNull()
  })
  it('rechaza historia de otro partido y más de 30 eventos', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse({ ...matchDetail(), events: [{ ...matchDetail().events[0], match_id: 'other' }] }))
      .mockResolvedValueOnce(jsonResponse({ ...matchDetail(), events: Array(31).fill(matchDetail().events[0]) })))
    await expect(api().detail('match-001', signal())).rejects.toThrow('Evento REST inválido')
    await expect(api().detail('match-001', signal())).rejects.toThrow('Detalle REST inválido')
  })
})
