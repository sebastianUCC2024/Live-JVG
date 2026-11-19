import type { EventType } from '../types/match'
import { Icon } from './Icon'

export interface TimelineEvent {
  event_id: string
  event_type: EventType
  home_score: number
  away_score: number
  updated_at: string
  reference_event_id: string | null
}
const labels: Record<EventType, string> = { GOAL: 'Gol', VAR_CHECK: 'Revisión VAR', GOAL_ANNULLED: 'Gol anulado', MATCH_CONTINUES: 'El juego continúa', MATCH_FINISHED: 'Finalizado' }
const descriptions: Record<EventType, string> = { GOAL: 'Marcador actualizado', VAR_CHECK: 'La jugada entra en revisión', GOAL_ANNULLED: 'Corrección del marcador', MATCH_CONTINUES: 'Actualización del estado del partido', MATCH_FINISHED: 'Resultado definitivo' }
const time = new Intl.DateTimeFormat('es-CO', { timeStyle: 'medium', timeZone: 'America/Bogota' })

/** Displays the received order and scores; never creates or reorders sporting events. */
export function EventTimeline({ events, simulated = false }: { events: readonly TimelineEvent[]; simulated?: boolean }) {
  return <ol className="event-list timeline" aria-label={simulated ? 'Última actualización simulada' : 'Línea de tiempo del partido'}>
    {events.map(event => <li key={event.event_id} className={`timeline-event event-${event.event_type.toLowerCase()}`}>
      <span className="timeline-marker" aria-hidden="true"><Icon name={event.event_type === 'GOAL' ? 'ball' : event.event_type === 'VAR_CHECK' ? 'monitor' : event.event_type === 'GOAL_ANNULLED' ? 'cancel' : 'arrow'} /></span>
      <div className="timeline-copy"><strong>{labels[event.event_type]}</strong><p>{descriptions[event.event_type]}</p>{event.reference_event_id && <small>Referencia: <span>{event.reference_event_id}</span></small>}</div>
      <span className="timeline-score" aria-label={`Marcador tras el evento: ${event.home_score} a ${event.away_score}`}>{event.home_score} – {event.away_score}</span>
      <time dateTime={event.updated_at}>{time.format(new Date(event.updated_at))}<span>Bogotá{simulated ? ' · muestra' : ''}</span></time>
    </li>)}
  </ol>
}
