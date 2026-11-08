import { registerExtension, type IExtensionManifest } from '@codingame/monaco-vscode-api/extensions'
import { ExtensionHostKind } from '@codingame/monaco-vscode-extensions-service-override'
import type * as vscode from 'vscode'
import gettingStartedUrl from './getting-started.md?url'
import { DEFAULT_YEAR, WPILIB_YEARS } from '@frc-dev-wasm/runtime'
import { cleanStaleCaches, clearCaches, formatBytes } from './cache'
import { requestClearSiteData } from '../siteData'
import { gitContributions } from '../git/manifest'
import { registerGit } from '../git/extension'
import { registerLocalFolder } from './localFolder'
import { createOutput } from './output'
import { openGitHubLink, takeGitHubLink } from './githubLink'
import { prefetchRuntime, whenIdle } from './prefetch'
import { offerStoredProjects, openFolder, openRobotProject, suggestRobotProject } from './projects'
import { NotRobotProject, type FrcHost, type FrcServices, type FrcServicesFactory } from './services'
import { closeSimGui, getSimFrame, openSimGui, WASM_SIM_YEARS, type SimGuiKind } from './simView'
import { createAgentTerminal, registerShell } from './terminal'
import { OPEN_IN_TERMINAL } from './terminal/profile'
import { IMPORT_VENDOR_JARS } from './vendorLibraries'
import { exportProjectAsZip, importProjectFromZip } from './zip'
import { realGitFolder, workspaceFolder } from '../workspace'
import { registerGitHubSessions } from '../git/githubSessions'
import { registerVscodeGit, vscodeGitBuilt } from '../git/vscodeGit'
import { registerVscodeWpilib, type VscodeWpilib } from './vscodeWpilib'
import { registerCopilot } from '../copilot/extension'
import { seasonChoices } from './wpilibYear'
import { plainText } from '../plainText'

const CATEGORY = 'WPILib'
const RUNTIME_BASE = new URL(`${import.meta.env.BASE_URL}runtime/`, window.location.href)

