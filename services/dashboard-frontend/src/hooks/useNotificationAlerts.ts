import { useEffect, useRef, useState } from 'react'
import { getAlertPage, notificationConfig, NotificationApiError } from '../services/notificationApi'
import type { LiveAlert } from '../types/alert'

export const ALERT_POLL_MS = 5000
export const ALERT_DRAIN_MS = 250
const REQUEST_TIMEOUT_MS = 10000
const CURSOR_RESET_COOLDOWN_MS = 30000
interface AlertState { key: string; items: LiveAlert[]; status: 'disabled' | 'loading' | 'ready' | 'error'; refreshing: boolean; error: string | null }
interface Session { key: string; items: Map<string, LiveAlert>; cursor?: string; recovering: boolean; lastReset: number | null; errorStatus?: number; drain: Set<string> }

export function useNotificationAlerts(matchId: string | null, rawOrigin: string | undefined) {
  const config = notificationConfig(rawOrigin)
  const origin = config.origin
  const key = JSON.stringify([origin, matchId])
  const enabled = !!origin && !!matchId
  const [retry, setRetry] = useState(0)
  const sessionRef = useRef<Session | null>(null)
  const [state, setState] = useState<AlertState>({ key, items: [], status: enabled ? 'loading' : 'disabled', refreshing: false, error: null })
  useEffect(() => {
    if (!enabled || !origin || !matchId) { sessionRef.current = null; return }
    if (sessionRef.current?.key !== key) sessionRef.current = { key, items: new Map(), recovering: false, lastReset: null, drain: new Set() }
    const session = sessionRef.current
    if (session.errorStatus === 400 || session.errorStatus === 409) {
      session.cursor = undefined; session.recovering = true; session.drain.clear(); session.lastReset = Date.now()
    }
    let active = true
    let timer: ReturnType<typeof setTimeout> | undefined
    let controller: AbortController | undefined
    let requestTimeout: ReturnType<typeof setTimeout> | undefined
    const publish = (status: AlertState['status'], error: string | null = null, refreshing = false) => {
      if (active) setState({ key, items: [...session.items.values()], status, error, refreshing })
    }
    const schedule = (delay: number) => { if (active) timer = setTimeout(() => { void poll() }, delay) }
    async function poll() {
      if (!active) return
      controller = new AbortController()
      const currentController = controller
      const timeout = setTimeout(() => currentController.abort(), REQUEST_TIMEOUT_MS)
      requestTimeout = timeout
      const after = session.cursor
      publish(session.items.size ? 'ready' : 'loading', null, true)
      try {
        const page = await getAlertPage(origin!, matchId!, after, currentController.signal)
        if (!active) return
        if (page.has_more && (page.next_cursor === after || session.drain.has(page.next_cursor)))
          throw new NotificationApiError(0, 'El cursor no avanza. Reintenta la consulta de alertas.')
        // A reset replaces the previous epoch only after a valid initial page arrives.
        if (session.recovering) session.items.clear()
        for (const alert of page.items) {
          const identity = alert.alert_id.toLowerCase()
          if (!session.items.has(identity)) session.items.set(identity, alert)
        }
        session.cursor = page.next_cursor; session.recovering = false; session.errorStatus = undefined
        if (page.has_more) session.drain.add(page.next_cursor)
        else session.drain.clear()
        publish('ready')
        schedule(page.has_more ? ALERT_DRAIN_MS : ALERT_POLL_MS)
      } catch (error) {
        if (!active) return
        const apiError = error instanceof NotificationApiError ? error : new NotificationApiError(0, 'No se pudo actualizar las alertas.')
        if (apiError.status === 409 && after !== undefined && !session.recovering
          && (session.lastReset === null || Date.now() - session.lastReset >= CURSOR_RESET_COOLDOWN_MS)) {
          session.cursor = undefined; session.recovering = true; session.drain.clear(); session.lastReset = Date.now()
          publish('loading', null, true); schedule(ALERT_DRAIN_MS)
        } else {
          session.errorStatus = apiError.status
          publish('error', apiError.message) // Stop automatic retries; the user can retry explicitly.
        }
      } finally {
        clearTimeout(timeout)
        if (requestTimeout === timeout) requestTimeout = undefined
        if (controller === currentController) controller = undefined
      }
    }
    publish('loading', null, true)
    void poll()
    return () => { active = false; clearTimeout(timer); clearTimeout(requestTimeout); controller?.abort() }
  }, [key, enabled, origin, matchId, retry])
  const current: AlertState = enabled ? state.key === key ? state : { key, items: [], status: 'loading', refreshing: true, error: null }
    : { key, items: [], status: 'disabled', refreshing: false, error: config.error }
  return { ...current, retry: () => setRetry(value => value + 1) }
}
