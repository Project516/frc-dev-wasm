// The WPILib VS Code extension (built by packages/vscode-wpilib), the one of the open project's season.
// It runs in the page's extension host, and calls the IDE through frcDevWasmWpilib for what it would
// start processes for: Gradle tasks, the robot program and the tools.
import type { WpilibHost } from '@frc-dev-wasm/vscode-wpilib/host'
import { DEFAULT_YEAR, WPILIB_YEARS, gradleRioYear } from '@frc-dev-wasm/runtime'
import { registerExtension, type IExtensionManifest } from '@codingame/monaco-vscode-api/extensions'
import { ExtensionHostKind } from '@codingame/monaco-vscode-extensions-service-override'
import { CustomSchemas } from '@codingame/monaco-vscode-files-service-override'
import { FileAccess } from '@codingame/monaco-vscode-api/vscode/vs/base/common/network'
import { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri'
import type * as vscode from 'vscode'
import type { TerminalGit } from '../git/controller'
import { readFolderFile, workspaceFolder } from '../workspace'
import type { FrcHost, FrcServices } from './services'
import { extensionYear, seasonChoices, startCreator, takeHandoff, type Seasons } from './wpilibYear'

const SEASONS: Seasons = { years: WPILIB_YEARS, fallback: DEFAULT_YEAR }

export interface VscodeWpilib {
  year: string
  // Where the site serves the extension folder (ends with a slash)
  base: URL
  manifest: IExtensionManifest
  files: string[]
  // Whether Create a New Project switched seasons, and so opens its project creator once the extension is ready
  openCreator: boolean
}

// Fetches the manifest and file list of the extension for the folder's season, or undefined when it is
// not built. The workbench draws the activity bar of the last session before the extension is registered,
// with icons at the extension's own URIs (extension-file://<id>/extension/<file>, where registerExtension
// puts it), so those are mapped to the site before the workbench starts.
export async function loadVscodeWpilib(folder: vscode.Uri): Promise<VscodeWpilib | undefined> {
  const gradle = await readFolderFile(folder, 'build.gradle')
  const handoff = takeHandoff(localStorage)
  const year = extensionYear(SEASONS, gradle && gradleRioYear(new TextDecoder().decode(gradle)), handoff)
  const base = new URL(`${import.meta.env.BASE_URL}runtime/${year}/vscode-wpilib/`, window.location.href)
  const [manifest, files] = await Promise.all([
    fetch(new URL('package.json', base)).then((r) => (r.ok ? (r.json() as Promise<IExtensionManifest>) : undefined)),
    fetch(new URL('files.json', base)).then((r) => (r.ok ? (r.json() as Promise<string[]>) : undefined))
  ]).catch(() => [])
  if (!manifest || !files) return undefined
  const authority = `${manifest.publisher}.${manifest.name}`
  for (const file of files) {
    FileAccess.registerStaticBrowserUri(URI.from({ scheme: CustomSchemas.extensionFile, authority, path: `/extension/${file}` }), URI.parse(new URL(file, base).href))
  }
  return { year, base, manifest, files, openCreator: handoff === year }
}

// Direct when the server allows it, else through the vendor proxy's maven route
async function proxiedFetch(api: typeof vscode, url: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init)
  } catch (error) {
    const proxy = api.workspace.getConfiguration('wpilib').get<string>('vendorProxy')?.trim().replace(/\/$/, '')
    const method = init?.method ?? 'GET'
    if (!proxy || !url.startsWith('https://') || (method !== 'GET' && method !== 'HEAD')) throw error
    return fetch(`${proxy}/maven/${url.slice('https://'.length)}`, init)
  }
}

interface RunningTask {
  name: string
  terminal: vscode.Terminal
}

export function registerVscodeWpilib({ year, base, manifest, files, openCreator }: VscodeWpilib, host: FrcHost, services: FrcServices, git: TerminalGit): void {
  const api = host.vscode

  const running = new Set<RunningTask>()
  // A finished task's terminal is reused by the next run of the same task, as VS Code does
  const finished = new Map<string, vscode.Terminal>()

  // The creator the last load was reloaded for asks nothing more
  let seasonChosen = openCreator

  const wpilibHost: WpilibHost = {
    extensionBase: base.href,
    extensionFiles: files,
    projectsFolder: workspaceFolder.path,
    fetch: (url, init) => proxiedFetch(api, url, init),
    async startCreator() {
      if (seasonChosen) {
        seasonChosen = false
        return true
      }
      const pick = async (years: string[]) => {
        const choices = await seasonChoices(years, new URL('../../', base))
        return (
          await api.window.showQuickPick(
            choices.map((c) => ({ ...c, description: [c.description, c.year === year ? 'loaded' : undefined].filter(Boolean).join(', ') })),
            // The creator's webview opens this pick and takes focus back, which would dismiss it
            { title: 'Create a New Project', placeHolder: 'WPILib year', ignoreFocusOut: true }
          )
        )?.year
      }
      return startCreator(SEASONS, year, pick, localStorage, () => location.reload())
    },
    async execute(command, name, cwd, env) {
      finished.get(name)?.dispose()
      finished.delete(name)
      const { createTaskPty } = await import('./terminal/pty')
      return new Promise<number>((resolve) => {
        const task: RunningTask = { name, terminal: undefined! }
        const pty = createTaskPty({
          api,
          services,
          git,
          cwd,
          command,
          env,
          onExit(code) {
            running.delete(task)
            finished.set(name, task.terminal)
            resolve(code)
          }
        })
        task.terminal = api.window.createTerminal({ name, pty, iconPath: new api.ThemeIcon('tools') })
        running.add(task)
        task.terminal.show()
      })
    },
    async cancel() {
      if (running.size === 0) return 0
      const picked = await api.window.showQuickPick(
        [...running].map((task) => ({ label: task.name, task })),
        { canPickMany: true, placeHolder: 'Select tasks to cancel' }
      )
      for (const { task } of picked ?? []) task.terminal.dispose()
      return picked?.length ?? 0
    },
    async simulate({ cwd, extensions }) {
      host.showTerminal()
      const gui = extensions.some((lib) => /(^|\/)(lib)?halsim_gui(\.\w+)?$/.test(lib))
      if (!(await services.simulate({ root: cwd, gui, reuseBuild: true }))) throw new Error('The robot program did not start. See the WPILib terminal.')
    },
    tools: services.tools.map((tool) => tool.label),
    async startTool(name) {
      const tool = services.tools.find((t) => t.label === name)
      if (!tool) return false
      await services.startTool(tool.id)
      return true
    }
  }
  Object.assign(globalThis, { frcDevWasmWpilib: Promise.resolve(wpilibHost) })
  const extension = registerExtension(manifest, ExtensionHostKind.LocalProcess, { system: true })
  for (const file of files) extension.registerFileUrl(file, new URL(file, base).href)
  if (openCreator) {
    void extension
      .whenReady()
      .then(() => api.commands.executeCommand('wpilibcore.createNewProject'))
      .finally(() => (seasonChosen = false))
  }
}