// With ownGit unset, VS Code's Git extension contributes the Git commands and views. With ownWpilib
// unset, the WPILib extension contributes project creation, build, simulate, tools and vendor libraries.
function frcManifest(ownGit: boolean, ownWpilib: boolean): IExtensionManifest {
  // Where the walkthrough's commands come from
  const wpilib = ownWpilib ? 'wpilib' : 'wpilibcore'
  return {
    name: 'wpilib',
    publisher: 'frc-dev-wasm',
    displayName: 'WPILib',
    version: '0.0.1',
    engines: { vscode: '*' },
    activationEvents: ['*'],
    contributes: {
      commands: [
        ...(ownWpilib
          ? [
              { command: 'wpilib.showCommands', title: 'Show WPILib Commands', category: CATEGORY, icon: '$(circuit-board)' },
              { command: 'wpilib.createNewProject', title: 'Create a New Project', category: CATEGORY },
              { command: 'wpilib.buildCode', title: 'Build Robot Code', category: CATEGORY },
              { command: 'wpilib.simulateCode', title: 'Simulate Robot Code', category: CATEGORY },
              { command: 'wpilib.testCode', title: 'Test Robot Code', category: CATEGORY },
              { command: 'wpilib.startTool', title: 'Start Tool', category: CATEGORY },
              { command: 'wpilib.manageVendorLibs', title: 'Manage Vendor Libraries', category: CATEGORY }
            ]
          : []),
        { command: 'wpilib.openRobotProject', title: 'Open Robot Project', category: CATEGORY },
        { command: 'wpilib.stopSimulation', title: 'Stop Robot Simulation', category: CATEGORY },
        { command: 'wpilib.openSimGui', title: 'Open Simulation GUI', category: CATEGORY },
        { command: IMPORT_VENDOR_JARS, title: 'Import Vendor Jars from Disk', category: CATEGORY },
        { command: 'wpilib.importZip', title: 'Import Project from Zip', category: CATEGORY },
        { command: 'wpilib.exportZip', title: 'Export Project as Zip', category: CATEGORY },
        { command: 'wpilib.clearCaches', title: 'Clear Caches', category: CATEGORY },
        { command: 'wpilib.clearSiteData', title: 'Clear Site Data', category: CATEGORY },
        { command: 'wpilib.openFolder', title: 'Open Folder...', category: 'File' },
        { command: 'wpilib.closeFolder', title: 'Close Folder', category: 'File' },
        { command: OPEN_IN_TERMINAL, title: 'Open in Integrated Terminal' },
        ...(ownGit ? gitContributions.commands! : [])
      ],
      menus: {
        'editor/title': ownWpilib ? [{ command: 'wpilib.showCommands', group: 'navigation@1' }] : [],
        'explorer/context': [{ command: OPEN_IN_TERMINAL, group: 'navigation@30', when: 'resourceScheme == file' }],
        ...(ownGit ? gitContributions.menus : {}),
        commandPalette: [...((ownGit && gitContributions.menus?.commandPalette) || []), { command: OPEN_IN_TERMINAL, when: 'false' }]
      },
      configuration: [
        // The IDE's own Source Control reads the user settings; VS Code's Git extension uses git config
        ownGit ? gitContributions.configuration : { ...gitContributions.configuration, properties: { 'git.corsProxy': gitContributions.configuration!.properties!['git.corsProxy'] } },
        {
          title: CATEGORY,
          properties: {
            'wpilib.simGui': {
              type: 'string',
              enum: ['wasm', 'classic'],
              enumDescriptions: ['The WPILib Sim GUI (halsim_gui), built to WebAssembly. WPILib 2027 projects use the classic one.', 'The earlier hand-written Sim GUI.'],
              default: 'wasm',
              description: 'Which Simulation GUI to open. Takes effect the next time the GUI is opened.'
            },
            'wpilib.vendorProxy': {
              type: 'string',
              default: 'https://frc-proxy.project516.dev',
              scope: 'application',
              markdownDescription:
                'Proxy for downloading vendor libraries from maven servers that do not allow browser requests, such as CTRE and REV. Vendor jars are fetched directly when possible and through this proxy otherwise. Deploy your own from `packages/proxy`. Empty turns the proxy off, so those libraries can only be imported from disk.'
            }
          }
        }
      ],
      // One project per window: a clone goes into the browser workspace and opens in place
      configurationDefaults: ownGit ? undefined : { 'git.defaultCloneDirectory': '/workspace', 'git.openAfterClone': 'always' },
      colors: ownGit ? gitContributions.colors : undefined,
      viewsWelcome: ownGit ? gitContributions.viewsWelcome : undefined,
      walkthroughs: [
        {
          id: 'wpilib.gettingStarted',
          title: 'Get Started with FRC',
          description: 'Create a robot project, build it, and run it in simulation.',
          steps: [
            {
              id: 'create',
              title: 'Create a project',
              description: `Pick a template and a team number.\n[Create a New Project](command:${wpilib}.createNewProject)`,
              completionEvents: [`onCommand:${wpilib}.createNewProject`],
              media: { markdown: 'media/getting-started.md' }
            },
            {
              id: 'build',
              title: 'Build the robot code',
              description: `Compile the project with javac.\n[Build Robot Code](command:${wpilib}.buildCode)`,
              completionEvents: [`onCommand:${wpilib}.buildCode`],
              media: { markdown: 'media/getting-started.md' }
            },
            {
              id: 'simulate',
              title: 'Simulate the robot',
              description: `Run the robot program and open the simulation GUI.\n[Simulate Robot Code](command:${wpilib}.simulateCode)`,
              completionEvents: [`onCommand:${wpilib}.simulateCode`],
              media: { markdown: 'media/getting-started.md' }
            }
          ]
        }
      ]
    }
  }
}

function simGuiKind(api: typeof vscode, year: string): SimGuiKind {
  const classic = api.workspace.getConfiguration('wpilib').get<SimGuiKind>('simGui') === 'classic'
  return classic || !WASM_SIM_YEARS.includes(year) ? 'classic' : 'wasm'
}

const TEAM_NUMBER_PATTERN = /^\d{1,5}$/

