import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { runInNewContext } from 'node:vm'
import { expect, it, vi } from 'vitest'

const script = readFileSync(resolve(process.cwd(), 'public/theme-init.js'), 'utf8')
function boot(preference: string | null, systemLight: boolean, blocked = false) {
  const root = { dataset: {} as Record<string, string>, style: {} as Record<string, string> }
  const systemQuery = vi.fn()
  let stored = preference
  let meta = ''
  runInNewContext(script, {
    localStorage: { getItem() { if (blocked) throw Error('blocked'); return stored }, setItem(_key: string, value: string) { stored = value } },
    matchMedia: systemQuery.mockReturnValue({ matches: systemLight }),
    document: { documentElement: root, querySelector: () => ({ setAttribute: (_name: string, value: string) => { meta = value } }) },
  })
  return { root, meta, stored, systemQuery }
}
it.each(['light', 'dark'])('aplica %s antes de React, ignorando el sistema cuando hay preferencia explícita', preference => {
  const { root, meta } = boot(preference, preference === 'dark')
  expect(root.dataset.theme).toBe(preference)
  expect(root.style.colorScheme).toBe(preference)
  expect(meta).toBe(preference === 'light' ? '#f3f6fa' : '#080e19')
})
it.each([null, 'auto', 'automatic', 'system', 'invalid'])('arranca oscuro con preferencia %s, sin consultar el sistema', preference => {
  const result = boot(preference, true)
  expect(result.root.dataset.theme).toBe('dark')
  expect(result.systemQuery).not.toHaveBeenCalled()
  if (preference !== null) expect(result.stored).toBe('dark')
})
it('no impide cargar la página cuando el almacenamiento está bloqueado', () => {
  expect(boot(null, true, true).root.dataset.theme).toBe('dark')
})
