// VS Code's own Git, Git Base and GitHub extensions (built by packages/vscode-git), on the real git.
// They run in the page's extension host, and the Git extension's child_process shim starts git
// through frcDevWasmGit.
import { registerExtension, type IExtensionManifest } from '@codingame/monaco-vscode-api/extensions'
import { getService, ILifecycleService } from '@codingame/monaco-vscode-api/services'
import { LifecyclePhase } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/lifecycle/common/lifecycle'
import { ExtensionHostKind } from '@codingame/monaco-vscode-extensions-service-override'
import { workspaceFolder } from '../workspace'
import type { GitProcess } from './wasm/client'

const RUNTIME = new URL(`${import.meta.env.BASE_URL}runtime/vscode-git/`, window.location.href)

export const vscodeGitBuilt = (): Promise<boolean> =>
  Promise.all(
    [new URL('git/package.json', RUNTIME), new URL('../git/git.wasm', RUNTIME)].map((url) =>
      fetch(url, { method: 'HEAD' }).then(
        (r) => r.ok,
        () => false
      )
    )
  ).then((ok) => ok.every(Boolean))

// What the child_process shim passes for each git it starts
type SpawnRequest = Omit<GitProcess, 'cwd'> & { cwd?: string }

async function register(name: string): Promise<void> {
  const base = new URL(`${name}/`, RUNTIME)
  const [manifest, files] = await Promise.all([
    fetch(new URL('package.json', base)).then((r) => r.json() as Promise<IExtensionManifest>),
    fetch(new URL('files.json', base)).then((r) => r.json() as Promise<string[]>)
  ])
  const extension = registerExtension(manifest, ExtensionHostKind.LocalProcess, { system: true })
  for (const file of files) extension.registerFileUrl(file, new URL(file, base).href)
  await extension.whenReady()
}

export async function registerVscodeGit(run: (process: GitProcess) => Promise<number>): Promise<void> {
  Object.assign(globalThis, { frcDevWasmGit: { run: (request: SpawnRequest) => run({ ...request, cwd: request.cwd ?? workspaceFolder.path }) } })
  // The workbench reads views welcome content once it has restored, and then gets only the latest
  // registration, so the extensions come after that
  await (await getService(ILifecycleService)).when(LifecyclePhase.Eventually)
  // Git and GitHub depend on Git Base, and dependencies must be in the same extension host
  await register('git-base')
  await register('git')
  await register('github')
}
