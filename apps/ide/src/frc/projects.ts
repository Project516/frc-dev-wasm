import type * as vscode from 'vscode'
import { workspaceFolder } from '../workspace'
import type { FrcServices } from './services'
import { plainText } from '../plainText'

const OPEN = 'Open Robot Project'
const CREATE = 'Create a New Project'

const basename = (path: string) => path.split('/').filter(Boolean).pop() ?? path

// The window shows one folder, so switching projects reloads the page
export function openFolder(api: typeof vscode, path: string): Thenable<unknown> {
  return api.commands.executeCommand('vscode.openFolder', api.Uri.file(path))
}

export async function openRobotProject(api: typeof vscode, services: FrcServices): Promise<void> {
  const projects = await services.robotProjects()
  if (projects.length === 0) {
    const choice = await api.window.showInformationMessage('There are no robot projects in the browser workspace.', CREATE)
    if (choice) await api.commands.executeCommand('wpilib.createNewProject')
    return
  }
  const open = api.workspace.workspaceFolders?.[0]?.uri.path
  const pick = await api.window.showQuickPick(
    projects.map((path) => ({ label: basename(path), description: path === open ? `${path} (open)` : path, path })),
    { title: OPEN, placeHolder: 'Robot project' }
  )
  if (pick && pick.path !== open) await openFolder(api, pick.path)
}

// For build, simulate and Manage Vendor Libraries when the open folder is not a robot project
export async function suggestRobotProject(api: typeof vscode, services: FrcServices, message: string): Promise<void> {
  const choices = (await services.robotProjects()).length > 0 ? [OPEN, CREATE] : [CREATE]
  const choice = await api.window.showWarningMessage(plainText(`${message} Build and simulate work on the open folder.`), ...choices)
  if (choice) await api.commands.executeCommand(choice === OPEN ? 'wpilib.openRobotProject' : 'wpilib.createNewProject')
}

// A bare /workspace that holds projects has no project to build
export async function offerStoredProjects(api: typeof vscode, services: FrcServices): Promise<void> {
  if (api.workspace.workspaceFolders?.[0]?.uri.path !== workspaceFolder.path) return
  const projects = await services.robotProjects()
  if (projects.length === 0 || projects[0] === workspaceFolder.path) return
  const choice = await api.window.showInformationMessage(
    `The browser workspace holds ${projects.length} robot project${projects.length === 1 ? '' : 's'}. Open one to build and simulate it.`,
    OPEN
  )
  if (choice) await openRobotProject(api, services)
}
