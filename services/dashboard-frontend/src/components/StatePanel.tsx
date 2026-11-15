import { Icon } from './Icon'

export function StatePanel({ kind, onRetry }: { kind: 'loading' | 'empty' | 'error' | 'filtered'; onRetry: () => void }) {
  const content = {
    loading: ['Preparando los partidos', 'Cargando datos de demostración…'],
    empty: ['No hay partidos disponibles', 'Este escenario simula una jornada sin partidos.'],
    filtered: ['Ningún partido coincide', 'Prueba otra búsqueda o cambia el filtro.'],
    error: ['No pudimos cargar los partidos', 'Es un error simulado. Puedes restablecer la demostración.'],
  }[kind]
  return <div className={`state-panel ${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
    <span className={kind === 'loading' ? 'spin state-icon' : 'state-icon'}><Icon name={kind === 'error' ? 'refresh' : 'grid'} /></span>
    <h2>{content[0]}</h2><p>{content[1]}</p>
    {kind === 'error' && <button className="primary-button" onClick={onRetry}>Restablecer demostración</button>}
  </div>
}