async function createNewProject(api: typeof vscode, services: FrcServices): Promise<void> {
  const title = 'Create a New Project'
  const year =
    WPILIB_YEARS.length > 1
      ? (
          await api.window.showQuickPick(
            await seasonChoices([DEFAULT_YEAR, ...WPILIB_YEARS.filter((y) => y !== DEFAULT_YEAR)], RUNTIME_BASE),
            { title, placeHolder: 'WPILib year' }
          )
        )?.year
      : DEFAULT_YEAR
  if (!year) return
  const kind = await api.window.showQuickPick(
    [
      { label: 'Template', description: 'A starting point with the basic robot structure', projectKind: 'template' as const },
      { label: 'Example', description: 'A complete project that shows how to use a feature', projectKind: 'example' as const }
    ],
    { title, placeHolder: 'Project type' }
  )
  if (!kind) return
  const templates = (await services.listTemplates(year)).filter((t) => t.kind === kind.projectKind)
  const template = await api.window.showQuickPick(
    templates.map((t) => ({ label: t.label, description: t.description, id: t.id })),
    { title, placeHolder: kind.projectKind === 'template' ? 'Project template' : 'Example project', matchOnDescription: true }
  )
  if (!template) return
  const name = await api.window.showInputBox({
    title,
    prompt: 'Project folder name',
    value: 'MyRobot',
    validateInput: async (v) =>
      !/^[\w.-]+$/.test(v)
        ? 'Use letters, digits, dot, dash and underscore'
        : (await api.workspace.fs.stat(api.Uri.file(`${workspaceFolder.path}/${v}`)).then(() => true, () => false))
          ? 'A folder with this name exists'
          : undefined
  })
  if (!name) return
  const team = await api.window.showInputBox({
    title,
    prompt: 'Team number',
    validateInput: (v) => (TEAM_NUMBER_PATTERN.test(v) ? undefined : 'Enter a team number')
  })
  if (!team) return
  const folder = `${workspaceFolder.path}/${name}`
  await services.createProject(year, template.id, folder, Number(team))
  await openFolder(api, folder)
}

async function clearAllCaches(api: typeof vscode, host: FrcHost, services: FrcServices): Promise<void> {
  const choice = await api.window.showWarningMessage(
    'Clear all caches?',
    {
      modal: true,
      detail:
        'Removes build output, Swing tool data and downloaded vendor libraries stored by the FRC tools, and any Cache Storage. Workspace files, Git data and saved credentials are not touched. The next build and tool start may take longer, and vendor libraries are downloaded again.'
    },
    'Clear Caches'
  )
  if (choice !== 'Clear Caches') return
  await services.stop()
  const before = (await navigator.storage.estimate()).usage ?? 0
  const removed = await clearCaches()
  const after = (await navigator.storage.estimate()).usage ?? 0
  const freed = formatBytes(Math.max(0, before - after))
  host.log(
    `Clear Caches: removed ${removed.entries} entries (${formatBytes(removed.bytes)}). ` +
      `Storage in use: ${formatBytes(before)} before, ${formatBytes(after)} after, ${freed} freed.\n`
  )
  const reload = await api.window.showInformationMessage(`Caches cleared, ${freed} freed. Reload to finish.`, 'Reload')
  if (reload === 'Reload') await api.commands.executeCommand('workbench.action.reloadWindow')
}

async function clearSiteData(api: typeof vscode, services: FrcServices): Promise<void> {
  const choice = await api.window.showWarningMessage(
    'Delete all FRC Dev data in this browser?',
    {
      modal: true,
      detail:
        'Deletes every robot project in the workspace, Git data, saved credentials, settings and caches, then reloads. This cannot be undone. Use WPILib: Export Project as Zip or push to Git first to keep a project. Folders opened from disk are not changed. Close other FRC Dev tabs first.'
    },
    'Delete Everything'
  )
  if (choice !== 'Delete Everything') return
  await services.stop()
  requestClearSiteData()
}

async function startTool(api: typeof vscode, services: FrcServices): Promise<void> {
  const tool = await api.window.showQuickPick(
    services.tools.map((t) => ({ label: t.label, id: t.id })),
    { title: 'Start Tool', placeHolder: 'Tool' }
  )
  if (tool) await services.startTool(tool.id)
}

