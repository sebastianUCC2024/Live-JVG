import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { MatchCard } from './MatchCard'
import { demoMatches } from '../services/demoMatches'

describe('Tarjeta y componentes del marcador', () => {
  it('muestra equipos, marcador accesible y revisión VAR', () => {
    render(<MatchCard match={demoMatches[0]} favorite={false} onFavorite={vi.fn()} onDetails={vi.fn()} />)
    expect(screen.getByLabelText('Atlético Capital 2, Deportivo Norte 1')).toBeInTheDocument()
    expect(screen.getByText('Revisión VAR en curso')).toBeInTheDocument()
    expect(screen.getByText('Escenario simulado')).toBeInTheDocument()
  })
  it('muestra anulación sin volver a restar el marcador recibido', () => {
    render(<MatchCard match={demoMatches[2]} favorite={false} onFavorite={vi.fn()} onDetails={vi.fn()} />)
    expect(screen.getByText('Gol anulado por VAR')).toBeInTheDocument()
    expect(screen.getByLabelText('Real Andino 1, Club del Pacífico 1')).toBeInTheDocument()
  })
  it('invoca favoritos y detalle mediante botones', async () => {
    const favorite = vi.fn(), details = vi.fn()
    const user = userEvent.setup()
    render(<MatchCard match={demoMatches[0]} favorite={true} onFavorite={favorite} onDetails={details} />)
    const card = screen.getByRole('article')
    const button = within(card).getByRole('button', { name: /Favorito:/ })
    expect(button).toHaveAttribute('aria-pressed', 'true')
    await user.click(button)
    await user.click(within(card).getByRole('button', { name: /Ver detalle/ }))
    expect(favorite).toHaveBeenCalledOnce()
    expect(details).toHaveBeenCalledOnce()
  })
})
