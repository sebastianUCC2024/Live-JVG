import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import { CommandShell } from './CommandShell'

it('distingue demostración y servidor sin usuarios o métricas ficticias', () => {
  const v = render(<CommandShell mode="demo" navigation={<a href="#main">Partidos</a>}><h1>Partidos</h1></CommandShell>)
  expect(screen.getByText('DEMO · DATOS SIMULADOS')).toBeInTheDocument()
  expect(screen.queryByText('DATOS DEL SERVIDOR')).not.toBeInTheDocument()
  v.rerender(<CommandShell mode="live" navigation={<a href="#main">Partidos</a>}><h1>Partidos</h1></CommandShell>)
  expect(screen.getByText('DATOS DEL SERVIDOR')).toBeInTheDocument()
  expect(screen.queryByText('DEMO · DATOS SIMULADOS')).not.toBeInTheDocument()
})
it('ofrece salto al contenido y navegación alcanzables con teclado', async () => {
  render(<CommandShell mode="live" navigation={<a href="#main">Partidos</a>}><h1>Partidos</h1></CommandShell>)
  await userEvent.setup().tab()
  expect(screen.getByRole('link', { name: 'Saltar al contenido' })).toHaveFocus()
  expect(screen.getByRole('main')).toHaveAttribute('tabindex', '-1')
  expect(screen.getByRole('navigation', { name: 'Vistas del dashboard' })).toBeInTheDocument()
})
