import { getService, ITerminalProfileService } from '@codingame/monaco-vscode-api/services'
import type * as vscode from 'vscode'
import type { TerminalGit } from '../../git/controller'
import type { FrcServices } from '../services'
import type { AgentShell } from './pty'
import { OPEN_IN_TERMINAL, SHELL_EXTENSION_ID, SHELL_PROFILE_ID, SHELL_PROFILE_TITLE } from './profile'

// The shell is a Pseudoterminal that runs in the page. Its code loads when the first terminal opens.
export function registerShell(api: typeof vscode, services: FrcServices, git: TerminalGit): void {
  const options = async (cwd?: string): Promise<vscode.ExtensionTerminalOptions> => {
    const { createShellPty } = await import('./pty')
    return { name: SHELL_PROFILE_TITLE, pty: createShellPty({ api, services, git, cwd }), iconPath: new api.ThemeIcon('terminal') }
  }

  api.window.registerTerminalProfileProvider(SHELL_PROFILE_ID, {
    async provideTerminalProfile() {
      return new api.TerminalProfile(await options())
    }
  })
  // A manifest `terminal` contribution would make the extension workspace-only, and a browser
  // without a remote disables those. The workbench service lists the profile instead.
  void getService(ITerminalProfileService).then((profiles) =>
    profiles.registerInternalContributedProfile({
      extensionIdentifier: SHELL_EXTENSION_ID,
      id: SHELL_PROFILE_ID,
      title: SHELL_PROFILE_TITLE,
      icon: 'terminal'
    })
  )
  // Without a process backend the stock command asks which profile to use, with one choice
  api.commands.registerCommand('workbench.action.terminal.new', async () => {
    api.window.createTerminal(await options()).show()
  })
  // The stock explorer item needs a process backend, so the explorer gets this one instead
  api.commands.registerCommand(OPEN_IN_TERMINAL, async (uri?: vscode.Uri, selected?: vscode.Uri[]) => {
    const targets = selected?.length ? selected : uri ? [uri] : []
    const dirs = new Set<string>()
    for (const target of targets) {
      const stat = await api.workspace.fs.stat(target)
      dirs.add(stat.type & api.FileType.Directory ? target.path : api.Uri.joinPath(target, '..').path)
    }
    for (const dir of dirs) api.window.createTerminal(await options(dir)).show()
  })
}

export interface AgentTerminal {
  run(command: string, signal: AbortSignal): Promise<{ code: number; output: string }>
}

// The Copilot terminal: created on first use, and again after the user closes it
export function createAgentTerminal(api: typeof vscode, services: FrcServices, git: TerminalGit): AgentTerminal {
  let current: Promise<{ terminal: vscode.Terminal; shell: AgentShell }> | undefined
  api.window.onDidCloseTerminal(async (terminal) => {
    const closed = current
    if (closed && (await closed).terminal === terminal && current === closed) current = undefined
  })
  return {
    async run(command, signal) {
      current ??= import('./pty').then(({ createAgentPty }) => {
        const shell = createAgentPty({ api, services, git })
        return { shell, terminal: api.window.createTerminal({ name: 'Copilot', pty: shell.pty, iconPath: new api.ThemeIcon('copilot') }) }
      })
      const { terminal, shell } = await current
      terminal.show(true)
      return shell.run(command, signal)
    }
  }
}
