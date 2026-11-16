import { useEffect, useState } from 'react'
import { createDashboardApi, loadMatchRecovery, type MatchRecovery, type ScoreEvent } from '../services/dashboardApi'
import type { StreamState, WatchedMatch } from '../services/dashboardStream'

interface RecoveryState { data: Record<string, MatchRecovery>; error: string; loading: boolean; key: string }

export function mergeRecovery(entry: WatchedMatch | undefined, rest: MatchRecovery | undefined) {
  const live = entry?.snapshot
  // An explicit empty STOMP recovery supersedes an older REST response.
  if (entry?.recovery === 'unavailable') return { snapshot: null, events: [] as ScoreEvent[] }
  const compatible = rest?.snapshot && (!live || rest.snapshot.stream_id === live.stream_id)
  const snapshot = compatible && (!live || rest.snapshot!.revision > live.revision) ? rest.snapshot : live ?? null
  const events: ScoreEvent[] = []
  const seen = new Set<string>()
  const liveEvents = entry?.events ?? []
  // REST order is inbox sequence descending, never timestamp order.
  const candidates = compatible ? [
    ...liveEvents.filter(event => event.revision > rest.snapshot!.revision), ...rest.detail.events,
  ] : liveEvents
  for (const event of candidates) if (!seen.has(event.event_id)) { seen.add(event.event_id); events.push(event) }
  return { snapshot, events: events.slice(0, 30) }
}

export function useMatchRecovery(ids: string[], url: string, stream: StreamState, retry: number) {
  const key = JSON.stringify([url, ids])
  const connected = stream.status === 'connected'
  const epochs = ids.map(id => stream.matches[id]?.snapshot?.stream_id ?? '').join(',')
  const [state, setState] = useState<RecoveryState>({ data: {}, error: '', loading: true, key })
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setState(previous => ({ data: previous.key === key ? previous.data : {}, error: '', loading: true, key }))
    void Promise.resolve().then(() => {
      const api = createDashboardApi(url)
      return Promise.all(ids.map(async id => [id, await loadMatchRecovery(api, id, controller.signal)] as const))
    })
      .then(entries => { if (active) setState({ data: Object.fromEntries(entries), error: '', loading: false, key }) })
      .catch(error => { if (active) setState(previous => ({ data: previous.key === key ? previous.data : {}, error: error instanceof Error ? error.message : 'No se pudo recuperar el historial.', loading: false, key })) })
    return () => { active = false; controller.abort() }
    // Identity and connection changes invalidate all requests from the previous recovery cycle.
    // ids are represented by key to keep dependencies stable.
  }, [key, connected, epochs, retry, url])
  return state.key === key ? state : { data: {}, error: '', loading: true, key }
}
