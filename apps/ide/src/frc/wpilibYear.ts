// Which season's WPILib extension a window loads, and the hand-off that opens another season's
// project creator. Free of VS Code and DOM imports so tests/wpilibYear.mjs runs it under Node.

export interface Seasons {
  // The seasons the site serves, oldest first
  years: readonly string[]
  // The season for a window without a robot project
  fallback: string
}

// Set to a season by Create a New Project when the loaded extension is another one's. The next load
// takes it, so that season's extension loads and its project creator opens.
export const CREATOR_YEAR_KEY = 'frc-dev-wasm.creatorYear'

// The hand-off wins, then the season of the open project's GradleRIO plugin. A season the site lacks
// gives the fallback.
export function extensionYear({ years, fallback }: Seasons, projectYear: string | undefined, handoff: string | null): string {
  for (const year of [handoff, projectYear]) if (year && years.includes(year)) return year
  return fallback
}

export function takeHandoff(storage: Pick<Storage, 'getItem' | 'removeItem'>): string | null {
  const year = storage.getItem(CREATOR_YEAR_KEY)
  storage.removeItem(CREATOR_YEAR_KEY)
  return year
}

export interface SeasonChoice {
  label: string
  description?: string
  year: string
}

// A pre-release season reads "WPILib 2027 (alpha)" with its version beside it; a stable one is plain.
export function seasonChoice(year: string, version: string | undefined): SeasonChoice {
  const stage = version?.match(/-([a-z]+)/i)?.[1].toLowerCase()
  return stage ? { label: `WPILib ${year} (${stage})`, description: version, year } : { label: `WPILib ${year}`, year }
}

// `runtime` is the folder that holds each season's manifest.json, which names its WPILib version
export function seasonChoices(years: readonly string[], runtime: URL): Promise<SeasonChoice[]> {
  return Promise.all(
    years.map(async (year) => {
      const manifest = await fetch(new URL(`${year}/manifest.json`, runtime)).then(
        (r) => (r.ok ? (r.json() as Promise<{ wpilib?: string }>).catch(() => undefined) : undefined),
        () => undefined
      )
      return seasonChoice(year, manifest?.wpilib)
    })
  )
}

// Asks which season to create a project for, when the site serves more than one. Resolves to true when
// the loaded extension's creator should open. For another season it saves the hand-off and reloads, and
// resolves to false, as it does when the question is dismissed.
export async function startCreator(
  { years, fallback }: Seasons,
  loaded: string,
  pick: (years: string[]) => Promise<string | undefined>,
  storage: Pick<Storage, 'setItem'>,
  reload: () => void
): Promise<boolean> {
  if (years.length < 2) return true
  const year = await pick([fallback, ...years.filter((y) => y !== fallback)])
  if (!year) return false
  if (year === loaded) return true
  storage.setItem(CREATOR_YEAR_KEY, year)
  reload()
  return false
}
