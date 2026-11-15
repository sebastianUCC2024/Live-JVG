export function resolveWebSocketUrl(explicit: string | undefined, origin: string | undefined,
  page: { protocol: string; hostname: string }): string {
  if (explicit) return explicit // Preserve the existing explicit override.
  if (origin) {
    const url = new URL(origin)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
      || url.search || url.hash || url.pathname !== '/') throw new Error('VITE_DASHBOARD_ORIGIN must be an HTTP(S) origin')
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    url.pathname = '/ws'
    return url.toString()
  }
  return `${page.protocol === 'https:' ? 'wss' : 'ws'}://${page.hostname}:8081/ws` // Legacy memory default.
}
export const defaultWebSocketUrl = resolveWebSocketUrl(import.meta.env.VITE_WS_URL,
  import.meta.env.VITE_DASHBOARD_ORIGIN, window.location)
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
