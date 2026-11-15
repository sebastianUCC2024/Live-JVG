import type { DemoMatch } from '../types/match'
import { Scoreboard } from './Scoreboard'
import { VarEvent } from './VarEvent'
import { Icon } from './Icon'

export function MatchCard({ match, favorite, onFavorite, onDetails, simulated = true }: { match: DemoMatch; favorite: boolean; onFavorite: () => void; onDetails: () => void; simulated?: boolean }) {
  const { snapshot, home, away } = match
  return <article className={`match-card ${snapshot.var_status === 'IN_PROGRESS' ? 'under-review' : ''}`} aria-label={`${home.name} contra ${away.name}`}>
    <div className="card-top"><span>{match.competition}</span><button className={`icon-button favorite ${favorite ? 'selected' : ''}`} onClick={onFavorite} aria-pressed={favorite} aria-label={`Favorito: ${home.name} contra ${away.name}`}><Icon name="star" /></button></div>
    <div className="match-clock"><span className="tiny-dot" />{simulated ? <>{match.minute}′ <span>· minuto simulado</span></> : 'Último marcador recibido'}</div>
    <div className="teams"><div className="team"><span className={`crest ${home.color}`} aria-hidden="true">{home.initials}</span><h3>{home.name}</h3><small>Local</small></div>
      <Scoreboard home={snapshot.home_score} away={snapshot.away_score} homeName={home.name} awayName={away.name} />
      <div className="team"><span className={`crest ${away.color}`} aria-hidden="true">{away.initials}</span><h3>{away.name}</h3><small>Visitante</small></div></div>
    <VarEvent snapshot={snapshot} />
    <div className="card-bottom"><span>{simulated ? 'Escenario simulado' : 'Datos del dashboard-backend'}</span><button onClick={onDetails}>Ver detalle <Icon name="arrow" /></button></div>
  </article>
}
