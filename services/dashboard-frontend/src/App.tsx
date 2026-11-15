import { useState } from 'react'
import { DashboardPage } from './pages/DashboardPage'
import { LiveDashboardPage } from './pages/LiveDashboardPage'

export function App() {
  const [mode, setMode] = useState<'live' | 'demo'>(() => new URLSearchParams(window.location.search).get('mode') === 'demo' ? 'demo' : 'live')
  return <><div className="mode-switch" role="group" aria-label="Fuente del dashboard"><button aria-pressed={mode === 'live'} onClick={() => setMode('live')}>Tiempo real</button><button aria-pressed={mode === 'demo'} onClick={() => setMode('demo')}>Demostración</button></div>{mode === 'live' ? <LiveDashboardPage /> : <DashboardPage />}</>
}
