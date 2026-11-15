import { describe, expect, it } from 'vitest'
import { parseMetadata } from './liveConfig'

describe('Metadatos externos opcionales', () => {
  it('conserva nombres disponibles sin asumir campos del snapshot', () => {
    expect(parseMetadata('{"match-001":{"home":"Atlético","away":"Unión","competition":"Liga","home_score":99}}'))
      .toEqual({ 'match-001': { home: 'Atlético', away: 'Unión', competition: 'Liga' } })
  })
  it.each(['malformed', 'null', '[]', '{"match-001":null}'])('gestiona configuración inválida: %s', raw => {
    expect(parseMetadata(raw)).toEqual({})
  })
})
