import { expect, test, type Page, type Route, type TestInfo } from '@playwright/test'
import { alertPage, liveAlert } from '../src/test/alertFixtures'

const catalog = (id: string) => ({ match_id: id, home_team_id: 'home', away_team_id: 'away',
  home_name: 'Atlético Real', away_name: 'Unión Real', home_score: 1, away_score: 0,
  var_status: 'CLEAR', last_event_id: 'goal-1', updated_at: '2026-11-14T20:42:00Z' })
const snapshot = (id: string) => ({ stream_id: 'fixture-stream', revision: 2, match_id: id,
  event_id: 'goal-1', event_type: 'GOAL', home_score: 1, away_score: 0,
  updated_at: '2026-11-14T20:42:00Z', var_status: 'CLEAR', reference_event_id: null })

async function fixtures(page: Page, alerts: (route: Route) => Promise<void>, memory = false) {
  const unexpected: string[] = [], errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.origin === 'http://127.0.0.1:5173') return route.continue()
    if (url.origin === 'http://127.0.0.1:8082' && url.pathname === '/api/alerts') return alerts(route)
    if (url.origin === 'http://127.0.0.1:8081' && url.pathname.startsWith('/api/matches')) {
      if (memory) return route.fulfill({ status: 404, json: {} })
      if (url.pathname === '/api/matches') return route.fulfill({ json: [catalog('match-001'), catalog('match-002')] })
      const id = url.pathname.split('/')[3]
      if (url.pathname.endsWith('/score')) return route.fulfill({ json: snapshot(id) })
      return route.fulfill({ json: { ...catalog(id), events: [snapshot(id)], active_goals: [snapshot(id)] } })
    }
    unexpected.push(url.origin + url.pathname)
    return route.abort()
  })
  await page.routeWebSocket('**/*', socket => {
    const url = new URL(socket.url())
    if (url.origin === 'ws://127.0.0.1:5173') { socket.connectToServer(); return } // local Vite HMR only
    if (url.href !== 'ws://127.0.0.1:8081/ws') { unexpected.push(url.origin + url.pathname); socket.close(); return }
    socket.onMessage(data => {
      const frame = data.toString()
      if (frame.startsWith('CONNECT\n') || frame.startsWith('STOMP\n')) socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      if (frame.startsWith('SUBSCRIBE\n')) {
        const headers = Object.fromEntries(frame.split('\n').slice(1).filter(line => line.includes(':')).map(line => {
          const colon = line.indexOf(':'); return [line.slice(0, colon), line.slice(colon + 1)]
        }))
        if (headers.destination.startsWith('/app/')) {
          const id = headers.destination.split('/')[3]
          socket.send(`MESSAGE\nsubscription:${headers.id}\nmessage-id:fixture-${id}\ndestination:${headers.destination}\n\n${JSON.stringify({ available: true, snapshot: snapshot(id) })}\0`)
        }
      }
    })
  })
  return { unexpected, errors }
}

async function evidence(page: Page, info: TestInfo, name: string) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true })
}

test('carga, alertas extensas, orden, deduplicación y reintento con teclado', async ({ page }, info) => {
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  let calls = 0
  const long = liveAlert(3, { alert_type: 'GOAL_ANNULLED', reference_event_id: 'event-1',
    content: { locale: 'es', title: 'Anulación'.repeat(12), message: '<script>no ejecutar</script>' + 'TextoExtenso'.repeat(38) } })
  const observations = await fixtures(page, async route => {
    calls++; await gate
    await route.fulfill({ json: alertPage([liveAlert(), liveAlert(2, { alert_type: 'VAR_CHECK', var_status: 'IN_PROGRESS',
      content: { locale: 'es', title: 'Revisión VAR', message: 'Revisión en curso.' } }), long, long], 'list') })
  })
  await page.goto('/')
  const region = page.getByRole('region', { name: 'Alertas del partido' })
  await expect(region.getByText('Cargando alertas…')).toBeVisible()
  await expect(region.getByRole('button')).toBeDisabled()
  await evidence(page, info, 'loading')
  release()
  await expect(region.getByRole('listitem')).toHaveCount(3)
  await expect(region.getByRole('listitem').first().getByRole('heading')).toHaveText(long.content.title)
  await expect(region.getByText(long.content.message)).toBeVisible()
  await expect(region.locator('script')).toHaveCount(0)
  await expect(region.locator('time').first()).toHaveAttribute('datetime', long.created_at)
  await expect(page.getByText('Conectado', { exact: true })).toBeVisible()
  await expect(page.getByRole('article')).toContainText('Atlético Real')
  await evidence(page, info, 'long-alerts')
  const retry = region.getByRole('button', { name: 'Reintentar alertas' })
  await page.getByRole('combobox', { name: 'Partidos disponibles' }).focus()
  for (let step = 0; step < 12 && !await retry.evaluate(button => button === document.activeElement); step++)
    await page.keyboard.press('Tab')
  await expect(retry).toBeFocused(); await page.keyboard.press('Enter')
  await expect.poll(() => calls).toBeGreaterThanOrEqual(2)
  await expect(region.getByRole('listitem')).toHaveCount(3)
  expect(observations.unexpected).toEqual([]); expect(observations.errors).toEqual([])
})

