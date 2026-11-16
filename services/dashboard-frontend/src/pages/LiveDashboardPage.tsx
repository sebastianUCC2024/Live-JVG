import { useEffect, useState } from 'react'
import { useDashboardStream } from '../hooks/useDashboardStream'
import { defaultWebSocketUrl, initialMatchIds, matchMetadata } from '../services/liveConfig'
import type { TransportFactory } from '../services/dashboardStream'
import { MatchCard } from '../components/MatchCard'
import { Icon } from '../components/Icon'
import { createDashboardApi, type CatalogMatch } from '../services/dashboardApi'
import { mergeRecovery, useMatchRecovery } from '../hooks/useMatchRecovery'
import { NotificationAlerts } from '../components/NotificationAlerts'

export const eventLabels = { GOAL: 'Gol', VAR_CHECK: 'Revisión VAR', GOAL_ANNULLED: 'Gol anulado', MATCH_CONTINUES: 'El juego continúa' }
const statusLabels = { connecting: 'Conectando', connected: 'Conectado', reconnecting: 'Reconectando', failed: 'Sin conexión' }

export function LiveDashboardPage({ factory, url = defaultWebSocketUrl, notificationOrigin = import.meta.env.VITE_NOTIFICATION_ORIGIN }: { factory?: TransportFactory; url?: string; notificationOrigin?: string }) {
  const [ids, setIds] = useState<string[]>(initialMatchIds)
  const [input, setInput] = useState(initialMatchIds.join(', '))
  const [formError, setFormError] = useState('')
  const [retry, setRetry] = useState(0)
  const [favorites, setFavorites] = useState<string[]>([])
  const [detail, setDetail] = useState<string | null>(null)
  const state = useDashboardStream(ids, url, retry, factory)
  const recovery = useMatchRecovery(ids, url, state, retry)
  const [catalog, setCatalog] = useState<CatalogMatch[]>([])
  const [catalogStatus, setCatalogStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [catalogError, setCatalogError] = useState('')
  const [catalogRetry, setCatalogRetry] = useState(0)
  const [manual, setManual] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setCatalogStatus('loading')
    void Promise.resolve().then(() => createDashboardApi(url).catalog(controller.signal)).then(matches => {
      if (!active) return
      setCatalog(matches); setCatalogStatus('ready')
      if (!manual) setIds(current => current.length === 1 && matches.some(match => match.match_id === current[0])
        ? current : matches.length ? [matches[0].match_id] : [])
    }).catch(error => {
      if (active) { setCatalogStatus('error'); setCatalogError(error instanceof Error ? error.message : 'No se pudo consultar el catálogo.') }
    })
    return () => { active = false; controller.abort() }
  }, [url, catalogRetry, manual])
  const selectedDetail = detail ? mergeRecovery(state.matches[detail], recovery.data[detail]) : null
  function follow(event: React.FormEvent) {
    event.preventDefault()
    const next = [...new Set(input.split(',').map(id => id.trim()).filter(Boolean))]
    if (!next.length || next.length > 20 || next.some(id => !/^[A-Za-z0-9_.-]+$/.test(id))) {
      setFormError('Indica entre 1 y 20 IDs con letras, números, puntos, guiones o guiones bajos.')
      return
    }
    setManual(true); setFormError(''); setDetail(null); setIds(next)
  }

  return <div className="app-shell live-shell"><a href="#main" className="skip-link">Saltar al contenido</a>
    <aside className="sidebar"><a className="brand" href="#main"><span className="brand-mark">J<span>V</span>G</span><span>LIVE<span className="brand-sub">SPORTS PLATFORM</span></span></a>
      <div className="sidebar-label">TU CENTRO DE JUEGO</div><nav aria-label="Vistas del dashboard"><span className="nav-item active"><Icon name="grid" /> Partidos <span>{ids.length}</span></span></nav>
      <div className="sidebar-note"><strong>Cada decisión cuenta.</strong><p>El marcador que confirma el servidor, sin cálculos adicionales.</p></div></aside>
    <div className="workspace"><header className="topbar"><span>Dashboard <span className="slash">/</span> Marcadores</span><span className={`connection-pill ${state.status}`} role="status">{statusLabels[state.status]}</span></header>
      <main id="main"><div className="page-heading"><div><p className="eyebrow">ACTUALIZACIONES DEL PARTIDO</p><h1>Centro de partidos<span>.</span></h1><p className="intro">Marcadores y decisiones VAR recibidos del dashboard-backend.</p></div><button className="secondary-button" onClick={() => setRetry(value => value + 1)}><Icon name="refresh" /> Reconectar</button></div>
        <section className="watch-form" aria-label="Catálogo de partidos">
          <label htmlFor="catalog-match">Partidos disponibles</label>
          {catalogStatus === 'loading' && <p role="status">Cargando catálogo…</p>}
          {catalogStatus === 'error' && <div role="alert"><p>{catalogError}</p><p>En modo memory puedes utilizar la selección manual.</p><button className="secondary-button" onClick={() => setCatalogRetry(value => value + 1)}>Reintentar catálogo</button></div>}
          {catalogStatus === 'ready' && !catalog.length && <p>No hay partidos disponibles.</p>}
          {catalogStatus === 'ready' && catalog.length > 0 && <select id="catalog-match" value={manual ? '' : ids[0] ?? ''} onChange={event => {
            if (!event.target.value) return
            setManual(false); setDetail(null); setIds([event.target.value])
          }}><option value="" disabled>Selecciona un partido</option>{catalog.map(match => <option key={match.match_id} value={match.match_id}>{match.home_name} — {match.away_name} ({match.match_id})</option>)}</select>}
        </section>
        <details className="manual-selection" open><summary>Selección manual / modo memory</summary><form className="watch-form" onSubmit={follow}><label htmlFor="match-ids">Partidos a seguir · IDs separados por comas</label><div><input id="match-ids" value={input} onChange={event => setInput(event.target.value)} placeholder="match-001, match-002" /><button className="primary-button" type="submit">Seguir partidos</button></div><small>Usa los IDs configurados en el servidor. El modo memory no ofrece catálogo ni historial persistido.</small>{formError && <p role="alert">{formError}</p>}</form></details>
        {recovery.loading && ids.length > 0 && <p role="status">Recuperando historial…</p>}
        {recovery.error && ids.length > 0 && <div className="connection-notice"><p>{recovery.error} Las actualizaciones STOMP siguen disponibles.</p><button className="secondary-button" onClick={() => setRetry(value => value + 1)}>Reintentar recuperación</button></div>}
        {state.status !== 'connected' && <div className="connection-notice" role="status">{state.status === 'failed' ? 'Se detuvieron los reintentos automáticos. Usa Reconectar para volver a intentarlo.' : 'Esperando conexión. El último marcador recibido puede estar desactualizado.'}{state.attempts > 0 && ` Intento ${state.attempts} de 8.`}</div>}
        {state.invalidMessages > 0 && <p className="invalid-notice" role="alert">Se ignoraron {state.invalidMessages} mensajes inválidos. El marcador válido se conserva.</p>}
        <section className="matches-section" aria-label="Partidos en seguimiento"><div className="matches-grid">{ids.map(id => {
          const entry = state.matches[id]
          const snapshot = mergeRecovery(entry, recovery.data[id]).snapshot
          const match = catalog.find(match => match.match_id === id) ?? recovery.data[id]?.detail
          const metadata = { ...matchMetadata[id], ...(match ? { home: match.home_name, away: match.away_name } : {}) }
          return <div key={id} className="watched-match"><div className="match-identifier"><span>Partido {id}</span><span>{state.status !== 'connected' ? 'Sin actualizar' : entry?.recovery === 'pending' ? 'Recuperando estado' : 'Estado recibido'}</span></div>
            {snapshot ? <MatchCard simulated={false} match={{ snapshot,
              home: { name: metadata?.home || 'Local', initials: metadata?.home?.slice(0, 2).toUpperCase() || 'L', color: 'wine' },
              away: { name: metadata?.away || 'Visitante', initials: metadata?.away?.slice(0, 2).toUpperCase() || 'V', color: 'blue' },
              competition: metadata?.competition || `Partido ${id}`, minute: null }} favorite={favorites.includes(id)}
              onFavorite={() => setFavorites(current => current.includes(id) ? current.filter(value => value !== id) : [...current, id])}
              onDetails={() => setDetail(current => current === id ? null : id)} />
              : <div className="state-panel"><span className="state-icon"><Icon name="monitor" /></span><h2>{entry?.recovery === 'unavailable' ? 'Marcador aún no disponible' : 'Esperando marcador'}</h2>{match && <p>{match.home_name} {match.home_score} – {match.away_score} {match.away_name}</p>}<p>{entry?.recovery === 'unavailable' ? 'El servidor todavía no tiene un snapshot de este partido.' : 'El marcador aparecerá cuando el servidor lo confirme.'}</p></div>}
            {snapshot && !metadata?.home && !metadata?.away && <p className="metadata-note">El mensaje no incluye nombres de equipos.</p>}
          </div>
        })}</div></section>
        {detail && selectedDetail?.snapshot && <section className="detail-panel" aria-label="Eventos deportivos"><h2>Últimos eventos · {detail}</h2><button className="secondary-button" onClick={() => setDetail(null)}>Cerrar detalle</button><p>Hasta 30 eventos recientes, sin paginación.</p><ol className="event-list">{selectedDetail.events.map(event => <li key={event.event_id}><strong>{eventLabels[event.event_type]}</strong><span>{event.home_score} – {event.away_score}</span><time dateTime={event.updated_at}>{new Intl.DateTimeFormat('es-CO', { timeStyle: 'medium', timeZone: 'America/Bogota' }).format(new Date(event.updated_at))}</time></li>)}</ol></section>}
        {!manual && catalogStatus === 'ready' && ids.length === 1 && catalog.some(match => match.match_id === ids[0])
          && <NotificationAlerts matchId={ids[0]} origin={notificationOrigin} />}
        <footer className="page-footer">Los snapshots son la fuente del marcador. La recuperación depende del estado disponible en el servidor.</footer>
      </main>
    </div>
  </div>
}
