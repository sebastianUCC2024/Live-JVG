type Name = 'grid' | 'star' | 'search' | 'arrow' | 'refresh' | 'check' | 'monitor' | 'bolt' | 'ball' | 'cancel' | 'bell' | 'clock' | 'sun' | 'moon'
const paths: Record<Name, string> = {
  sun: 'M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8 M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5L19 19 M5 19l1.5-1.5 M17.5 6.5L19 5',
  moon: 'M20.5 13.5A9 9 0 0 1 10.5 3a9 9 0 1 0 10 10.5Z',
  bolt: 'm13 2-9 12h7l-1 8 10-13h-7l1-7Z',
  ball: 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0 M12 7l5 4-2 6H9l-2-6 5-4Z M12 2v5 M22 12l-5-1 M18 20l-3-3 M6 20l3-3 M2 12l5-1',
  cancel: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M8 8l8 8 M16 8l-8 8',
  bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4',
  clock: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M12 7v5l3 2',
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  star: 'm12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9Z',
  search: 'M21 21l-5-5 M18 10.5a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0',
  arrow: 'M5 12h14 m-6-6 6 6-6 6',
  refresh: 'M20 7v5h-5 M4 17v-5h5 M6 6a8 8 0 0 1 13 3 M18 18a8 8 0 0 1-13-3',
  check: 'm5 12 4 4L19 6',
  monitor: 'M3 4h18v13H3z M8 21h8 M12 17v4',
}
export function Icon({ name }: { name: Name }) {
  return <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>
}
