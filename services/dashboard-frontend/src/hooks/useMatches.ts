import { useEffect, useState } from 'react'
import { loadDemoMatches } from '../services/demoMatches'
import { isMatchSnapshot, type DemoMatch, type DemoScenario, type MatchLoader } from '../types/match'

type State = { status: 'loading' } | { status: 'ready'; matches: DemoMatch[] } | { status: 'error'; message: string }

export function useMatches(scenario: DemoScenario, refresh: number, loader: MatchLoader = loadDemoMatches): State {
  const [state, setState] = useState<State>({ status: 'loading' })
  useEffect(() => {
    const controller = new AbortController()
    setState({ status: 'loading' })
    loader(scenario, controller.signal).then(matches => {
      if (controller.signal.aborted) return
      if (!matches.every(match => isMatchSnapshot(match.snapshot))) throw new Error('invalid snapshot')
      setState({ status: 'ready', matches })
    }).catch(() => {
      if (!controller.signal.aborted) setState({ status: 'error', message: 'No pudimos cargar los partidos de demostración.' })
    })
    return () => controller.abort()
  }, [scenario, refresh, loader])
  return state
}