// vscodeWpilib: the WPILib extension, when it is built (loadVscodeWpilib)
export async function registerFrcExtension(createServices: FrcServicesFactory, vscodeWpilib: VscodeWpilib | undefined): Promise<void> {
  const vscodeGit = realGitFolder && (await vscodeGitBuilt())
  const extension = registerExtension(frcManifest(!vscodeGit, !vscodeWpilib), ExtensionHostKind.LocalProcess)
  extension.registerFileUrl('media/getting-started.md', gettingStartedUrl)
  const api = await extension.getApi()

  const output = createOutput(api)
  const host: FrcHost = {
    vscode: api,
    write: output.write,
    log: output.log,
    showTerminal: output.showTerminal,
    showSimGui: (year) => openSimGui(simGuiKind(api, year), year),
    closeSimGui,
    getSimFrame,
    async git(args, cwd) {
      let out = ''
      try {
        const code = await gitHandle.cli(args, { out: (text) => (out += text), err: () => {} }, cwd, new AbortController().signal)
        return { code, out }
      } catch {
        return undefined
      }
    }
  }
  const services = createServices(host)

  const run = (task: () => Promise<unknown>) => async () => {
    try {
      await task()
    } catch (error) {
      if (error instanceof NotRobotProject) {
        host.write(`${error.message}\n`)
        void suggestRobotProject(api, services, error.message)
        return
      }
      host.write(`${error instanceof Error ? error.message : String(error)}\n`)
      void api.window.showErrorMessage(plainText(`WPILib: ${error instanceof Error ? error.message : String(error)}`))
    }
  }

  if (vscodeWpilib) {
    // What the IDE's own prompts open
    api.commands.registerCommand('wpilib.showCommands', () => api.commands.executeCommand('wpilibcore.openCommandPalette'))
    api.commands.registerCommand('wpilib.createNewProject', () => api.commands.executeCommand('wpilibcore.createNewProject'))
  } else {
    api.commands.registerCommand(
      'wpilib.showCommands',
      () => api.commands.executeCommand('workbench.action.quickOpen', '>WPILib: ')
    )
    api.commands.registerCommand('wpilib.createNewProject', run(() => createNewProject(api, services)))
    api.commands.registerCommand('wpilib.buildCode', run(() => {
      host.showTerminal()
      return services.build()
    }))
    api.commands.registerCommand('wpilib.simulateCode', run(() => {
      host.showTerminal()
      return services.simulate()
    }))
    api.commands.registerCommand('wpilib.testCode', run(() => {
      host.showTerminal()
      return services.test()
    }))
    api.commands.registerCommand('wpilib.startTool', run(() => startTool(api, services)))
    api.commands.registerCommand('wpilib.manageVendorLibs', run(() => services.manageVendorLibraries()))
  }
  api.commands.registerCommand('wpilib.openRobotProject', run(() => openRobotProject(api, services)))
  api.commands.registerCommand('wpilib.stopSimulation', run(() => services.stop()))
  api.commands.registerCommand(
    'wpilib.openSimGui',
    run(async () => {
      const year = await services.runtimeYear()
      await openSimGui(simGuiKind(api, year), year)
    })
  )
  api.commands.registerCommand(IMPORT_VENDOR_JARS, run(() => services.importVendorJars()))
  api.commands.registerCommand('wpilib.clearCaches', run(() => clearAllCaches(api, host, services)))
  api.commands.registerCommand('wpilib.clearSiteData', run(() => clearSiteData(api, services)))
  api.commands.registerCommand('wpilib.importZip', run(() => importProjectFromZip(api)))
  api.commands.registerCommand('wpilib.exportZip', run(() => exportProjectAsZip(api)))
  registerLocalFolder(api)
  const gitHandle = registerGit(api, !vscodeGit)
  registerShell(api, services, gitHandle)
  if (vscodeGit) {
    registerGitHubSessions(api, gitHandle)
    void registerVscodeGit((process) => gitHandle.realGit(process))
  }
  if (vscodeWpilib) registerVscodeWpilib(vscodeWpilib, host, services, gitHandle)
  registerCopilot(gitHandle, createAgentTerminal(api, services, gitHandle)).catch((error: unknown) => host.log(`GitHub Copilot did not start: ${String(error)}\n`))

  const status = api.window.createStatusBarItem(api.StatusBarAlignment.Left, 100)
  status.text = '$(circuit-board) WPILib'
  status.tooltip = 'Show WPILib commands'
  status.command = 'wpilib.showCommands'
  status.show()

  const link = takeGitHubLink()
  if (link !== undefined) void openGitHubLink(api, link).catch((error: unknown) => api.window.showErrorMessage(plainText(`Could not open the link: ${String(error)}`)))
  else void offerStoredProjects(api, services).catch(() => undefined)

  // After the runtime download, so the two never compete
  void prefetchRuntime(api, services.runtimeYear()).then(() => {
    void services.prepareVendorLibraries()
    whenIdle(
      () => {
        void services.warmUp().catch(() => undefined)
        void services
          .robotProjects()
          .then((projects) => cleanStaleCaches({ hasRobotProject: projects.length > 0 }))
          .then((removed) => {
            if (removed.entries > 0) host.log(`Removed ${removed.entries} stale cache entries (${formatBytes(removed.bytes)}).\n`)
          })
          .catch(() => undefined)
      },
      30_000
    )
  })
}
