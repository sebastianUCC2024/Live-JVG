import { useNotificationAlerts } from '../hooks/useNotificationAlerts'

const labels = { GOAL: 'Gol', VAR_CHECK: 'Revisión VAR', GOAL_ANNULLED: 'Gol anulado', MATCH_CONTINUES: 'El juego continúa' }
export function NotificationAlerts({ matchId, origin }: { matchId: string; origin: string | undefined }) {
  const state = useNotificationAlerts(matchId, origin)
  return <section className="notification-panel" aria-label="Alertas del partido" aria-busy={state.refreshing}>
    <div className="notification-heading"><div><h2>Alertas del partido</h2><p>Partido {matchId}</p></div>
      {state.status !== 'disabled' && <button className="secondary-button" disabled={state.refreshing} onClick={state.retry}>Reintentar alertas</button>}</div>
    {state.status === 'disabled' && <p role={state.error ? 'alert' : undefined}>{state.error ?? 'Alertas desactivadas: el servicio de notificaciones no está configurado.'}</p>}
    {state.status === 'loading' && <p role="status">Cargando alertas…</p>}
    {state.status === 'ready' && !state.items.length && <p role="status">Todavía no hay alertas para este partido.</p>}
    {state.status === 'error' && <div role="alert"><p>{state.error}</p>{state.items.length > 0 && <p>Se conservan las alertas recibidas; pueden estar desactualizadas.</p>}</div>}
    {state.items.length > 0 && <ol className="notification-list">{[...state.items].reverse().map(alert => <li key={alert.alert_id}>
      <span className="notification-type">{labels[alert.alert_type]}</span><h3>{alert.content.title}</h3><p>{alert.content.message}</p>
      <time dateTime={alert.created_at}>{new Intl.DateTimeFormat('es-CO', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Bogota' }).format(new Date(alert.created_at))}</time>
    </li>)}</ol>}
  </section>
}
