import { expect, test } from '@playwright/test'

test('dashboard responsive sin conexiones al backend', async ({ page }) => {
  const sockets: string[] = []
  const errors: string[] = []
  page.on('websocket', socket => { if (!socket.url().includes('vite')) sockets.push(socket.url()) })
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/?mode=demo')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Centro de partidos.')
  await expect(page.getByRole('article')).toHaveCount(4)
  await expect(page.getByText('DEMO · DATOS SIMULADOS')).toBeVisible()
  await expect(page.getByRole('article').first()).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
  expect(overflow).toBe(false)
  await page.screenshot({ path: `test-results/dashboard-${test.info().project.name}.png`, fullPage: true })
  await page.getByRole('button', { name: /En revisión VAR/ }).click()
  await expect(page.getByRole('article')).toHaveCount(1)
  await page.getByRole('combobox', { name: 'Escenario de demostración' }).selectOption('error')
  await expect(page.getByRole('alert')).toBeVisible()
  await page.getByRole('button', { name: 'Restablecer demostración' }).click()
  await expect(page.getByRole('article')).toHaveCount(1)
  expect(errors).toEqual([])
  // The Vite development server may open its own HMR socket on port 5173.
  expect(sockets.filter(url => !url.startsWith('ws://127.0.0.1:5173/'))).toEqual([])
})
