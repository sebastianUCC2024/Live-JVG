import { eventTypes, type EventType, type VarStatus } from './match'

/** Respuesta AlertResponse local de notification-backend. */
export interface LiveAlert {
  contract_version: '1.0'
  alert_id: string
  match_id: string
  alert_type: EventType
  source_event_id: string
  source_updated_at: string
  created_at: string
  reference_event_id: string | null
  home_score: number
  away_score: number
  var_status: VarStatus
  content: { locale: 'es'; title: string; message: string }
}
export interface AlertPage { items: LiveAlert[]; next_cursor: string; has_more: boolean }

const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0
function instant(value: unknown): boolean {
  if (typeof value !== 'string') return false
  const parts = /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.exec(value)
  if (!parts || !Number.isFinite(Date.parse(value))) return false
  const [, yearText, monthText, dayText] = parts
  const year = Number(yearText), month = Number(monthText), day = Number(dayText)
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  // Valida la fecha antes de mostrarla porque Date.parse normaliza fechas imposibles.
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1]
}
const score = (value: unknown) => typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 2147483647

export function isLiveAlert(value: unknown, matchId: string): value is LiveAlert {
  if (!object(value) || !object(value.content)) return false
  return value.contract_version === '1.0'
    && typeof value.alert_id === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.alert_id)
    && value.match_id === matchId && text(value.match_id) && text(value.source_event_id)
    && eventTypes.includes(value.alert_type as EventType)
    && instant(value.source_updated_at) && instant(value.created_at)
    && (value.reference_event_id === null || text(value.reference_event_id))
    && (value.alert_type !== 'GOAL_ANNULLED' || text(value.reference_event_id))
    && score(value.home_score) && score(value.away_score)
    && (value.var_status === 'CLEAR' || value.var_status === 'IN_PROGRESS')
    && value.content.locale === 'es' && text(value.content.title) && value.content.title.length <= 120
    && text(value.content.message) && value.content.message.length <= 500
}
export function isAlertPage(value: unknown, matchId: string): value is AlertPage {
  return object(value) && Array.isArray(value.items) && value.items.length <= 50
    && value.items.every(item => isLiveAlert(item, matchId)) && text(value.next_cursor) && value.next_cursor.length <= 4096
    && typeof value.has_more === 'boolean' && (!value.has_more || value.items.length > 0)
}
