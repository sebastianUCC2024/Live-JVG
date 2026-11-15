import { useEffect, useState } from 'react'
import { initialStreamState, startDashboardStream, type TransportFactory } from '../services/dashboardStream'

export function useDashboardStream(ids: string[], url: string, retry = 0, factory?: TransportFactory) {
  const key = ids.join(',')
  const [state, setState] = useState(() => initialStreamState(ids))
  useEffect(() => {
    const matches = key ? key.split(',') : []
    setState(initialStreamState(matches))
    if (!matches.length) return
    return startDashboardStream(matches, url, setState, factory)
  }, [key, url, retry, factory])
  return state
}
