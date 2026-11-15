export const eventTypes = ['GOAL', 'VAR_CHECK', 'GOAL_ANNULLED', 'MATCH_CONTINUES'] as const
export type EventType = typeof eventTypes[number]
export type VarStatus = 'CLEAR' | 'IN_PROGRESS'

/** MatchSnapshotResponse from dashboard-backend, not a shared Rabbit schema. */
export interface MatchSnapshot {
  stream_id: string
  revision: number
  match_id: string
  home_score: number
  away_score: number
  event_id: string
  event_type: EventType
  updated_at: string
  var_status: VarStatus
  reference_event_id: string | null
}

export type SnapshotReply =
  | { available: true; snapshot: MatchSnapshot }
  | { available: false; snapshot: null }

/** Demo-only metadata: the backend does not currently supply teams or minutes. */
export interface MatchView {
  snapshot: MatchSnapshot
  home: { name: string; initials: string; color: string }
  away: { name: string; initials: string; color: string }
  competition: string
  minute: number | null
}

export type DemoMatch = MatchView

export type DemoScenario = 'ready' | 'empty' | 'error' | 'loading'
export type MatchLoader = (scenario: DemoScenario, signal: AbortSignal) => Promise<DemoMatch[]>

export function isMatchSnapshot(value: unknown): value is MatchSnapshot {
  if (!value || typeof value !== 'object') return false
  const data = value as Record<string, unknown>
  const identifier = (entry: unknown): entry is string => typeof entry === 'string' && entry.trim().length > 0
  const score = (entry: unknown) => typeof entry === 'number' && Number.isSafeInteger(entry) && entry >= 0
  return identifier(data.stream_id) && identifier(data.match_id) && identifier(data.event_id)
    && score(data.revision) && (data.revision as number) > 0
    && score(data.home_score) && score(data.away_score)
    && eventTypes.includes(data.event_type as EventType)
    && (data.var_status === 'CLEAR' || data.var_status === 'IN_PROGRESS')
    && typeof data.updated_at === 'string'
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(data.updated_at)
    && Number.isFinite(Date.parse(data.updated_at))
    && (data.reference_event_id === null || identifier(data.reference_event_id))
    && (data.event_type !== 'GOAL_ANNULLED' || identifier(data.reference_event_id))
}
