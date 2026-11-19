import { useEffect, useState, type Dispatch, type SetStateAction } from 'react'
import { createDashboardApi, type CatalogMatch } from '../services/dashboardApi'

/**
 * Loads the existing presentation-round catalog and owns request cancellation.
 * Only ready matches with the established demo round identity participate in automatic
 * selection. This preserves the current presentation behavior, including an empty
 * catalog for generic match IDs. REST recovery and STOMP remain independent of this request.
 */
export function useMatchCatalog(
  url: string,
  manual: boolean,
  roundId: string | null,
  setIds: Dispatch<SetStateAction<string[]>>,
) {
  const [catalog, setCatalog] = useState<CatalogMatch[]>([])
  const [catalogStatus, setCatalogStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [catalogError, setCatalogError] = useState('')
  const [catalogRetry, setCatalogRetry] = useState(0)

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
  }, [url, catalogRetry, manual, roundId, setIds])

  return { catalog, catalogStatus, catalogError, setCatalogRetry }
}
