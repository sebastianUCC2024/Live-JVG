import { useEffect, useState } from 'react'
import { Icon } from './Icon'

type Preference = 'light' | 'dark' | 'auto'
const storageKey = 'live-jvg.theme'
const isPreference = (value: string | null): value is Preference => value === 'light' || value === 'dark' || value === 'auto'
function savedPreference(): Preference {
  try { const value = window.localStorage.getItem(storageKey); return isPreference(value) ? value : 'auto' }
  catch { return 'auto' }
}

export function ThemeSelector() {
  const [preference, setPreference] = useState<Preference>(savedPreference)
  useEffect(() => {
    const system = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-color-scheme: light)') : undefined
    function apply() {
      const theme = preference === 'auto' ? (system?.matches ? 'light' : 'dark') : preference
      document.documentElement.dataset.theme = theme
      document.documentElement.style.colorScheme = theme
      document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'light' ? '#f3f6fa' : '#080e19')
    }
    apply()
    if (preference === 'auto') system?.addEventListener('change', apply)
    return () => system?.removeEventListener('change', apply)
  }, [preference])
  useEffect(() => {
    function synchronize(event: StorageEvent) {
      if (event.key === storageKey || event.key === null) setPreference(savedPreference())
    }
    window.addEventListener('storage', synchronize)
    return () => window.removeEventListener('storage', synchronize)
  }, [])
  function choose(value: Preference) {
    setPreference(value)
    try { window.localStorage.setItem(storageKey, value) } catch { /* The selected theme still works for this session. */ }
  }
  return <div className="theme-selector" role="group" aria-label="Tema visual">
    {([
      ['light', 'Claro', 'sun'], ['dark', 'Oscuro', 'moon'], ['auto', 'Automático', 'system'],
    ] as const).map(([value, label, icon]) => <button key={value} type="button" aria-pressed={preference === value} aria-label={`Tema ${label}`} title={label} onClick={() => choose(value)}><Icon name={icon} /><span>{label}</span></button>)}
  </div>
}
