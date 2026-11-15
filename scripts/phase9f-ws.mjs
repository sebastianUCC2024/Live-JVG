import { readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

export function parseFrames(text) {
  return text.split('\0').filter(s => s.trim()).map(raw => {
    const split = raw.replace(/^\n+/, '').split('\n\n')
    const lines = split.shift().split('\n')
    return { command: lines.shift().trim(), headers: Object.fromEntries(lines.map(line => {
      const n = line.indexOf(':'); return [line.slice(0, n), line.slice(n + 1)]
    })), body: split.join('\n\n') }
  })
}

export function verifySnapshot(snapshot, run, expected) {
  if (snapshot.match_id !== `9f_${run}_match` || !snapshot.stream_id?.startsWith('durable-')
      || !Number.isSafeInteger(snapshot.revision) || snapshot.revision < 1
      || snapshot.stream_id !== expected.stream_id || snapshot.revision < expected.revision) throw new Error('Durable snapshot identity/revision mismatch')
}

export async function execute(runDir, approvalPath) {
  const state = JSON.parse(await readFile(resolve(runDir, 'run.json'), 'utf8'))
  const approval = JSON.parse(await readFile(approvalPath, 'utf8'))
  const raw = await readFile(resolve(runDir, '.env'))
  const hash = createHash('sha256').update(raw).digest('hex')
  if (!/^[a-f0-9]{12}$/.test(state.run_id) || state.project !== `live-jvg-9f-${state.run_id}`
      || hash !== state.config_sha256 || !approval.actions?.includes('F5')
      || ['run_id', 'project', 'context', 'endpoint', 'config_sha256'].some(key => approval[key] !== state[key])) throw new Error('Explicit matching F5 approval required')
  const env = Object.fromEntries(raw.toString('utf8').trim().split('\n').map(line => {
    const n = line.indexOf('='); return [line.slice(0, n), line.slice(n + 1)]
  }))
  const port = Number(env.AUDIT_DASHBOARD_PORT)
  if (!Number.isInteger(port) || port < 1024 || port > 65535 || [8080, 8081, 18080, 18081].includes(port)) throw new Error('Alternate dashboard port required')
  const match = `9f_${state.run_id}_match`
  const response = await fetch(`http://127.0.0.1:${port}/api/matches/${match}/score`, { signal: AbortSignal.timeout(8000) })
  if (!response.ok) throw new Error('A durable committed fixture is required before F5')
  const expected = await response.json()
  return new Promise((accept, reject) => {
    const socket = new WebSocket(`ws://127.0.0.1:${port}/ws`, ['v12.stomp'])
    const timer = setTimeout(() => finish(new Error('Snapshot subscription timed out')), 15000)
    let stopped = false
    const finish = (error, result) => { if (stopped) return; stopped = true; clearTimeout(timer); socket.close(); error ? reject(error) : accept(result) }
    socket.onopen = () => socket.send('CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0')
    socket.onerror = () => finish(new Error('Isolated WebSocket failed'))
    socket.onclose = () => { if (!stopped) finish(new Error('WebSocket closed before recovery')) }
    socket.onmessage = message => {
      try {
        for (const frame of parseFrames(String(message.data))) {
          if (frame.command === 'ERROR') throw new Error('STOMP rejected isolated session')
          if (frame.command === 'CONNECTED') {
            socket.send(`SUBSCRIBE\nid:live\ndestination:/topic/matches/${match}/score\nack:auto\n\n\0`)
            socket.send(`SUBSCRIBE\nid:recovery\ndestination:/app/matches/${match}/snapshot\nack:auto\n\n\0`)
          }
          if (frame.command === 'MESSAGE' && frame.headers.subscription === 'recovery') {
            const reply = JSON.parse(frame.body)
            if (reply.available !== true) throw new Error('Durable snapshot unavailable')
            verifySnapshot(reply.snapshot, state.run_id, expected)
            finish(null, { run_id: state.run_id, stream_id: reply.snapshot.stream_id, revision: reply.snapshot.revision, result: 'PASS' })
          }
        }
      } catch { finish(new Error('Snapshot protocol/identity validation failed')) }
    }
  })
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args = process.argv.slice(2)
  if (!args.includes('--execute') || !args.includes('--run-dir') || !args.includes('--approval')) {
    console.error('Refused: --execute, private --run-dir and reviewed --approval required; no connection opened')
    process.exitCode = 1
  } else {
    try { console.log(JSON.stringify(await execute(args[args.indexOf('--run-dir') + 1], args[args.indexOf('--approval') + 1]))) }
    catch { console.error('Isolated F5 failed; sensitive details withheld'); process.exitCode = 1 }
  }
}
