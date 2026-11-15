import { expect, test, type WebSocketRoute } from '@playwright/test'
import type { MatchSnapshot } from '../src/types/match'

const base: MatchSnapshot = { stream_id: 'browser-test-stream', revision: 1, match_id: 'match-001',
  home_score: 0, away_score: 0, event_id: 'initial', event_type: 'MATCH_CONTINUES',
  updated_at: '2026-11-14T20:42:00.079218900Z', var_status: 'CLEAR', reference_event_id: null }

test('cliente STOMP real: goles, VAR, duplicados, mensaje inválido y reconexión', async ({ page }) => {
  // The WebSocket server is simulated; the browser runs the actual @stomp/stompjs client.
  let latest = base
  const connections: { route: WebSocketRoute; subscriptions: Map<string, string> }[] = []
  let sequence = 0
  function send(connection: typeof connections[number], destination: string, body: unknown) {
    const subscription = connection.subscriptions.get(destination)
    if (subscription) connection.route.send(`MESSAGE\nsubscription:${subscription}\nmessage-id:${++sequence}\ndestination:${destination}\ncontent-type:application/json\n\n${JSON.stringify(body)}\0`)
  }
  await page.routeWebSocket(/\/ws$/, route => {
    const connection = { route, subscriptions: new Map<string, string>() }
    connections.push(connection)
    route.onMessage(data => {
      const frame = data.toString()
      const command = frame.split('\n')[0]
      if (command === 'CONNECT' || command === 'STOMP') route.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      if (command === 'SUBSCRIBE') {
        const headers = Object.fromEntries(frame.split('\n').slice(1).filter(line => line.includes(':')).map(line => {
          const colon = line.indexOf(':'); return [line.slice(0, colon), line.slice(colon + 1)]
        }))
        connection.subscriptions.set(headers.destination, headers.id)
        if (headers.destination.startsWith('/app/')) send(connection, headers.destination, { available: true, snapshot: latest })
      }
      if (command === 'DISCONNECT') route.close({ code: 1000 })
    })
  })
  await page.goto('/')
  await expect(page.getByText('Conectado', { exact: true })).toBeVisible()
  await expect(page.getByLabel('Local 0, Visitante 0', { exact: true })).toBeVisible()
  const live = '/topic/matches/match-001/score'
  latest = { ...base, revision: 2, event_id: 'home', event_type: 'GOAL', home_score: 1 }
  send(connections.at(-1)!, live, latest)
  await expect(page.getByLabel('Local 1, Visitante 0', { exact: true })).toBeVisible()
  latest = { ...latest, revision: 3, event_id: 'away', away_score: 1 }
  send(connections.at(-1)!, live, latest)
  await expect(page.getByLabel('Local 1, Visitante 1', { exact: true })).toBeVisible()
  latest = { ...latest, revision: 4, event_id: 'var', event_type: 'VAR_CHECK', var_status: 'IN_PROGRESS' }
  send(connections.at(-1)!, live, latest)
  await expect(page.getByText('Revisión VAR en curso')).toBeVisible()
  latest = { ...latest, revision: 5, event_id: 'annulled', event_type: 'GOAL_ANNULLED',
    home_score: 0, var_status: 'CLEAR', reference_event_id: 'home' }
  send(connections.at(-1)!, live, latest)
  send(connections.at(-1)!, live, latest)
  await expect(page.getByText('Gol anulado por VAR')).toBeVisible()
  await expect(page.getByLabel('Local 0, Visitante 1', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Ver detalle' }).click()
  await expect(page.getByRole('region', { name: 'Eventos deportivos' }).getByRole('listitem')).toHaveCount(5)
  send(connections.at(-1)!, live, {})
  await expect(page.getByRole('alert')).toContainText('Se ignoraron 1 mensajes inválidos')
  await expect(page.getByLabel('Local 0, Visitante 1', { exact: true })).toBeVisible()
  await connections.at(-1)!.route.close({ code: 1012, reason: 'simulated restart' })
  await expect(page.getByText('Reconectando', { exact: true })).toBeVisible()
  latest = { ...latest, revision: 6, event_id: 'continue', event_type: 'MATCH_CONTINUES', away_score: 2, reference_event_id: null }
  await expect(page.getByText('Conectado', { exact: true })).toBeVisible({ timeout: 10000 })
  await expect(page.getByLabel('Local 0, Visitante 2', { exact: true })).toBeVisible()
  expect(connections.length).toBeGreaterThanOrEqual(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  await page.screenshot({ path: `test-results/live-${test.info().project.name}.png`, fullPage: true })
})

test('smoke opcional con dashboard-backend real: handshake y snapshot', async ({ page }) => {
  test.skip(process.env.LIVE_BACKEND_SMOKE !== '1', 'Requiere backend local real, configurado por VITE_WS_URL')
  await page.goto('/')
  await expect(page.getByText('Conectado', { exact: true })).toBeVisible()
  await expect(page.getByText('Marcador aún no disponible')).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})
