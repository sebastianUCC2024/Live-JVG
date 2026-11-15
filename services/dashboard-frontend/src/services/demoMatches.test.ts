import { afterEach, describe, expect, it, vi } from 'vitest'
import { demoMatches, loadDemoMatches } from './demoMatches'

afterEach(() => vi.useRealTimers())

describe('Servicio local de demostración', () => {
  it('devuelve una copia independiente de los fixtures', async () => {
    vi.useFakeTimers()
    const request = loadDemoMatches('ready', new AbortController().signal)
    await vi.advanceTimersByTimeAsync(250)
    const data = await request
    expect(data).toEqual(demoMatches)
    data[0].snapshot.home_score = 99
    expect(demoMatches[0].snapshot.home_score).toBe(2)
  })
  it('devuelve una jornada vacía sin inventar partidos', async () => {
    vi.useFakeTimers()
    const request = loadDemoMatches('empty', new AbortController().signal)
    await vi.advanceTimersByTimeAsync(250)
    expect(await request).toEqual([])
  })
  it('permite cancelar la carga y elimina su temporizador', async () => {
    vi.useFakeTimers()
    const controller = new AbortController()
    const request = loadDemoMatches('ready', controller.signal)
    const rejected = expect(request).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()
    await rejected
    expect(vi.getTimerCount()).toBe(0)
  })
  it('expone un error controlado para el escenario simulado', async () => {
    vi.useFakeTimers()
    const request = loadDemoMatches('error', new AbortController().signal)
    const rejected = expect(request).rejects.toThrow('escenario de demostración')
    await vi.advanceTimersByTimeAsync(250)
    await rejected
  })
})
