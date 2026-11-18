import { CommandShell } from '../components/CommandShell'
import { EventTimeline } from '../components/EventTimeline'
import { useState, type ReactNode } from 'react'
import type { DemoMatch, DemoScenario, MatchLoader } from '../types/match'
import { useMatches } from '../hooks/useMatches'
import { MatchCard } from '../components/MatchCard'
import { StatePanel } from '../components/StatePanel'
import { Icon } from '../components/Icon'

const eventLabels = { GOAL: 'Gol', VAR_CHECK: 'Revisión VAR', GOAL_ANNULLED: 'Gol anulado', MATCH_CONTINUES: 'El juego continúa' }
const sampleDate = new Intl.DateTimeFormat('es-CO', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Bogota' })

type Filter = 'all' | 'var' | 'favorites'
export function DashboardPage({ loader, modeControls }: { loader?: MatchLoader; modeControls?: ReactNode }) {
  const [scenario, setScenario] = useState<DemoScenario>('ready')
  const [refresh, setRefresh] = useState(0)
  const [filter, setFilter] = useState<Filter>('all')
  const [search, setSearch] = useState('')
  const [favorites, setFavorites] = useState<string[]>([])
  const [detail, setDetail] = useState<DemoMatch | null>(null)
  const state = useMatches(scenario, refresh, loader)
  const matches = state.status === 'ready' ? state.matches : []
  const reviewing = matches.filter(match => match.snapshot.var_status === 'IN_PROGRESS').length
  const visible = matches.filter(match => (filter !== 'var' || match.snapshot.var_status === 'IN_PROGRESS')
    && (filter !== 'favorites' || favorites.includes(match.snapshot.match_id))
    && `${match.home.name} ${match.away.name} ${match.competition}`.toLocaleLowerCase('es').includes(search.toLocaleLowerCase('es')))
  function toggleFavorite(id: string) { setFavorites(current => current.includes(id) ? current.filter(value => value !== id) : [...current, id]) }

  return <CommandShell mode="demo" modeControls={modeControls} navigation={<>
    <button className={filter !== 'favorites' ? 'nav-item active' : 'nav-item'} onClick={() => setFilter('all')}><Icon name="grid" /> Partidos <span>{matches.length}</span></button>
    <button className={filter === 'favorites' ? 'nav-item active' : 'nav-item'} onClick={() => setFilter('favorites')}><Icon name="star" /> Favoritos <span>{favorites.length}</span></button>
  </>}><div className="page-heading"><div><p className="eyebrow">SPORTS COMMAND CENTER</p><h1>Centro de partidos<span>.</span></h1><p className="intro">El juego no se detiene. Tu perspectiva, tampoco.</p></div><button className="secondary-button" onClick={() => { setRefresh(value => value + 1); setDetail(null) }} disabled={state.status === 'loading'}><Icon name="refresh" /> Recargar demo</button></div>
        <div className="demo-notice"><span className="demo-tag">MODO DEMOSTRACIÓN</span><p>Partidos ficticios para explorar el dashboard. No hay conexión a datos en vivo.</p></div>
        <section className="summary-grid" aria-label="Resumen de la demostración"><div className="summary-item"><span className="summary-icon"><Icon name="grid" /></span><div><p>Partidos de muestra</p><strong>{state.status === 'ready' ? matches.length.toString().padStart(2, '0') : '—'}</strong></div><span className="summary-caption">En esta jornada</span></div>
          <div className="summary-item"><span className="summary-icon amber"><Icon name="monitor" /></span><div><p>En revisión VAR</p><strong>{state.status === 'ready' ? reviewing.toString().padStart(2, '0') : '—'}</strong></div><span className="summary-caption">Decisión pendiente</span></div>
          <div className="summary-item"><span className="summary-icon"><Icon name="check" /></span><div><p>Fuente de datos</p><strong className="summary-text">Simulada</strong></div><span className="summary-caption">Sin conexión externa</span></div></section>
        <section id="matches" className="matches-section" aria-labelledby="matches-title"><div className="section-heading"><div><h2 id="matches-title">La jornada <span>DEMO</span></h2><p>Selecciona un partido para explorar su última actualización.</p></div><label className="search"><Icon name="search" /><input type="search" aria-label="Buscar partido o competición" placeholder="Buscar equipo o competición" value={search} onChange={event => setSearch(event.target.value)} /></label></div>
          <div className="filter-row"><div className="filters" role="group" aria-label="Filtrar partidos">{([['all', 'Todos'], ['var', 'En revisión VAR'], ['favorites', 'Favoritos']] as const).map(([value, label]) => <button key={value} aria-pressed={filter === value} className={filter === value ? 'filter selected' : 'filter'} onClick={() => setFilter(value)}>{label}{value === 'var' && <span>{reviewing}</span>}</button>)}</div><span className="result-count">{visible.length} partidos</span></div>
          {state.status === 'loading' ? <StatePanel kind="loading" onRetry={() => setScenario('ready')} /> : state.status === 'error' ? <StatePanel kind="error" onRetry={() => { setScenario('ready'); setRefresh(value => value + 1) }} /> : matches.length === 0 ? <StatePanel kind="empty" onRetry={() => setScenario('ready')} /> : visible.length === 0 ? <StatePanel kind="filtered" onRetry={() => setScenario('ready')} /> : <div className="matches-grid">{visible.map(match => <MatchCard key={match.snapshot.match_id} match={match} favorite={favorites.includes(match.snapshot.match_id)} onFavorite={() => toggleFavorite(match.snapshot.match_id)} onDetails={() => setDetail(detail?.snapshot.match_id === match.snapshot.match_id ? null : match)} />)}</div>}
        </section>
        {detail && state.status === 'ready' && <section className="detail-panel" aria-label="Detalle del partido"><div><p className="eyebrow">ÚLTIMA ACTUALIZACIÓN SIMULADA</p><h2>{detail.home.name} · {detail.away.name}</h2></div><button className="secondary-button" onClick={() => setDetail(null)}>Cerrar detalle</button><dl><div><dt>Último evento</dt><dd>{eventLabels[detail.snapshot.event_type]}</dd></div><div><dt>Competición</dt><dd>{detail.competition}</dd></div><div><dt>Estado VAR</dt><dd>{detail.snapshot.var_status === 'IN_PROGRESS' ? 'En revisión' : 'Sin revisión activa'}</dd></div><div><dt>Fecha de la muestra (Bogotá)</dt><dd><time dateTime={detail.snapshot.updated_at}>{sampleDate.format(new Date(detail.snapshot.updated_at))}</time></dd></div></dl><div className="detail-timeline"><h3>La jugada, en contexto</h3><p>Solo la última actualización de esta muestra; no es un historial completo.</p><EventTimeline events={[detail.snapshot]} simulated /></div></section>}
        <footer className="page-footer"><span>LIVE-JVG <span className="slash">/</span> La claridad también es parte del juego.</span><label>Escenario de demo <select aria-label="Escenario de demostración" value={scenario} onChange={event => { setScenario(event.target.value as DemoScenario); setDetail(null) }}><option value="ready">Con partidos</option><option value="empty">Sin partidos</option><option value="error">Error simulado</option><option value="loading">Cargando</option></select></label></footer>
  </CommandShell>
}