test('lista vacía accesible', async ({ page }, info) => {
  const observations = await fixtures(page, route => route.fulfill({ json: alertPage() }))
  await page.goto('/')
  const region = page.getByRole('region', { name: 'Alertas del partido' })
  await expect(region.getByRole('status')).toHaveText('Todavía no hay alertas para este partido.')
  await expect(region.getByRole('listitem')).toHaveCount(0)
  await evidence(page, info, 'empty')
  expect(observations.unexpected).toEqual([]); expect(observations.errors).toEqual([])
})

test('503 sin diagnóstico interno, reintento y recuperación', async ({ page }, info) => {
  let unavailable = true
  const observations = await fixtures(page, route => unavailable
    ? route.fulfill({ status: 503, json: { error: 'private-database-configuration' } })
    : route.fulfill({ json: alertPage([liveAlert()]) }))
  await page.goto('/')
  const region = page.getByRole('region', { name: 'Alertas del partido' })
  await expect(region.getByRole('alert')).toContainText('no está disponible temporalmente')
  await expect(page.getByText('private-database-configuration')).toHaveCount(0)
  await evidence(page, info, 'error')
  unavailable = false
  await region.getByRole('button', { name: 'Reintentar alertas' }).focus()
  await page.keyboard.press('Enter')
  await expect(region.getByRole('listitem')).toHaveCount(1)
  await expect(region.getByRole('alert')).toHaveCount(0)
  await evidence(page, info, 'retry')
  expect(observations.unexpected).toEqual([]); expect(observations.errors).toEqual([])
})

test('páginas incrementales y recuperación 409 sin mezclar épocas', async ({ page }, info) => {
  const cursors: (string | null)[] = []
  let reset = false
  const observations = await fixtures(page, route => {
    const after = new URL(route.request().url()).searchParams.get('after')
    cursors.push(after)
    if (!after && !reset) return route.fulfill({ json: alertPage([liveAlert()], 'a', true) })
    if (after === 'a') return route.fulfill({ json: alertPage([liveAlert(), liveAlert(2)], 'b', true) })
    if (after === 'b') { reset = true; return route.fulfill({ status: 409, json: {} }) }
    return route.fulfill({ json: alertPage([liveAlert(9, { content: { locale: 'es', title: 'Nueva época', message: 'Estado recuperado.' } })], 'new') })
  })
  await page.goto('/')
  const region = page.getByRole('region', { name: 'Alertas del partido' })
  await expect(region.getByRole('heading', { name: 'Nueva época' })).toBeVisible()
  await expect(region.getByRole('listitem')).toHaveCount(1)
  // StrictMode may abort an initial request and issue another without a cursor.
  expect(cursors.filter((cursor, index) => index === 0 || cursor !== cursors[index - 1]).slice(0, 4)).toEqual([null, 'a', 'b', null])
  await evidence(page, info, 'epoch-recovery')
  expect(observations.unexpected).toEqual([]); expect(observations.errors).toEqual([])
})

test('cambio de partido durante solicitud no muestra contenido anterior', async ({ page }, info) => {
  let started = false, release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const observations = await fixtures(page, async route => {
    const id = new URL(route.request().url()).searchParams.get('match_id')!
    if (id === 'match-001') { started = true; await gate }
    await route.fulfill({ json: alertPage([liveAlert(id === 'match-001' ? 1 : 2, { match_id: id,
      content: { locale: 'es', title: `Alerta ${id}`, message: 'Mensaje del partido seleccionado.' } })], id) })
  })
  await page.goto('/')
  await expect.poll(() => started).toBe(true)
  await page.getByRole('combobox', { name: 'Partidos disponibles' }).selectOption('match-002')
  const region = page.getByRole('region', { name: 'Alertas del partido' })
  await expect(region.getByRole('heading', { name: 'Alerta match-002' })).toBeVisible()
  release()
  await expect(region.getByText('Partido match-002', { exact: true })).toBeVisible()
  await expect(region.getByRole('heading', { name: 'Alerta match-001' })).toHaveCount(0)
  await evidence(page, info, 'changed-match')
  expect(observations.unexpected).toEqual([]); expect(observations.errors).toEqual([])
})

test('demo no consulta alertas ni abre transporte del marcador', async ({ page }, info) => {
  let calls = 0
  const observations = await fixtures(page, async route => { calls++; await route.abort() })
  const sockets: string[] = []
  page.on('websocket', socket => { if (!socket.url().startsWith('ws://127.0.0.1:5173/')) sockets.push(socket.url()) })
  await page.goto('/?mode=demo')
  await expect(page.getByText('DEMO · DATOS SIMULADOS')).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(4)
  await expect(page.getByRole('region', { name: 'Alertas del partido' })).toHaveCount(0)
  await evidence(page, info, 'demo')
  expect(calls).toBe(0); expect(sockets).toEqual([])
  expect(observations.unexpected).toEqual([]); expect(observations.errors).toEqual([])
})

test('memory mantiene marcador STOMP sin consultar notificaciones', async ({ page }, info) => {
  let calls = 0
  const observations = await fixtures(page, async route => { calls++; await route.abort() }, true)
  await page.goto('/')
  await expect(page.getByText('En modo memory puedes utilizar la selección manual.')).toBeVisible()
  await expect(page.getByText('Conectado', { exact: true })).toBeVisible()
  await expect(page.getByLabel('Local 1, Visitante 0', { exact: true })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Alertas del partido' })).toHaveCount(0)
  await evidence(page, info, 'memory')
  expect(calls).toBe(0)
  expect(observations.unexpected).toEqual([]); expect(observations.errors).toEqual([])
})
