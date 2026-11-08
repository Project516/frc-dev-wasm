import { projectYear, WPILIB_YEARS } from '@frc-dev-wasm/runtime'
import type * as vscode from 'vscode'

// The season the IDE builds the open project with: an older project builds with the oldest season the site serves
export async function projectSeason(api: typeof vscode): Promise<string | undefined> {
  const folder = api.workspace.workspaceFolders?.[0]?.uri
  if (!folder) return undefined
  const read = (path: string) => Promise.resolve(api.workspace.fs.readFile(api.Uri.joinPath(folder, path))).catch(() => undefined)
  const found = await projectYear({ read, list: async () => [] })
  return found && WPILIB_YEARS.includes(found.year) ? found.year : undefined
}
