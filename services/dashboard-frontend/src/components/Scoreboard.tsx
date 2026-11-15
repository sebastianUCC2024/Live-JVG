export function Scoreboard({ home, away, homeName, awayName }: { home: number; away: number; homeName: string; awayName: string }) {
  return <div className="scoreboard" aria-label={`${homeName} ${home}, ${awayName} ${away}`}>
    <span>{home}</span><span className="score-divider" aria-hidden="true">:</span><span>{away}</span>
  </div>
}
