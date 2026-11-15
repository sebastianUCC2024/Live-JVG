import { vi } from 'vitest'
import type { IFrame, StompConfig } from '@stomp/stompjs'
import type { TransportFactory } from '../services/dashboardStream'
import type { MatchSnapshot } from '../types/match'

export const snapshot = (changes: Partial<MatchSnapshot> = {}): MatchSnapshot => ({
  stream_id: 'stream-1', revision: 1, match_id: 'match-001', home_score: 0, away_score: 0,
  event_id: 'event-1', event_type: 'MATCH_CONTINUES', updated_at: '2026-11-14T20:42:00.079218900Z',
  var_status: 'CLEAR', reference_event_id: null, ...changes,
})

export function stompHarness() {
  let config: StompConfig
  const handlers = new Map<string, (message: { body: string }) => void>()
  const transport = { activate: vi.fn(), deactivate: vi.fn(async () => {}), forceDisconnect: vi.fn(),
    subscribe: vi.fn((destination: string, handler: (message: { body: string }) => void) => {
      handlers.set(destination, handler)
      return { unsubscribe: vi.fn() }
    }) }
  const factory: TransportFactory = options => { config = options; return transport }
  return {
    factory, transport, handlers,
    connect: () => config.onConnect?.({} as IFrame),
    close: () => config.onWebSocketClose?.(new CloseEvent('close')),
    send: (destination: string, data: unknown) => handlers.get(destination)?.({ body: JSON.stringify(data) }),
    raw: (destination: string, body: string) => handlers.get(destination)?.({ body }),
    get config() { return config },
  }
}
