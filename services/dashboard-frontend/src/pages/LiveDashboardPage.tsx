import { useState } from 'react'
import { useDashboardStream } from '../hooks/useDashboardStream'
import { defaultWebSocketUrl, initialMatchIds, matchMetadata } from '../services/liveConfig'
import type { TransportFactory } from '../services/dashboardStream'
import { MatchCard } from '../components/MatchCard'
import { Icon } from '../components/Icon'

export const eventLabels = { GOAL: 'Gol', VAR_CHECK: 'Revisión VAR', GOAL_ANNULLED: 'Gol anulado', MATCH_CONTINUES: 'El juego continúa' }
const statusLabels = { connecting: 'Conectando', connected: 'Conectado', reconnecting: 'Reconectando', failed: 'Sin conexión' }

export function LiveDashboardPage({ factory, url = defaultWebSocketUrl }: { factory?: TransportFactory; url?: string }) {
  const [ids, setIds] = useState<string[]>(initialMatchIds)
  const [input, setInput] = useState(initialMatchIds.join(', '))
  const [formError, setFormError] = useState('')
  const [retry, setRetry] = useState(0)
  const [favorites, setFavorites] = useState<string[]>([])
  const [detail, setDetail] = useState<string | null>(null)
  const state = useDashboardStream(ids, url, retry, factory)
  function follow(event: React.FormEvent) {
    event.preventDefault()
    const next = [...new Set(input.split(',').map(id => id.trim()).filter(Boolean))]
    if (!next.length || next.length > 20 || next.some(id => !/^[A-Za-z0-9_.-]+$/.test(id))) {
      setFormError('Indica entre 1 y 20 IDs con letras, números, puntos, guiones o guiones bajos.')
      return
    }
    setFormError(''); setDetail(null); setIds(next)
  }

  return <div className="app-shell live-shell"><a href="#main" className="skip-link">Saltar al contenido</a>
    <aside className="sidebar"><a className="brand" href="#main"><span className="brand-mark">J<span>V</span>G</span><span>LIVE<span className="brand-sub">SPORTS PLATFORM</span></span></a>
      <div className="sidebar-label">TU CENTRO DE JUEGO</div><nav aria-label="Vistas del dashboard"><span className="nav-item active"><Icon name="grid" /> Partidos <span>{ids.length}</span></span></nav>
      <div className="sidebar-note"><strong>Cada decisión cuenta.</strong><p>El marcador que confirma el servidor, sin cálculos adicionales.</p></div></aside>
    <div className="workspace"><header className="topbar"><span>Dashboard <span className="slash">/</span> Marcadores</span><span className={`connection-pill ${state.status}`} role="status">{statusLabels[state.status]}</span></header>
      <main id="main"><div className="page-heading"><div><p className="eyebrow">ACTUALIZACIONES DEL PARTIDO</p><h1>Centro de partidos<span>.</span></h1><p className="intro">Marcadores y decisiones VAR recibidos del dashboard-backend.</p></div><button className="secondary-button" onClick={() => setRetry(value => value + 1)}><Icon name="refresh" /> Reconectar</button></div>
        <form className="watch-form" onSubmit={follow}><label htmlFor="match-ids">Partidos a seguir · IDs separados por comas</label><div><input id="match-ids" value={input} onChange={event => setInput(event.target.value)} placeholder="match-001, match-002" /><button className="primary-button" type="submit">Seguir partidos</button></div><small>El servidor no publica un catálogo. Indica los IDs registrados por el equipo.</small>{formError && <p role="alert">{formError}</p>}</form>
        {state.status !== 'connected' && <div className="connection-notice" role="status">{state.status === 'failed' ? 'Se detuvieron los reintentos automáticos. Usa Reconectar para volver a intentarlo.' : 'Esperando conexión. El último marcador recibido puede estar desactualizado.'}{state.attempts > 0 && ` Intento ${state.attempts} de 8.`}</div>}
        {state.invalidMessages > 0 && <p className="invalid-notice" role="alert">Se ignoraron {state.invalidMessages} mensajes inválidos. El marcador válido se conserva.</p>}
        <section className="matches-section" aria-label="Partidos en seguimiento"><div className="matches-grid">{ids.map(id => {
          const entry = state.matches[id]
          const snapshot = entry?.snapshot
          const metadata = matchMetadata[id]
          return <div key={id} className="watched-match"><div className="match-identifier"><span>Partido {id}</span><span>{state.status !== 'connected' ? 'Sin actualizar' : entry?.recovery === 'pending' ? 'Recuperando estado' : 'Estado recibido'}</span></div>
            {snapshot ? <MatchCard simulated={false} match={{ snapshot,
              home: { name: metadata?.home || 'Local', initials: metadata?.home?.slice(0, 2).toUpperCase() || 'L', color: 'wine' },
              away: { name: metadata?.away || 'Visitante', initials: metadata?.away?.slice(0, 2).toUpperCase() || 'V', color: 'blue' },
              competition: metadata?.competition || `Partido ${id}`, minute: null }} favorite={favorites.includes(id)}
              onFavorite={() => setFavorites(current => current.includes(id) ? current.filter(value => value !== id) : [...current, id])}
              onDetails={() => setDetail(current => current === id ? null : id)} />
              : <div className="state-panel"><span className="state-icon"><Icon name="monitor" /></span><h2>{entry?.recovery === 'unavailable' ? 'Marcador aún no disponible' : 'Esperando marcador'}</h2><p>{entry?.recovery === 'unavailable' ? 'El servidor todavía no tiene un snapshot de este partido.' : 'El marcador aparecerá cuando el servidor lo confirme.'}</p></div>}
            {snapshot && !metadata?.home && !metadata?.away && <p className="metadata-note">El mensaje no incluye nombres de equipos.</p>}
          </div>
        })}</div></section>
        {detail && state.matches[detail]?.snapshot && <section className="detail-panel" aria-label="Eventos deportivos"><h2>Últimos eventos · {detail}</h2><button className="secondary-button" onClick={() => setDetail(null)}>Cerrar detalle</button><ol className="event-list">{state.matches[detail].events.map(snapshot => <li key={`${snapshot.stream_id}:${snapshot.event_id}`}><strong>{eventLabels[snapshot.event_type]}</strong><span>{snapshot.home_score} – {snapshot.away_score}</span><time dateTime={snapshot.updated_at}>{new Intl.DateTimeFormat('es-CO', { timeStyle: 'medium', timeZone: 'America/Bogota' }).format(new Date(snapshot.updated_at))}</time></li>)}</ol></section>}
        <footer className="page-footer">Los snapshots son la fuente del marcador. La recuperación depende del estado disponible en el servidor.</footer>
      </main>
    </div>
  </div>
}
