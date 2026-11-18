import type { ReactNode } from 'react'
import { ThemeSelector } from './ThemeSelector'
import { Icon } from './Icon'

export function CommandShell({ mode, navigation, status, modeControls, children }: {
  mode: 'demo' | 'live'
  navigation: ReactNode
  status?: ReactNode
  modeControls?: ReactNode
  children: ReactNode
}) {
  return <div className={`app-shell ${mode === 'live' ? 'live-shell' : 'demo-shell'}`}>
    <a href="#main" className="skip-link">Saltar al contenido</a>
    <aside className="sidebar">
      <a className="brand" href="#main" aria-label="Live-JVG · inicio"><span className="brand-mark"><Icon name="bolt" /></span><span>LIVE<span className="brand-slash">/</span>JVG<span className="brand-sub">SPORTS COMMAND CENTER</span></span></a>
      <div className="sidebar-label">EL JUEGO, BAJO OTRA MIRADA</div>
      <nav aria-label="Vistas del dashboard">{navigation}</nav>
      <div className="sidebar-note"><div className="mini-pitch" aria-hidden="true"><i /></div><span className="eyebrow">PERSPECTIVA TOTAL</span><strong>Cada decisión<br />cambia el juego.</strong><p>El marcador y su contexto.<br />Una sola perspectiva.</p></div>
      <div className="sidebar-footer"><span className="workspace-symbol"><Icon name="grid" /></span><div>Live-JVG<small>{mode === 'demo' ? 'Exploración simulada' : 'Consulta del servidor'}</small></div><span className="workspace-dot" aria-hidden="true" /></div>
    </aside>
    <div className="workspace">{modeControls}
      <header className="topbar"><div className="breadcrumb"><Icon name="grid" /><span>Workspace</span><span className="slash">/</span><strong>Centro de partidos</strong></div><div className="topbar-right"><ThemeSelector />{status}<span className={`source-badge ${mode}`}>{mode === 'demo' ? 'DEMO · DATOS SIMULADOS' : 'DATOS DEL SERVIDOR'}</span></div></header>
      <main id="main" tabIndex={-1}>{children}</main>
    </div>
  </div>
}
