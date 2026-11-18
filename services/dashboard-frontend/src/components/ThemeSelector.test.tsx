import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ThemeSelector } from './ThemeSelector'
import { DashboardPage } from '../pages/DashboardPage'
import { LiveDashboardPage } from '../pages/LiveDashboardPage'
import { demoMatches } from '../services/demoMatches'
import { snapshot, stompHarness } from '../test/stompHarness'

let media: EventTarget & { matches: boolean; addEventListener: ReturnType<typeof vi.fn>; removeEventListener: ReturnType<typeof vi.fn> }
beforeEach(() => {
  const values = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: vi.fn((key: string) => values.get(key) ?? null),
    setItem: vi.fn((key: string, value: string) => { values.set(key, value) }),
    clear: vi.fn(() => values.clear()),
  })
  media = Object.assign(new EventTarget(), { matches: false }) as typeof media
  media.addEventListener = vi.fn(media.addEventListener.bind(media))
  media.removeEventListener = vi.fn(media.removeEventListener.bind(media))
  vi.stubGlobal('matchMedia', vi.fn(() => media))
})
afterEach(() => { vi.restoreAllMocks(); window.localStorage.clear(); vi.unstubAllGlobals(); delete document.documentElement.dataset.theme })

it('selecciona automático inicialmente y sigue cambios del sistema sin recargar', () => {
  const view = render(<ThemeSelector />)
  expect(screen.getByRole('button', { name: 'Tema Automático' })).toHaveAttribute('aria-pressed', 'true')
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
  act(() => { media.matches = true; media.dispatchEvent(new Event('change')) })
  expect(document.documentElement).toHaveAttribute('data-theme', 'light')
  act(() => { media.matches = false; media.dispatchEvent(new Event('change')) })
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
  view.unmount()
  expect(media.removeEventListener).toHaveBeenCalledWith('change', expect.any(Function))
})
it('persiste selección explícita, la recupera y no sigue el sistema en modo manual', async () => {
  const user = userEvent.setup(), view = render(<ThemeSelector />)
  await user.click(screen.getByRole('button', { name: 'Tema Claro' }))
  expect(window.localStorage.getItem('live-jvg.theme')).toBe('light')
  act(() => { media.matches = false; media.dispatchEvent(new Event('change')) })
  expect(document.documentElement).toHaveAttribute('data-theme', 'light')
  view.unmount(); render(<ThemeSelector />)
  expect(screen.getByRole('button', { name: 'Tema Claro' })).toHaveAttribute('aria-pressed', 'true')
  await user.click(screen.getByRole('button', { name: 'Tema Oscuro' }))
  expect(window.localStorage.getItem('live-jvg.theme')).toBe('dark')
  expect(document.documentElement.style.colorScheme).toBe('dark')
  await user.click(screen.getByRole('button', { name: 'Tema Automático' }))
  expect(window.localStorage.getItem('live-jvg.theme')).toBe('auto')
  act(() => { media.matches = true; media.dispatchEvent(new Event('change')) })
  expect(document.documentElement).toHaveAttribute('data-theme', 'light')
})
it('recupera preferencias inválidas y funciona si localStorage está bloqueado', async () => {
  window.localStorage.setItem('live-jvg.theme', 'unknown')
  const view = render(<ThemeSelector />)
  expect(screen.getByRole('button', { name: 'Tema Automático' })).toHaveAttribute('aria-pressed', 'true')
  view.unmount()
  vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  render(<ThemeSelector />)
  await userEvent.setup().click(screen.getByRole('button', { name: 'Tema Claro' }))
  expect(document.documentElement).toHaveAttribute('data-theme', 'light')
})
it('sincroniza cambios de preferencia entre pestañas', () => {
  render(<ThemeSelector />)
  act(() => { window.localStorage.setItem('live-jvg.theme', 'light'); window.dispatchEvent(new StorageEvent('storage', { key: 'live-jvg.theme' })) })
  expect(document.documentElement).toHaveAttribute('data-theme', 'light')
})
it('conserva favoritos, filtros, búsqueda y detalle en demostración al cambiar el tema', async () => {
  const user = userEvent.setup(), loader = vi.fn(async () => structuredClone(demoMatches))
  render(<DashboardPage loader={loader} />)
  const card = await screen.findByRole('article', { name: 'Atlético Capital contra Deportivo Norte' })
  await user.click(within(card).getByRole('button', { name: /Favorito:/ }))
  await user.click(within(screen.getByRole('group', { name: 'Filtrar partidos' })).getByRole('button', { name: 'Favoritos' }))
  await user.type(screen.getByRole('searchbox'), 'Atlético')
  await user.click(screen.getByRole('button', { name: /Ver detalle/ }))
  await user.click(screen.getByRole('button', { name: 'Tema Claro' }))
  expect(screen.getByRole('searchbox')).toHaveValue('Atlético')
  expect(screen.getAllByRole('article')).toHaveLength(1)
  expect(screen.getByRole('region', { name: 'Detalle del partido' })).toBeInTheDocument()
  expect(within(screen.getByRole('group', { name: 'Filtrar partidos' })).getByRole('button', { name: 'Favoritos' })).toHaveAttribute('aria-pressed', 'true')
  expect(loader).toHaveBeenCalledTimes(1)
})
it('conserva marcador, selección, historial y transporte STOMP al cambiar el tema', async () => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
  const harness = stompHarness(), user = userEvent.setup()
  render(<LiveDashboardPage factory={harness.factory} />)
  act(() => { harness.connect(); harness.send('/app/matches/match-001/snapshot', { available: true, snapshot: snapshot({ home_score: 1, event_type: 'GOAL' }) }) })
  await user.click(screen.getByRole('button', { name: /Ver detalle/ }))
  const input = screen.getByLabelText('Partidos a seguir · IDs separados por comas') as HTMLInputElement
  const previousIds = input.value
  await user.click(screen.getByRole('button', { name: 'Tema Claro' }))
  await user.click(screen.getByRole('button', { name: 'Tema Oscuro' }))
  expect(screen.getByText('Conectado')).toBeInTheDocument()
  expect(screen.getByLabelText('Local 1, Visitante 0')).toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Eventos deportivos' })).toBeInTheDocument()
  expect(input).toHaveValue(previousIds)
  expect(harness.transport.deactivate).not.toHaveBeenCalled()
})
