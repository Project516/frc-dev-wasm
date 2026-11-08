import type * as vscode from 'vscode'
import { parseGitHubRepo } from '../git/objects'
import { workspaceFolder } from '../workspace'
import { openFolder } from './projects'
import { plainText } from '../plainText'

const PARAM = 'github'

// The repository a link such as ?github=owner/repo names. It leaves the address, so a reload does not ask again.
export function takeGitHubLink(): string | undefined {
  const url = new URL(window.location.href)
  const value = url.searchParams.get(PARAM)
  if (value === null) return undefined
  url.searchParams.delete(PARAM)
  history.replaceState(history.state, '', url)
  return value
}

// Clones the repository of a link and opens it, as vscode.dev/github/owner/repo opens one. It asks first, since the
// IDE trusts every workspace.
export async function openGitHubLink(api: typeof vscode, value: string): Promise<void> {
  const repo = parseGitHubRepo(value)
  if (!repo) {
    void api.window.showErrorMessage(`The link names "${plainText(value)}", which is not a GitHub repository. Links look like ?github=owner/repo.`)
    return
  }
  const name = `${repo.owner}/${repo.repo}`
  const existing = api.Uri.joinPath(workspaceFolder, repo.repo)
  if (await api.workspace.fs.stat(existing).then(() => true, () => false)) {
    const choice = await api.window.showInformationMessage(`The link opens ${name}. A project named ${repo.repo} is already in the browser.`, { modal: true }, 'Open It')
    if (choice) await openFolder(api, existing.path)
    return
  }
  const choice = await api.window.showInformationMessage(`Clone ${name} from GitHub and open it?`, { modal: true, detail: 'A link asked to open this repository.' }, 'Clone')
  if (!choice) return
  // VS Code's Git registers while the workbench starts
  for (let i = 0; i < 60 && !(await api.commands.getCommands(true)).includes('git.clone'); i++) await new Promise((r) => setTimeout(r, 500))
  await api.commands.executeCommand('git.clone', `https://github.com/${name}`, workspaceFolder.path)
}
