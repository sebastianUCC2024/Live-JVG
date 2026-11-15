import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'
import { demoMatches } from '../services/demoMatches'
import type { MatchLoader } from '../types/match'

const ready: MatchLoader = async () => structuredClone(demoMatches)

describe('Dashboard de demostración', () => {
  it('identifica los datos simulados y permite buscar y filtrar VAR', async () => {
    const user = userEvent.setup()
    render(<DashboardPage loader={ready} />)
    expect(screen.getByText(/No hay conexión a datos en vivo/)).toBeInTheDocument()
    expect(await screen.findAllByRole('article')).toHaveLength(4)
    await user.click(screen.getByRole('button', { name: /En revisión VAR/ }))
    expect(screen.getAllByRole('article')).toHaveLength(1)
    await user.type(screen.getByRole('searchbox'), 'inexistente')
    expect(screen.getByText('Ningún partido coincide')).toBeInTheDocument()
  })
  it('guarda favoritos de la sesión y abre el detalle correcto', async () => {
    const user = userEvent.setup()
    render(<DashboardPage loader={ready} />)
    const card = await screen.findByRole('article', { name: 'Atlético Capital contra Deportivo Norte' })
    await user.click(within(card).getByRole('button', { name: /Favorito:/ }))
    await user.click(within(screen.getByRole('group', { name: 'Filtrar partidos' })).getByRole('button', { name: 'Favoritos' }))
    expect(screen.getAllByRole('article')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: /Ver detalle/ }))
    expect(screen.getByRole('region', { name: 'Detalle del partido' })).toHaveTextContent('Revisión VAR')
    await user.click(screen.getByRole('button', { name: 'Cerrar detalle' }))
    expect(screen.queryByRole('region', { name: 'Detalle del partido' })).not.toBeInTheDocument()
  })
  it('presenta carga sin inventar marcadores', () => {
    const pending: MatchLoader = () => new Promise(() => {})
    render(<DashboardPage loader={pending} />)
    expect(screen.getByText('Preparando los partidos')).toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })
  it('muestra el estado vacío', async () => {
    render(<DashboardPage loader={async () => []} />)
    expect(await screen.findByText('No hay partidos disponibles')).toBeInTheDocument()
  })
  it('permite recuperarse del error simulado', async () => {
    const user = userEvent.setup()
    const loader = vi.fn<MatchLoader>().mockRejectedValueOnce(new Error('simulado')).mockResolvedValue(demoMatches)
    render(<DashboardPage loader={loader} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar los partidos')
    await user.click(screen.getByRole('button', { name: 'Restablecer demostración' }))
    expect(await screen.findAllByRole('article')).toHaveLength(4)
  })
  it('cancela una carga anterior al cambiar de escenario', async () => {
    const user = userEvent.setup()
    const signals: AbortSignal[] = []
    const loader: MatchLoader = async (scenario, signal) => { signals.push(signal); return scenario === 'empty' ? [] : demoMatches }
    render(<DashboardPage loader={loader} />)
    await screen.findAllByRole('article')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Escenario de demostración' }), 'empty')
    await waitFor(() => expect(signals[0].aborted).toBe(true))
    expect(await screen.findByText('No hay partidos disponibles')).toBeInTheDocument()
  })
})
