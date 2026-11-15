export const defaultWebSocketUrl = import.meta.env.VITE_WS_URL
  || `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.hostname}:8081/ws`
export const initialMatchIds: string[] = [...new Set(String(import.meta.env.VITE_MATCH_IDS || 'match-001')
  .split(',').map(id => id.trim()).filter(id => /^[A-Za-z0-9_.-]+$/.test(id)))].slice(0, 20)

export interface MatchMetadata { home?: string; away?: string; competition?: string }
export function parseMetadata(raw: string | undefined): Record<string, MatchMetadata> {
  try {
    const value: unknown = JSON.parse(raw || '{}')
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    return Object.fromEntries(Object.entries(value).filter(([, item]) => item && typeof item === 'object')
      .map(([id, item]) => [id, Object.fromEntries(Object.entries(item as object)
        .filter(([key, name]) => ['home', 'away', 'competition'].includes(key) && typeof name === 'string' && name.trim()))]))
  } catch { return {} }
}
export const matchMetadata = parseMetadata(import.meta.env.VITE_MATCH_METADATA)
