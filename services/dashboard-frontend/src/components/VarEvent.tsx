import type { MatchSnapshot } from '../types/match'
import { Icon } from './Icon'

export function VarEvent({ snapshot }: { snapshot: MatchSnapshot }) {
  const reviewing = snapshot.var_status === 'IN_PROGRESS'
  const annulled = snapshot.event_type === 'GOAL_ANNULLED'
  return <div className={`var-event ${reviewing ? 'reviewing' : annulled ? 'annulled' : 'clear'}`}>
    <span className="var-symbol">{reviewing || annulled ? <Icon name="monitor" /> : <Icon name="check" />}</span>
    <div><strong>{reviewing ? 'Revisión VAR en curso' : annulled ? 'Gol anulado por VAR' : 'Sin revisión activa'}</strong>
      <p>{reviewing ? 'El marcador puede cambiar' : annulled ? 'Marcador corregido' : 'El juego continúa'}</p></div>
    {reviewing && <span className="pulse-dot" aria-hidden="true" />}
  </div>
}
