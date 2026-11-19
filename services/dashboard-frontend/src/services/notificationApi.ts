import { isAlertPage, type AlertPage } from '../types/alert'

export interface NotificationConfig { origin: string | null; error: string | null }
export function notificationConfig(raw: string | undefined): NotificationConfig {
  if (!raw?.trim()) return { origin: null, error: null }
  try {
    const url = new URL(raw.trim())
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw new Error()
    return { origin: url.origin, error: null }
  } catch { return { origin: null, error: 'Configura VITE_NOTIFICATION_ORIGIN con un origen HTTP(S) válido, sin credenciales ni rutas.' } }
}
export class NotificationApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}
export async function getAlertPage(origin: string, matchId: string, after: string | undefined, signal: AbortSignal): Promise<AlertPage> {
  const config = notificationConfig(origin)
  if (!config.origin) throw new NotificationApiError(0, config.error ?? 'Las alertas no están configuradas.')
  const query = new URLSearchParams({ match_id: matchId, limit: '50' })
  if (after !== undefined) query.set('after', after)
  let response: Response
  try {
    response = await fetch(`${config.origin}/api/alerts?${query}`, { signal, cache: 'no-store', credentials: 'omit', headers: { Accept: 'application/json' } })
  } catch {
    throw new NotificationApiError(0, signal.aborted ? 'La consulta de alertas se canceló o superó el tiempo de espera.' : 'No se pudo conectar con el servicio de alertas.')
  }
  if (!response.ok) {
    const message = response.status === 400 ? 'La consulta de alertas no es válida.'
      : response.status === 409 ? 'El cursor de alertas cambió. Es necesario recuperar el estado inicial.'
      : response.status === 503 ? 'El servicio de alertas no está disponible temporalmente.'
      : `No se pudo consultar las alertas (HTTP ${response.status}).`
    throw new NotificationApiError(response.status, message)
  }
  let value: unknown
  try { value = await response.json() } catch { throw new NotificationApiError(0, 'El servicio devolvió un JSON de alertas inválido.') }
  if (!isAlertPage(value, matchId)) throw new NotificationApiError(0, 'La respuesta de alertas no cumple el DTO del servicio.')
  return value
}
