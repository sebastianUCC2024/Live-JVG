import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { runInNewContext } from 'node:vm'
import { expect, it } from 'vitest'

const script = readFileSync(resolve(process.cwd(), 'public/theme-init.js'), 'utf8')
function boot(preference: string | null, systemLight: boolean, blocked = false) {
  const root = { dataset: {} as Record<string, string>, style: {} as Record<string, string> }
  let meta = ''
  runInNewContext(script, {
    localStorage: { getItem() { if (blocked) throw Error('blocked'); return preference } },
    matchMedia: () => ({ matches: systemLight }),
    document: { documentElement: root, querySelector: () => ({ setAttribute: (_name: string, value: string) => { meta = value } }) },
  })
  return { root, meta }
}
it.each(['light', 'dark'])('aplica %s antes de React, ignorando el sistema cuando hay preferencia explícita', preference => {
  const { root, meta } = boot(preference, preference === 'dark')
  expect(root.dataset.theme).toBe(preference)
  expect(root.style.colorScheme).toBe(preference)
  expect(meta).toBe(preference === 'light' ? '#f3f6fa' : '#080e19')
})
it.each([null, 'auto', 'invalid'])('usa el sistema al arrancar con preferencia %s', preference => {
  expect(boot(preference, true).root.dataset.theme).toBe('light')
  expect(boot(preference, false).root.dataset.theme).toBe('dark')
})
it('no impide cargar la página cuando el almacenamiento está bloqueado', () => {
  expect(boot(null, true, true).root.dataset.theme).toBe('light')
})
