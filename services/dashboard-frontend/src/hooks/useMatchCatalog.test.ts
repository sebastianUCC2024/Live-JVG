import { act, renderHook, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, expect, it, vi } from 'vitest'
import { catalogMatch, jsonResponse } from '../test/durableFixtures'
import { useMatchCatalog } from './useMatchCatalog'

const endpoint = 'ws://127.0.0.1:18080/ws'
const round = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const id = (slot: number) => `demo-col-v4-00${slot}.${round}`
afterEach(() => vi.unstubAllGlobals())

function useCatalog(manual = false) {
  const [ids, setIds] = useState(['original'])
  return { ids, ...useMatchCatalog(endpoint, manual, null, setIds) }
}

it('selects ready matches from the latest presentation round in slot order', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([
    catalogMatch(id(2)), catalogMatch(id(1)),
    { ...catalogMatch('demo-col-v4-001.bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'), updated_at: '2026-11-13T00:00:00Z' },
  ])))
  const { result } = renderHook(() => useCatalog())
  await waitFor(() => expect(result.current.catalogStatus).toBe('ready'))
  expect(result.current.ids).toEqual([id(1), id(2)])
})

it('preserves the existing exclusion of generic IDs from automatic presentation selection', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([catalogMatch('match-001')])))
  const { result } = renderHook(() => useCatalog())
  await waitFor(() => expect(result.current.catalogStatus).toBe('ready'))
  expect(result.current.catalog).toEqual([])
  expect(result.current.ids).toEqual([])
})

it('keeps manual selection while refreshing the presentation catalog', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([catalogMatch(id(1))])))
  const { result } = renderHook(() => useCatalog(true))
  await waitFor(() => expect(result.current.catalogStatus).toBe('ready'))
  expect(result.current.ids).toEqual(['original'])
  expect(result.current.catalog).toHaveLength(1)
})

it('aborts the pending HTTP request when unmounted', async () => {
  const fetcher = vi.fn((_url: string, _options: RequestInit) => new Promise<Response>(() => {}))
  vi.stubGlobal('fetch', fetcher)
  const view = renderHook(() => useCatalog())
  await act(async () => {})
  expect(fetcher).toHaveBeenCalledOnce()
  const signal = fetcher.mock.calls[0][1].signal!
  expect(signal.aborted).toBe(false)
  view.unmount()
  expect(signal.aborted).toBe(true)
})
