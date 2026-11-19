import { CommandShell } from '../components/CommandShell'
import { EventTimeline } from '../components/EventTimeline'
import { useEffect, useState, type ReactNode } from 'react'
import { useDashboardStream } from '../hooks/useDashboardStream'
import { defaultWebSocketUrl, initialMatchIds, matchMetadata } from '../services/liveConfig'
import type { TransportFactory } from '../services/dashboardStream'
import { MatchCard } from '../components/MatchCard'
import { Icon } from '../components/Icon'
import { createDashboardApi, resolveRestOrigin, type CatalogMatch } from '../services/dashboardApi'
import { mergeRecovery, useMatchRecovery } from '../hooks/useMatchRecovery'
import { NotificationAlerts } from '../components/NotificationAlerts'
import './round-overview.css'

export const eventLabels = { GOAL: 'Gol', VAR_CHECK: 'Revisión VAR', GOAL_ANNULLED: 'Gol anulado', MATCH_CONTINUES: 'El juego continúa', MATCH_FINISHED: 'Finalizado' }
const statusLabels = { connecting: 'Conectando', connected: 'Conectado', reconnecting: 'Reconectando', failed: 'Sin conexión' }

export function LiveDashboardPage({ factory, url = defaultWebSocketUrl, notificationOrigin = import.meta.env.VITE_NOTIFICATION_ORIGIN, modeControls }: { factory?: TransportFactory; url?: string; notificationOrigin?: string; modeControls?: ReactNode }) {
  const [ids, setIds] = useState<string[]>(initialMatchIds)
  const [input, setInput] = useState(initialMatchIds.join(', '))
  const [formError, setFormError] = useState('')
  const [retry, setRetry] = useState(0)
  const [favorites, setFavorites] = useState<string[]>([])
  const [detail, setDetail] = useState<string | null>(null)
  const [focusedMatch, setFocusedMatch] = useState<string | null>(null)
  const state = useDashboardStream(ids, url, retry, factory)
  const recovery = useMatchRecovery(ids, url, state, retry)
  const [catalog, setCatalog] = useState<CatalogMatch[]>([])
  const [catalogStatus, setCatalogStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [catalogError, setCatalogError] = useState('')
  const [catalogRetry, setCatalogRetry] = useState(0)
  const [manual, setManual] = useState(false)
  const [resetKey, setResetKey] = useState('')
  const [showReset, setShowReset] = useState(false)
  const [resetting, setResetting] = useState(false)
  const [resetError, setResetError] = useState('')
  const [roundId, setRoundId] = useState<string | null>(null)

  async function restartSimulation(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const key = String(new FormData(event.currentTarget).get('presentationKey') ?? '').trim()
    if (!/^[0-9a-f]{48}$/.test(key)) {
      setResetError('Pega la clave de presentación completa de 48 caracteres.')
      return
    }
    setResetting(true)
    setResetError('')
    try {
      const response = await fetch(`${resolveRestOrigin(url)}/api/simulator/rounds`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ presentationKey: key }),
      })
      if (!response.ok) throw new Error(response.status === 403 ? 'El servidor rechazó la clave de presentación.' : 'No se pudo iniciar la ronda.')
      const matches: unknown = await response.json()
      if (!Array.isArray(matches) || matches.length !== 6 || !matches.every(id => typeof id === 'string')) throw new Error('La ronda no devolvió partidos.')
      setRoundId(matches[0])
      setDetail(null)
      setManual(false)
      setIds(matches)
      setCatalogRetry(value => value + 1)
    } catch (error) {
      setResetError(error instanceof Error ? error.message : 'No se pudo iniciar la ronda.')
    } finally {
      setResetting(false)
    }
  }
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    let reload: ReturnType<typeof setTimeout> | undefined
    setCatalogStatus('loading')
    void Promise.resolve().then(() => createDashboardApi(url).catalog(controller.signal)).then(matches => {
      const ready = matches.filter(match => match.last_event_id && match.updated_at &&
        /^demo-col-v4-00[1-6]\.[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(match.match_id))
      ready.sort((a, b) => Date.parse(b.updated_at!) - Date.parse(a.updated_at!))
      const suffix = (roundId ?? ready[0]?.match_id)?.split('.')[1]
      matches = ready.filter(match => match.match_id.endsWith('.' + suffix))
        .sort((a, b) => a.match_id.localeCompare(b.match_id))
      if (roundId && matches.length < 6) {
        if (active) reload = setTimeout(() => setCatalogRetry(value => value + 1), 2000)
        if (!matches.length) return
      }
      if (!active) return
      setCatalog(matches); setCatalogStatus('ready')
      if (!manual) setIds(current => {
        const available = matches.map(match => match.match_id)
        if (current.length === 1 && available.includes(current[0])) return current
        if (roundId && current.length > 1) return current
        return available
      })
    }).catch(error => {
      if (active) { setCatalogStatus('error'); setCatalogError(error instanceof Error ? error.message : 'No se pudo consultar el catálogo.') }
    })
    return () => { active = false; clearTimeout(reload); controller.abort() }
  }, [url, catalogRetry, manual, roundId])
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

  const focusId = focusedMatch && ids.includes(focusedMatch) ? focusedMatch : ids[0]
  const focusIds = focusId ? [focusId] : []
  const snapshots = ids.flatMap(id => { const snapshot = mergeRecovery(state.matches[id], recovery.data[id]).snapshot; return snapshot ? [snapshot] : [] })
  const reviewing = snapshots.filter(snapshot => snapshot.var_status === 'IN_PROGRESS').length
  const latest = snapshots.find(snapshot => snapshot.match_id === focusId) ?? null
  return <CommandShell mode="live" modeControls={modeControls} navigation={<>
    <a className="nav-item active" href="#matches"><Icon name="grid" /> Partidos <span>{ids.length}</span></a>
    <a className="nav-item" href="#catalog"><Icon name="search" /> Selección de partidos</a>
    <a className="nav-item" href="#main"><Icon name="monitor" /> Vista general</a>
  </>} status={<span className={`connection-pill ${state.status}`} role="status">{statusLabels[state.status]}</span>}><div className="page-heading"><div><p className="eyebrow">SPORTS COMMAND CENTER</p><h1>Centro de partidos<span>.</span></h1><p className="intro">El marcador confirmado. El contexto de cada decisión.</p></div><div style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap" }}><button className="primary-button" type="button" aria-expanded={showReset} onClick={() => setShowReset(value => !value)}>Reiniciar simulación</button><button className="secondary-button" onClick={() => setRetry(value => value + 1)}><Icon name="refresh" /> Reconectar</button></div></div>
        <div className="source-notice"><Icon name="check" /><p><strong>Fuente del servidor.</strong> Consulta los estados recibidos y su historial disponible. Una conexión activa no implica un partido en curso.</p><span>REST + STOMP</span></div>
        <section className="summary-grid" aria-label="Resumen de los datos disponibles"><div className="summary-item"><span className="summary-icon"><Icon name="grid" /></span><div><p>Partidos seleccionados</p><strong>{ids.length.toString().padStart(2, '0')}</strong></div><span className="summary-caption">En seguimiento</span></div><div className="summary-item"><span className="summary-icon"><Icon name="check" /></span><div><p>Estados disponibles</p><strong>{snapshots.length.toString().padStart(2, '0')}</strong></div><span className="summary-caption">Recibidos del servidor</span></div><div className="summary-item"><span className="summary-icon amber"><Icon name="monitor" /></span><div><p>En revisión VAR</p><strong>{snapshots.length ? reviewing.toString().padStart(2, '0') : '—'}</strong></div><span className="summary-caption">Según estados disponibles</span></div></section>
        {showReset && <section aria-label="Controles de presentación">
          <form className="watch-form" onSubmit={restartSimulation}>
            <label htmlFor="simulation-key">Clave de presentación</label>
            <input id="simulation-key" name="presentationKey" type="password" autoComplete="new-password" spellCheck={false} value={resetKey} onChange={event => setResetKey(event.target.value)} required />
            <button className="secondary-button" type="submit" disabled={resetting}>{resetting ? 'Iniciando ronda…' : 'Confirmar reinicio'}</button>
            {resetError && <p role="alert">{resetError}</p>}
          </form>
        </section>}
        {!resetting && !resetError && roundId && <p role="status">Ronda iniciada en 0–0.</p>}
        <section id="catalog" className="watch-form catalog-picker" aria-label="Catálogo de partidos">
          <label htmlFor="catalog-match">Partidos disponibles</label>
          {catalogStatus === 'loading' && <p role="status">Cargando catálogo…</p>}
          {catalogStatus === 'error' && <div role="alert"><p>{catalogError}</p><p>En modo memory puedes utilizar la selección manual.</p><button className="secondary-button" onClick={() => setCatalogRetry(value => value + 1)}>Reintentar catálogo</button></div>}
          {catalogStatus === 'ready' && !catalog.length && <p>No hay partidos disponibles.</p>}
          {catalogStatus === 'ready' && catalog.length > 0 && <select id="catalog-match" value={manual || ids.length !== 1 ? '' : ids[0]} onChange={event => {
            if (!event.target.value) return
            setManual(false); setDetail(null); setIds([event.target.value])
          }}><option value="" disabled>Selecciona un partido</option>{catalog.map(match => <option key={match.match_id} value={match.match_id}>{match.home_name} — {match.away_name} ({match.match_id})</option>)}</select>}
        </section>
        {catalog.length > 0 && <button className="secondary-button" type="button" onClick={() => { setManual(false); setDetail(null); setIds(catalog.map(match => match.match_id)) }}>Ver toda la ronda</button>}
        <details className="manual-selection" open={manual || catalogStatus !== 'ready'}><summary>Selección avanzada de partidos</summary><form className="watch-form" onSubmit={follow}><label htmlFor="match-ids">Partidos a seguir · IDs separados por comas</label><div><input id="match-ids" value={input} onChange={event => setInput(event.target.value)} placeholder="match-001, match-002" /><button className="primary-button" type="submit">Seguir partidos</button></div><small>Usa los IDs configurados en el servidor. El modo memory no ofrece catálogo ni historial persistido.</small>{formError && <p role="alert">{formError}</p>}</form></details>
        {recovery.loading && ids.length > 0 && <p role="status">Recuperando historial…</p>}
        {recovery.error && ids.length > 0 && <div className="connection-notice"><p>{recovery.error} Las actualizaciones STOMP siguen disponibles.</p><button className="secondary-button" onClick={() => setRetry(value => value + 1)}>Reintentar recuperación</button></div>}
        {state.status !== 'connected' && <div className="connection-notice" role="status">{state.status === 'failed' ? 'Se detuvieron los reintentos automáticos. Usa Reconectar para volver a intentarlo.' : 'Esperando conexión. El último marcador recibido puede estar desactualizado.'}{state.attempts > 0 && ` Intento ${state.attempts} de 8.`}</div>}
        {state.invalidMessages > 0 && <p className="invalid-notice" role="alert">Se ignoraron {state.invalidMessages} mensajes inválidos. El marcador válido se conserva.</p>}
        <section id="matches" className="matches-section" aria-label="Partidos en seguimiento"><div className="section-heading"><div><p className="eyebrow">TU PERSPECTIVA DEL JUEGO</p><h2>El partido, en foco</h2></div><span className="section-index">01 / MARCADORES</span></div>{ids.length > 1 && <div className="round-overview" role="group" aria-label="Marcadores de la ronda">
          {ids.map(id => {
            const snapshot = mergeRecovery(state.matches[id], recovery.data[id]).snapshot
            const match = catalog.find(item => item.match_id === id) ?? recovery.data[id]?.detail
            const template = match ?? catalog.find(item => item.match_id.split('.')[0] === id.split('.')[0])
            const starting = !snapshot && roundId && id.split('.')[1] === roundId.split('.')[1]
            const reviewing = snapshot?.var_status === 'IN_PROGRESS'
            const annulled = snapshot?.event_type === 'GOAL_ANNULLED'
            const home = template?.home_name ?? 'Local'
            const away = template?.away_name ?? 'Visitante'
            return <button key={id} type="button" className="round-score" aria-pressed={id === focusId}
              onClick={() => { setFocusedMatch(id); setDetail(null) }}>
              <span className="round-teams"><strong>{home}</strong><strong>{away}</strong></span>
              <span className="round-result">{snapshot ? `${snapshot.home_score}–${snapshot.away_score}` : starting ? '0–0' : '—'}</span>
              <span className="round-state" data-var={reviewing || annulled}>
                {snapshot?.event_type === 'MATCH_FINISHED' ? 'Finalizado' : reviewing ? 'Revisión VAR' :
                  annulled ? 'Gol anulado' : starting ? 'Iniciando' : snapshot ? 'En juego' : 'Esperando marcador'}
              </span>
            </button>
          })}
        </div>}<div className="matches-grid">{focusIds.map(id => {
          const entry = state.matches[id]
          const snapshot = mergeRecovery(entry, recovery.data[id]).snapshot
          const match = catalog.find(match => match.match_id === id) ?? recovery.data[id]?.detail
          const metadata = { ...matchMetadata[id], ...(match ? { home: match.home_name, away: match.away_name } : {}) }
          return <div key={id} className="watched-match"><div className="match-identifier"><span>Partido destacado</span><span>{state.status !== 'connected' ? 'Sin actualizar' : entry?.recovery === 'pending' ? 'Recuperando estado' : 'Estado recibido'}</span></div>
            {snapshot ? <MatchCard simulated={false} match={{ snapshot,
              home: { name: metadata?.home || 'Local', initials: metadata?.home?.slice(0, 2).toUpperCase() || 'L', color: 'wine' },
              away: { name: metadata?.away || 'Visitante', initials: metadata?.away?.slice(0, 2).toUpperCase() || 'V', color: 'blue' },
              competition: metadata?.competition || 'Simulación Live JVG', minute: null }} favorite={favorites.includes(id)}
              onFavorite={() => setFavorites(current => current.includes(id) ? current.filter(value => value !== id) : [...current, id])}
              onDetails={() => setDetail(current => current === id ? null : id)} />
              : <div className="state-panel"><span className="state-icon"><Icon name="monitor" /></span><h2>{roundId === id ? '0:00 · 0–0' : entry?.recovery === 'unavailable' ? 'Marcador aún no disponible' : 'Esperando marcador'}</h2>{match && <p>{match.home_name} {match.home_score} – {match.away_score} {match.away_name}</p>}<p>{entry?.recovery === 'unavailable' ? 'El servidor todavía no tiene un snapshot de este partido.' : 'El marcador aparecerá cuando el servidor lo confirme.'}</p></div>}
            {snapshot && !metadata?.home && !metadata?.away && <p className="metadata-note">El mensaje no incluye nombres de equipos.</p>}
          </div>
        })}<aside className="match-context" aria-label="Contexto del marcador"><div className="context-header"><Icon name="monitor" /><span>LECTURA DEL PARTIDO</span></div><div className="context-pitch" aria-hidden="true"><i /></div><h3>Cada jugada tiene<br />una historia.</h3><p>Abre el detalle para seguir los eventos recibidos, sus referencias y la evolución del marcador.</p><dl><div><dt>Último evento disponible</dt><dd>{latest ? eventLabels[latest.event_type] : 'Sin estado disponible'}</dd></div><div><dt>Hora de la actualización · Bogotá</dt><dd>{latest ? <time dateTime={latest.updated_at}>{new Intl.DateTimeFormat('es-CO', { timeStyle: 'medium', timeZone: 'America/Bogota' }).format(new Date(latest.updated_at))}</time> : '—'}</dd></div></dl><small>La hora describe la actualización recibida; no determina el orden de los eventos.</small></aside></div></section>
        {detail && selectedDetail?.snapshot && <section id="match-history" className="detail-panel" aria-label="Eventos deportivos"><p className="eyebrow">EVENT TIMELINE · HISTORIAL DISPONIBLE</p><h2>Últimos eventos · {detail}</h2><button className="secondary-button" onClick={() => setDetail(null)}>Cerrar detalle</button><p>Hasta 30 eventos recientes, sin paginación.</p><EventTimeline events={selectedDetail.events} /></section>}
        {!manual && catalogStatus === 'ready' && focusIds.filter(id => catalog.some(match => match.match_id === id)).map(id =>
          <NotificationAlerts key={id} matchId={id} origin={notificationOrigin} />)}
        <footer className="page-footer">Los snapshots son la fuente del marcador. La recuperación depende del estado disponible en el servidor.</footer>
  </CommandShell>
}
