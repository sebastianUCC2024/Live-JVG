import { useEffect, useState } from 'react'
import { Icon } from './Icon'

type Preference = 'light' | 'dark'
const storageKey = 'live-jvg.theme'
const isPreference = (value: string | null): value is Preference => value === 'light' || value === 'dark'
function savedPreference(): Preference {
  try {
    const value = window.localStorage.getItem(storageKey)
    if (isPreference(value)) return value
    if (value !== null) window.localStorage.setItem(storageKey, 'dark')
  } catch { /* Fall back safely if storage cannot be read or migrated. */ }
  return 'dark'
}

export function ThemeSelector() {
  const [preference, setPreference] = useState<Preference>(savedPreference)
  useEffect(() => {
    document.documentElement.dataset.theme = preference
    document.documentElement.style.colorScheme = preference
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', preference === 'light' ? '#f3f6fa' : '#080e19')
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
      ['light', 'Claro', 'sun'], ['dark', 'Oscuro', 'moon'],
    ] as const).map(([value, label, icon]) => <button key={value} type="button" aria-pressed={preference === value} aria-label={`Tema ${label}`} title={label} onClick={() => choose(value)}><Icon name={icon} /><span>{label}</span></button>)}
  </div>
}
