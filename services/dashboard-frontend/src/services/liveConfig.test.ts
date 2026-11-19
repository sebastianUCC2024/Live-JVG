import { describe, expect, it } from 'vitest'
import { parseMetadata, resolveWebSocketUrl } from './liveConfig'

describe('Endpoint por modo de ejecución', () => {
  const page = { protocol: 'http:', hostname: '127.0.0.1' }
  it('preserva memory por defecto y VITE_WS_URL como override explícito', () => {
    expect(resolveWebSocketUrl(undefined, undefined, page)).toBe('ws://127.0.0.1:8081/ws')
    expect(resolveWebSocketUrl('ws://localhost:18080/ws', 'http://localhost:8080', page)).toBe('ws://localhost:18080/ws')
  })
  it.each([['http://localhost:18080', 'ws://localhost:18080/ws'], ['http://localhost:8080', 'ws://localhost:8080/ws'],
    ['https://example.test', 'wss://example.test/ws']])('convierte origen %s sin adivinar puerto', (origin, expected) => {
    expect(resolveWebSocketUrl(undefined, origin, page)).toBe(expected)
  })
  it.each(['ftp://localhost', 'http://user:password@localhost', 'http://localhost/path', 'http://localhost?query=1'])('rechaza origen inválido', origin => {
    expect(() => resolveWebSocketUrl(undefined, origin, page)).toThrow()
  })
})

describe('Metadatos externos opcionales', () => {
  it('conserva nombres disponibles sin asumir campos del snapshot', () => {
    expect(parseMetadata('{"match-001":{"home":"Atlético","away":"Unión","competition":"Liga","home_score":99}}'))
      .toEqual({ 'match-001': { home: 'Atlético', away: 'Unión', competition: 'Liga' } })
  })
  it.each(['malformed', 'null', '[]', '{"match-001":null}'])('gestiona configuración inválida: %s', raw => {
    expect(parseMetadata(raw)).toEqual({})
  })
})
