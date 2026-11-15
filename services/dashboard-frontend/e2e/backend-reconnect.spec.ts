import { expect, test } from '@playwright/test'
import { spawn, type ChildProcess } from 'node:child_process'
import { once } from 'node:events'
import { mkdir, appendFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { dirname, resolve } from 'node:path'

test('backend real aislado: desconexión, reinicio y recuperación del navegador', async ({ page }, info) => {
  test.skip(process.env.REAL_BACKEND_RESTART !== '1', 'Requiere Java 17, JAR compilado y un puerto exclusivo')
  test.setTimeout(60000)
  const endpoint = new URL(process.env.VITE_WS_URL || 'ws://127.0.0.1:18081/ws')
  if (!['127.0.0.1', 'localhost'].includes(endpoint.hostname) || endpoint.pathname !== '/ws' || !endpoint.port) {
    throw new Error('La integración exige un endpoint /ws local con puerto explícito')
  }
  const port = Number(endpoint.port)
  // Refuse to use a port occupied by another service. Never terminate pre-existing processes.
  await new Promise<void>((accept, reject) => {
    const probe = createServer()
    probe.once('error', reject)
    probe.listen(port, '127.0.0.1', () => probe.close(() => accept()))
  })
  const log = info.outputPath('backend.log')
  await mkdir(dirname(log), { recursive: true })
  let child: ChildProcess | undefined
  let logWrites = Promise.resolve()
  async function startBackend() {
    child = spawn(process.env.JAVA17_BIN || 'java', ['-jar',
      resolve('../dashboard-backend/target/dashboard-backend-0.0.1-SNAPSHOT.jar'),
      `--server.port=${port}`, '--spring.profiles.active=memory', '--spring.rabbitmq.listener.simple.auto-startup=false',
      '--spring.rabbitmq.dynamic=false', '--management.health.rabbit.enabled=false',
    ], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
    let startupError: Error | undefined
    child.once('error', error => { startupError = error })
    const write = (data: Buffer) => { logWrites = logWrites.then(() => appendFile(log, data)) }
    child.stdout?.on('data', write)
    child.stderr?.on('data', write)
    await expect.poll(async () => {
      if (startupError) throw startupError
      if (child?.exitCode !== null) throw new Error('El backend terminó antes de estar listo; revisar backend.log')
      try { return (await fetch(`http://127.0.0.1:${port}/actuator/health`)).status } catch { return 0 }
    }, { timeout: 25000, intervals: [100, 250, 500] }).toBe(200)
  }
  async function stopBackend() {
    if (child && child.exitCode === null && child.signalCode === null) {
      const exited = once(child, 'exit')
      child.kill()
      await exited
    }
    await logWrites
  }
  try {
    await startBackend()
    await page.goto('/')
    await expect(page.getByText('Conectado', { exact: true })).toBeVisible()
    await expect(page.getByText('Marcador aún no disponible')).toBeVisible()
    await stopBackend()
    await expect(page.getByText('Reconectando', { exact: true })).toBeVisible()
    await expect(page.getByText('Sin actualizar', { exact: true })).toBeVisible()
    await startBackend()
    await expect(page.getByText('Conectado', { exact: true })).toBeVisible({ timeout: 20000 })
    await expect(page.getByText('Marcador aún no disponible')).toBeVisible()
    await expect(page.getByRole('alert')).toHaveCount(0)
    await expect(page.getByLabel('Local 0, Visitante 0', { exact: true })).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
    await page.screenshot({ path: info.outputPath('reconnected.png'), fullPage: true })
  } finally {
    await stopBackend()
    await info.attach('backend-isolated-log', { path: log, contentType: 'text/plain' })
  }
})
