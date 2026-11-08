import type * as vscode from 'vscode'
import type { Controller, TerminalGit } from './controller'
import { findRepositories } from './discover'
import { gitCommandIds } from './manifest'
import { plainText } from '../plainText'

// Commands are registered up front. The Git code and isomorphic-git load when one is first used,
// or at startup when the workspace already contains a repository. With `scm` unset, VS Code's Git
// extension provides the commands and Source Control, and this only runs git for the terminal and it.
export function registerGit(api: typeof vscode, scm: boolean): TerminalGit {
  let controller: Promise<Controller> | undefined
  const load = () => (controller ??= import('./controller').then((m) => m.createController(api, scm)))
  if (!scm) return terminalGit(load, () => (controller = undefined))

  for (const id of gitCommandIds) {
    api.commands.registerCommand(id, async (...args: unknown[]) => {
      try {
        await (await load()).run(id, args)
      } catch (error) {
        controller = undefined
        void api.window.showErrorMessage(plainText(`Git: ${error instanceof Error ? error.message : String(error)}`))
      }
    })
  }

  void findRepositories(api).then(async (found) => {
    if (found.length > 0) await (await load()).discover()
  })

  return terminalGit(load, () => (controller = undefined))
}

function terminalGit(load: () => Promise<Controller>, reset: () => void): TerminalGit {
  const use = async <T>(task: (c: Controller) => Promise<T>): Promise<T> => {
    try {
      return await task(await load())
    } catch (error) {
      reset()
      throw error
    }
  }
  return {
    cli: (args, io, cwd, signal) => use((c) => c.cli(args, io, cwd, signal)),
    realGit: (process) => use((c) => c.realGit(process)),
    token: (prompt) => use((c) => c.token(prompt)),
    login: (token) => use((c) => c.login(token)),
    logout: () => use((c) => c.logout())
  }
}
