import { isMatchSnapshot, type MatchSnapshot, type EventType, type VarStatus } from '../types/match'

/** PostgreSQL catalog row returned by DashboardController / ScoreProjection. */
export interface CatalogMatch {
  match_id: string
  home_team_id: string
  away_team_id: string
  home_name: string
  away_name: string
  home_score: number
  away_score: number
  var_status: VarStatus
  last_event_id: string | null
  updated_at: string | null
}
/** Rabbit payload stored in inbox: no stream identity or per-event revision. */
export interface ScoreEvent {
  match_id: string
  event_id: string
  event_type: EventType
  home_score: number
  away_score: number
  var_status: VarStatus
  updated_at: string
  reference_event_id: string | null
}
export interface MatchDetail extends CatalogMatch { events: ScoreEvent[]; active_goals: ScoreEvent[] }
export interface MatchRecovery { detail: MatchDetail; snapshot: MatchSnapshot | null }

export class DashboardApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

export function resolveRestOrigin(webSocketUrl: string): string {
  const url = new URL(webSocketUrl)
  if (!['ws:', 'wss:'].includes(url.protocol) || url.username || url.password || url.pathname !== '/ws'
    || url.search || url.hash) throw new Error('La URL WebSocket debe terminar en /ws y no incluir credenciales.')
  url.protocol = url.protocol === 'wss:' ? 'https:' : 'http:'
  return url.origin
}
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
const score = (value: unknown) => Number.isSafeInteger(value) && Number(value) >= 0
const instant = (value: unknown) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value))
function isCatalogMatch(value: unknown): value is CatalogMatch {
  return object(value) && ['match_id', 'home_team_id', 'away_team_id', 'home_name', 'away_name'].every(key => text(value[key]))
    && /^[A-Za-z0-9_.-]+$/.test(String(value.match_id))
    && score(value.home_score) && score(value.away_score) && ['CLEAR', 'IN_PROGRESS'].includes(String(value.var_status))
    && (value.last_event_id === null || text(value.last_event_id)) && (value.updated_at === null || instant(value.updated_at))
}
function parseEvent(value: unknown, matchId: string): ScoreEvent {
  if (!object(value) || value.match_id !== matchId) throw new Error('Evento REST inválido.')
  // Reuse the browser payload validator without exposing invented fields in REST DTOs.
  const normalized = { ...value, reference_event_id: value.reference_event_id ?? null }
  if (!isMatchSnapshot({ ...normalized, stream_id: 'validation', revision: 1 })) throw new Error('Evento REST inválido.')
  return normalized as unknown as ScoreEvent
}

export function createDashboardApi(webSocketUrl: string) {
  const origin = resolveRestOrigin(webSocketUrl)
  async function get(path: string, signal: AbortSignal): Promise<unknown> {
    let response: Response
    try {
      response = await fetch(`${origin}/api/matches${path}`, { signal, cache: 'no-store', headers: { Accept: 'application/json' } })
    } catch (error) {
      if (signal.aborted) throw error
      throw new DashboardApiError(0, 'No se pudo conectar con el dashboard. Comprueba la URL y la conexión.')
    }
    if (!response.ok) throw new DashboardApiError(response.status, `No se pudo consultar el dashboard (HTTP ${response.status}).`)
    try { return await response.json() }
    catch { throw new Error('El dashboard devolvió una respuesta JSON inválida.') }
  }
  return {
    async catalog(signal: AbortSignal): Promise<CatalogMatch[]> {
      const data = await get('', signal)
      if (!Array.isArray(data) || !data.every(isCatalogMatch)
        || new Set(data.map(match => match.match_id)).size !== data.length) throw new Error('Catálogo REST inválido.')
      return data
    },
    async detail(id: string, signal: AbortSignal): Promise<MatchDetail> {
      const data = await get(`/${encodeURIComponent(id)}`, signal)
      if (!isCatalogMatch(data) || data.match_id !== id || !object(data)
        || !Array.isArray(data.events) || data.events.length > 30 || !Array.isArray(data.active_goals)) throw new Error('Detalle REST inválido.')
      return { ...data, events: data.events.map(event => parseEvent(event, id)), active_goals: data.active_goals.map(event => parseEvent(event, id)) }
    },
    async snapshot(id: string, signal: AbortSignal): Promise<MatchSnapshot | null> {
      try {
        const data = await get(`/${encodeURIComponent(id)}/score`, signal)
        if (!isMatchSnapshot(data) || data.match_id !== id) throw new Error('Snapshot REST inválido.')
        return data
      } catch (error) {
        if (error instanceof DashboardApiError && error.status === 404) return null
        throw error
      }
    },
  }
}

export async function loadMatchRecovery(api: ReturnType<typeof createDashboardApi>, id: string, signal: AbortSignal): Promise<MatchRecovery> {
  // Detail is read first; a later snapshot anchors its newest event to a durable revision.
  // Retry once if a publication commits between these two independent GETs.
  for (let attempt = 0; attempt < 2; attempt++) {
    const detail = await api.detail(id, signal)
    const snapshot = await api.snapshot(id, signal)
    if ((detail.last_event_id === null && snapshot === null && detail.events.length === 0)
      || (snapshot && detail.last_event_id === snapshot.event_id && detail.events[0]?.event_id === snapshot.event_id)) return { detail, snapshot }
  }
  throw new Error('El partido cambió durante la consulta. Reintenta recuperar el historial.')
}
