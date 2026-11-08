import {
  DEFAULT_YEAR,
  DeviceState,
  availableVendorDeps,
  JavaCompiler,
  Nt4JvmClient,
  Nt4Server,
  RobotSession,
  SIM_HOST_GLOBAL,
  SwingApp,
  TestRun,
  generateProject,
  isLogFile,
  isRobotProject,
  listProjectTemplates,
  loadRobotProject,
  loadRuntimeAssets,
  loadTestSources,
  loadVendorRequirements,
  projectYear,
  resolveProjectJars,
  resolveTestJars,
  robotMainClass,
  simulationSettings,
  testClassNames,
  appPath,
  type BridgeMessage,
  type CompileResult,
  type FileChange,
  type ProjectFiles,
  type ProjectTemplate,
  type ProjectYear,
  type RuntimeAssets,
  type SimHost,
  type SimStatus,
  type VendorJar,
  type VendorRequirement
} from '@frc-dev-wasm/runtime'
import type * as vscode from 'vscode'
import { NotRobotProject, type FrcHost, type FrcServices, type FrcTool, type RunOptions } from './services'
import { compileKey, storeBuild, storedBuild } from './buildCache'
import { serveToolFiles } from './toolFiles'
import { openSwingTool, openTool } from './toolView'
import { createVendorSupport, type VendorSupport } from './vendorLibraries'
import { workspaceFolder } from '../workspace'
import { plainText } from '../plainText'

const RUNTIME_BASE = new URL(`${import.meta.env.BASE_URL}runtime/`, window.location.href)

// Web tools are pages. Swing tools are jars that run in CheerpJ. `shim` is a jar for the tool's
// classpath ahead of its own, and `networkTables` connects the tool to the NT4 server in the page.
// `years` are the seasons a tool has a release for, when not all; a Swing tool runs on the first one's runtime.
// `perYear` tools have a build for each season, under runtime/<year>/.
type Tool = FrcTool & { years?: string[]; perYear?: boolean } &
  ({ path: string } | { jar: string; mainClass: string; shim?: string; args?: string[]; networkTables?: boolean })

const TOOLS: Tool[] = [
  { id: 'advantagescope', label: 'AdvantageScope', path: 'tools/advantagescope/index.html' },
  { id: 'datalogtool', label: 'DataLogTool', path: 'tools/datalogtool/index.html', perYear: true },
  { id: 'elastic', label: 'Elastic', path: 'tools/elastic/index.html', perYear: true },
  { id: 'glass', label: 'Glass', path: 'tools/glass/index.html', perYear: true },
  { id: 'outlineviewer', label: 'OutlineViewer', path: 'tools/outlineviewer/index.html', perYear: true },
  { id: 'robotbuilder', label: 'RobotBuilder', years: ['2026'], jar: 'tools/robotbuilder/RobotBuilder.jar', mainClass: 'robotbuilder.RobotBuilder' },
  {
    id: 'smartdashboard',
    label: 'SmartDashboard',
    years: ['2026'],
    jar: 'tools/smartdashboard/SmartDashboard.jar',
    mainClass: 'edu.wpi.first.smartdashboard.SmartDashboard',
    shim: 'tools/smartdashboard/frcdevwasm-smartdashboard.jar',
    args: ['-ip', 'localhost'],
    networkTables: true
  },
  { id: 'sysid', label: 'SysId', path: 'tools/sysid/index.html', perYear: true }
]

// ANSI colors for the WPILib terminal.
const RED = '\x1b[31m'
const GREEN = '\x1b[32m'
const YELLOW = '\x1b[33m'
const RESET = '\x1b[0m'

export function createWpilibServices(host: FrcHost): FrcServices {
  const api = host.vscode
  const diagnostics = api.languages.createDiagnosticCollection('javac')
  const nt = new Nt4Server({ robotOnly: true })
  const devices = new DeviceState()
  const listeners = new Set<(messages: BridgeMessage[]) => void>()
  const statusListeners = new Set<(status: SimStatus) => void>()
  // Per season. A window holds one project, so usually just one.
  const runtimes = new Map<string, Promise<RuntimeAssets>>()
  const compilers = new Map<string, Promise<JavaCompiler>>()
  const vendorSupport = new Map<string, VendorSupport>()
  const templates = new Map<string, Promise<ProjectTemplate[]>>()
  let robot: RobotSession | undefined
  let status: SimStatus = 'stopped'
  let simRoot: string | undefined
  let simYear = DEFAULT_YEAR
  let robotWrite = host.write
  // Ends the current robot run for whoever waits on it, once, however it ends
  let endRun: (() => void) | undefined

  const memo = <T>(cache: Map<string, Promise<T>>, key: string, create: () => Promise<T>) => {
    let value = cache.get(key)
    if (!value) {
      value = create()
      cache.set(key, value)
      value.catch(() => cache.delete(key))
    }
    return value
  }
  const runtime = (year: string) => memo(runtimes, year, () => loadRuntimeAssets(year, RUNTIME_BASE))
  // One compiler JVM per season, however many callers ask at once
  const getCompiler = (year: string) => memo(compilers, year, () => runtime(year).then((rt) => new JavaCompiler(rt)))
  const vendors = (year: string) => {
    let support = vendorSupport.get(year)
    if (!support) {
      support = createVendorSupport(api, () => runtime(year), () => getCompiler(year), host.log)
      vendorSupport.set(year, support)
    }
    return support
  }
  // Read at every build and run, so an edited build.gradle takes effect
  const yearOf = async (root: vscode.Uri): Promise<ProjectYear> => (await projectYear(workspaceFiles(api, root))) ?? { year: DEFAULT_YEAR }
  const openYear = async () => {
    const folder = api.workspace.workspaceFolders?.[0]?.uri
    return folder ? (await yearOf(folder)).year : DEFAULT_YEAR
  }
  // A Sim GUI opened before any run uses the open project's year
  void openYear().then((year) => {
    if (!robot) simYear = year
  }, () => undefined)

  // The vendor requirements of the robot projects, by season. A project of a season the site lacks is skipped.
  async function projectVendorRequirements(): Promise<Map<string, VendorRequirement[]>> {
    const loaded = await Promise.all(
      (await findRobotProjects(api)).map(async (root) => {
        const { year } = await yearOf(root)
        const rt = await runtime(year).catch((error) => void host.log(`${basename(root)}: ${String(error)}\n`))
        return rt && { year, ...(await loadVendorRequirements(workspaceFiles(api, root), rt)) }
      })
    )
    const byYear = new Map<string, VendorRequirement[]>()
    for (const project of loaded) {
      if (!project) continue
      const { year, vendor, libraries, warnings } = project
      for (const warning of warnings) host.log(`warning: ${warning}\n`)
      byYear.set(year, [...(byYear.get(year) ?? []), ...vendor, ...libraries.compile, ...libraries.runtime, ...libraries.processors])
    }
    return byYear
  }
  // Only the open season's: another season's compile JVM would load just for this
  const prepareVendorLibraries = () =>
    Promise.all([projectVendorRequirements(), openYear()])
      .then(([byYear, year]) => vendors(year).prepare(byYear.get(year) ?? []))
      .catch((error) => host.log(`${String(error)}\n`))
  let prepareTimer: ReturnType<typeof setTimeout> | undefined
  const prepareSoon = () => {
    clearTimeout(prepareTimer)
    prepareTimer = setTimeout(() => void prepareVendorLibraries(), 2000)
  }
  const setStatus = (next: SimStatus) => {
    status = next
    for (const listener of statusListeners) listener(next)
  }

  const simHost: SimHost = {
    listen(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    send(message) {
      robot?.send(message)
      // A GUI that is reopened while the robot runs starts from what the last one set.
      if (robot && message.type !== 'sim.timing') devices.apply(message)
    },
    snapshot: () => devices.snapshot(),
    status: () => status,
    year: () => simYear,
    projectRoot: () => simRoot,
    onStatus(listener) {
      statusListeners.add(listener)
      return () => statusListeners.delete(listener)
    },
    nt
  }
  Object.assign(window, { [SIM_HOST_GLOBAL]: simHost, frcDevWasmNt4: nt })
  serveToolFiles(api, host.write)
  for (const pattern of ['**/vendordeps/*.json', '**/build.gradle', '**/gradle.properties']) {
    const watcher = api.workspace.createFileSystemWatcher(pattern)
    watcher.onDidCreate(prepareSoon)
    watcher.onDidChange(prepareSoon)
  }
  api.workspace.onDidChangeWorkspaceFolders(prepareSoon)

  function onRobotMessages(messages: BridgeMessage[]) {
    for (const message of messages) {
      if (message.type === 'console') robotWrite(String(message.data.text ?? ''))
      else if (message.type.startsWith('nt.')) nt.handleRobotMessage(message)
      else devices.apply(message)
    }
    for (const listener of listeners) listener(messages)
  }

  async function projectRoot(root?: string): Promise<vscode.Uri> {
    if (root) return api.Uri.file(root)
    const folder = api.workspace.workspaceFolders?.[0]?.uri
    if (folder && (await isRobotProject(workspaceFiles(api, folder)))) return folder
    throw new NotRobotProject(`${folder ? basename(folder) : 'The open folder'} is not a robot project.`)
  }

  type Build = {
    result: CompileResult
    root: vscode.Uri
    assets: RuntimeAssets
    project: Awaited<ReturnType<typeof loadRobotProject>>
    runtimeJars: VendorJar[]
  }
  let lastBuild: Build | undefined
  let builds = 0
  // Compiles in progress by key. As a second Gradle build waits for the first one's lock and then finds compileJava
  // up to date, a build of the same sources waits for the running one.
  const compiling = new Map<string, Promise<CompileResult>>()

  // Without `finish` a successful build says nothing more, for a task that goes on, such as test
  async function build(options: RunOptions = {}, finish = true): Promise<Build | undefined> {
    lastBuild = undefined
    const id = ++builds
    const write = options.write ?? host.write
    const root = await projectRoot(options.root)
    const started = performance.now()
    write(`\r\n> Task :compileJava (${basename(root)})\n`)
    const { year, warning } = await yearOf(root)
    if (warning) write(`${YELLOW}warning: ${warning}${RESET}\n`)
    const rt = await runtime(year)
    const project = await loadRobotProject(workspaceFiles(api, root), rt, basename(root), { git: (args) => host.git(args, root.path) })
    for (const warning of project.warnings) write(`${YELLOW}warning: ${warning}${RESET}\n`)
    // Gradle writes these into the source tree too. The compile has them already, so a failed write only warns.
    for (const [path, source] of Object.entries(project.generated)) {
      const file = api.Uri.joinPath(root, path)
      try {
        await api.workspace.fs.createDirectory(api.Uri.joinPath(file, '..'))
        await api.workspace.fs.writeFile(file, new TextEncoder().encode(source))
      } catch (error) {
        write(`${YELLOW}warning: could not write ${path}: ${error instanceof Error ? error.message : String(error)}${RESET}\n`)
      }
    }
    const jars = await resolveProjectJars(project, (requirements) => vendors(year).resolve(requirements, write))
    const compile = { classpath: project.classpath, processors: project.processors, vendorJars: jars.compile, processorJars: jars.processors }
    const key = await compileKey(rt, project.sources, compile).catch(() => undefined)
    const stored = key && ((await compiling.get(key)?.then((r) => (r.success ? r : undefined))) ?? (await storedBuild(api, root, key)))
    if (stored) {
      write('UP-TO-DATE\n')
      reportDiagnostics(api, diagnostics, root, stored, () => undefined, 'src/main/java')
      if (finish) write(buildLine(true, started))
      const done = { result: stored, root, assets: rt, project, runtimeJars: jars.runtime }
      if (id === builds) lastBuild = done
      return done
    }
    if (!compilers.has(year)) write('Starting the Java compiler. The first build takes longer.\n')
    const run = getCompiler(year).then((compiler) => compiler.compile(project.sources, compile))
    if (key) compiling.set(key, run)
    let result: CompileResult
    try {
      result = await run
      reportDiagnostics(api, diagnostics, root, result, write, 'src/main/java')
      if (result.success && key) await storeBuild(api, root, key, result)
    } finally {
      if (key && compiling.get(key) === run) compiling.delete(key)
    }
    if (finish || !result.success) write(buildLine(result.success, started))
    if (!result.success) {
      void api.commands.executeCommand('workbench.actions.view.problems')
      return undefined
    }
    const done = { result, root, assets: rt, project, runtimeJars: jars.runtime }
    // A build that started earlier and ended later must not replace the newer one
    if (id === builds) lastBuild = done
    return done
  }

  // The exit codes of the JUnit launcher (packages/java/junit)
  const TEST_FAILURES: Record<number, string> = { 1: 'There were failing tests.', 2: 'The tests could not run. See the output above.', 3: 'No tests found for the given includes.' }
  const buildLine = (success: boolean, started: number) => {
    const seconds = ((performance.now() - started) / 1000).toFixed(1)
    return success ? `\n${GREEN}BUILD SUCCESSFUL${RESET} in ${seconds}s\n` : `\n${RED}BUILD FAILED${RESET} in ${seconds}s\n`
  }

  // `./gradlew test`: the robot code, the tests, then JUnit in a JVM of its own
  async function test(options: RunOptions = {}): Promise<boolean> {
    const write = options.write ?? host.write
    const started = performance.now()
    const built = await build(options, false)
    if (!built) return false
    const { root, assets, project } = built
    const sources = await loadTestSources(workspaceFiles(api, root))
    if (Object.keys(sources).length === 0) {
      write(`\n> Task :compileTestJava NO-SOURCE\n\n> Task :test NO-SOURCE\n${buildLine(true, started)}`)
      return true
    }
    write('\n> Task :compileTestJava\n')
    const support = vendors(assets.year)
    const warnings = [...project.test.warnings]
    const jars = await resolveTestJars(
      project,
      project.test.framework,
      { resolve: (requirements) => support.resolve(requirements, write), managedVersions: (bom) => support.managedVersions(bom) },
      warnings
    )
    for (const warning of warnings) write(`${YELLOW}warning: ${warning}${RESET}\n`)
    const compiler = await getCompiler(assets.year)
    const compiled = await compiler.compile(sources, { classes: built.result.jar, classpath: project.classpath, vendorJars: jars.compile })
    reportDiagnostics(api, diagnostics, root, compiled, write, 'src/test/java')
    if (!compiled.success || !compiled.jar) {
      write(buildLine(false, started))
      void api.commands.executeCommand('workbench.actions.view.problems')
      return false
    }
    write('\n> Task :test\n')
    const run = await TestRun.start({
      assets,
      mainJar: built.result.jar!,
      testJar: compiled.jar,
      jars: jars.runtime,
      classpath: project.classpath,
      deploy: project.deploy,
      classes: testClassNames(compiled.jar),
      systemProperties: project.test.systemProperties,
      filters: options.tests,
      onOutput: (text) => write(`${text}\n`)
    })
    const abort = () => run.kill()
    if (options.signal?.aborted) abort()
    options.signal?.addEventListener('abort', abort, { once: true })
    const code = await run.exit
    options.signal?.removeEventListener('abort', abort)
    if (code === 0) write(buildLine(true, started))
    else write(`> Task :test FAILED\n\nFAILURE: Build failed with an exception.\n\n* What went wrong:\nExecution failed for task ':test'.\n> ${TEST_FAILURES[code] ?? `The test JVM exited with code ${code}.`}\n${buildLine(false, started)}`)
    return code === 0
  }

  async function stop() {
    const current = robot
    robot = undefined
    if (!current) return
    const end = endRun
    await current.stop()
    nt.detachRobot()
    devices.clear()
    setStatus('stopped')
    // stop() gives up after a timeout and the program may never report that it exited
    end?.()
  }

  return {
    tools: TOOLS,

    runtimeYear: openYear,

    async listTemplates(year) {
      return (await memo(templates, year, () => runtime(year).then(listProjectTemplates))).map((t) => ({
        id: `${t.kind}:${t.id}`,
        label: t.name,
        description: t.tags.join(', '),
        kind: t.kind
      }))
    },

    async createProject(year, templateId, folder, teamNumber) {
      const [kind, id] = templateId.split(':')
      const all = await memo(templates, year, () => runtime(year).then(listProjectTemplates))
      const template = all.find((t) => t.kind === kind && t.id === id)
      if (!template) throw new Error(`Unknown template ${templateId}`)
      const root = api.Uri.file(folder)
      const existing = await api.workspace.fs.readDirectory(root).then((entries) => entries.length, () => 0)
      if (existing > 0) throw new Error(`Cannot create into non empty folder ${folder}`)
      const files = await generateProject(await runtime(year), template, teamNumber)
      for (const [path, data] of Object.entries(files)) await api.workspace.fs.writeFile(api.Uri.joinPath(root, path), data)
      host.write(`Created ${template.name} project in ${folder}\n`)
    },

    async build(options) {
      return (await build(options)) !== undefined
    },

    test,

    async simulationInfo(root) {
      const folder = await projectRoot(root)
      const gradle = new TextDecoder().decode(await api.workspace.fs.readFile(api.Uri.joinPath(folder, 'build.gradle')))
      const mainClass = robotMainClass(gradle)
      if (!mainClass) throw new Error('build.gradle does not define ROBOT_MAIN_CLASS')
      return { mainClass, ...simulationSettings(gradle) }
    },

    async simulate(options = {}) {
      const write = options.write ?? host.write
      const root = options.reuseBuild ? await projectRoot(options.root) : undefined
      const built = lastBuild && lastBuild.root.path === root?.path ? lastBuild : await build(options)
      if (!built?.result.jar) return false
      await stop()
      simYear = built.assets.year
      setStatus('starting')
      simRoot = built.root.path
      if (options.gui ?? true) await host.showSimGui(simYear)
      robotWrite = write
      let ended = false
      const end = () => {
        if (ended) return
        ended = true
        host.closeSimGui()
        if (endRun === end) {
          endRun = undefined
          robotWrite = host.write
        }
        options.onExit?.()
      }
      endRun = end
      write(`\r\n> Task :${built.assets.year === '2026' ? 'simulateJava' : 'run'}\n`)
      nt.attachRobot((message) => robot?.send(message))
      try {
        robot = await RobotSession.start({
          assets: built.assets,
          jar: built.result.jar,
          mainClass: built.project.mainClass,
          classpath: built.project.classpath,
          vendorJars: built.runtimeJars,
          deploy: built.project.deploy,
          onMessages: (messages) => {
            if (status === 'starting') setStatus('running')
            onRobotMessages(messages)
          },
          onOutput: (text) => write(`${text}\n`),
          onLogFiles: async (files) => {
            for (const file of files.filter((f) => isLogFile(f.path))) {
              await api.workspace.fs.writeFile(api.Uri.joinPath(built.root, file.path), file.data)
              write(`Saved ${file.path}\n`)
            }
          },
          onExit: (code) => {
            write(`\nRobot program exited with code ${code}\n`)
            end()
            if (robot) {
              robot = undefined
              nt.detachRobot()
              devices.clear()
              setStatus('stopped')
            }
          }
        })
      } catch (error) {
        setStatus('stopped')
        end()
        throw error
      }
      return true
    },

    stop,

    async robotProjects() {
      return (await findRobotProjects(api)).map((uri) => uri.path)
    },

    async warmUp() {
      const folder = api.workspace.workspaceFolders?.[0]?.uri
      if (folder && (await isRobotProject(workspaceFiles(api, folder)))) await (await getCompiler((await yearOf(folder)).year)).warmUp()
    },

    async manageVendorLibraries() {
      const root = await projectRoot()
      await manageVendorLibraries(api, root, await runtime((await yearOf(root)).year), host.write)
      void prepareVendorLibraries()
    },

    prepareVendorLibraries,

    async importVendorJars() {
      for (const [year, requirements] of await projectVendorRequirements()) await vendors(year).importFromDisk(requirements)
    },

    async startTool(toolId) {
      const tool = TOOLS.find((t) => t.id === toolId)
      if (!tool) throw new Error(`Unknown tool ${toolId}`)
      const year = await openYear()
      if (tool.years && !tool.years.includes(year)) throw new Error(`${tool.label} has no WPILib ${year} release`)
      if ('path' in tool) {
        await openTool(new URL(tool.perYear ? `${year}/${tool.path}` : tool.path, RUNTIME_BASE).href, tool.label)
        return
      }
      const root = api.workspace.workspaceFolders?.[0]?.uri ?? api.Uri.file('/workspace')
      const start = async (frame: HTMLIFrameElement, onExit: () => void) => {
        host.write(`Starting ${tool.label}\n`)
        const [assets, files] = await Promise.all([runtime(tool.years?.[0] ?? year), readWorkspaceFiles(api, root)])
        return SwingApp.start({
          assets,
          frame,
          classpath: [tool.shim, tool.jar].flatMap((file) => (file ? [appPath(new URL(file, RUNTIME_BASE))] : [])),
          mainClass: tool.mainClass,
          args: tool.args,
          bridge: tool.networkTables ? new Nt4JvmClient(nt) : undefined,
          id: tool.id,
          files,
          onFileChanges: (changes) => writeWorkspaceChanges(api, root, changes),
          onError: host.write,
          onExit
        })
      }
      await openSwingTool(tool.id, tool.label, start, (error) => {
        const message = error instanceof Error ? error.message : String(error)
        host.write(`Could not start ${tool.label}: ${message}\n`)
        void api.window.showErrorMessage(plainText(`WPILib: could not start ${tool.label}: ${message}`))
      })
    }
  }
}

interface InstalledVendorDep {
  uri: vscode.Uri
  name: string
  version: string
  uuid: string
}

async function installedVendorDeps(api: typeof vscode, root: vscode.Uri): Promise<InstalledVendorDep[]> {
  const dir = api.Uri.joinPath(root, 'vendordeps')
  const entries = await api.workspace.fs.readDirectory(dir).then(undefined, () => [] as [string, vscode.FileType][])
  const out: InstalledVendorDep[] = []
  for (const [name] of entries.filter(([n, t]) => t === api.FileType.File && n.endsWith('.json'))) {
    const uri = api.Uri.joinPath(dir, name)
    try {
      const json = JSON.parse(new TextDecoder().decode(await api.workspace.fs.readFile(uri)))
      out.push({ uri, name: json.name, version: json.version, uuid: json.uuid })
    } catch {
      // Not a vendordep file
    }
  }
  return out
}

// Mirrors the install, update and uninstall actions of the WPILib vendor dependencies view.
async function manageVendorLibraries(api: typeof vscode, root: vscode.Uri, rt: RuntimeAssets, write: (text: string) => void) {
  const title = 'Manage Vendor Libraries'
  const action = await api.window.showQuickPick(
    [
      { label: 'Install new libraries', id: 'install' },
      { label: 'Check for updates', id: 'update' },
      { label: 'Manage current libraries', description: 'Uninstall', id: 'remove' }
    ],
    { title, placeHolder: basename(root) }
  )
  if (!action) return
  const installed = await installedVendorDeps(api, root)
  const available = await availableVendorDeps(rt)
  const writeVendorDep = async (dep: (typeof available)[number]) => {
    const text = await dep.json()
    const fileName = JSON.parse(text).fileName ?? `${dep.name}-${dep.version}.json`
    await api.workspace.fs.writeFile(api.Uri.joinPath(root, 'vendordeps', fileName), new TextEncoder().encode(text))
  }

  if (action.id === 'install') {
    const picks = await api.window.showQuickPick(
      available
        .filter((a) => !installed.some((i) => i.uuid === a.uuid))
        .map((a) => ({ label: a.name, description: a.version, detail: a.description, dep: a })),
      { title, placeHolder: 'Libraries to install', canPickMany: true, matchOnDetail: true }
    )
    for (const pick of picks ?? []) {
      await writeVendorDep(pick.dep)
      write(`Installed ${pick.dep.name} ${pick.dep.version}\n`)
    }
  } else if (action.id === 'update') {
    const updates = installed.flatMap((i) => {
      const newer = available.find((a) => a.uuid === i.uuid && a.version !== i.version)
      return newer ? [{ label: i.name, description: `${i.version} to ${newer.version}`, installed: i, dep: newer, picked: true }] : []
    })
    if (updates.length === 0) {
      void api.window.showInformationMessage('All vendor libraries are up to date.')
      return
    }
    const picks = await api.window.showQuickPick(updates, { title, placeHolder: 'Libraries to update', canPickMany: true })
    for (const pick of picks ?? []) {
      await api.workspace.fs.delete(pick.installed.uri)
      await writeVendorDep(pick.dep)
      write(`Updated ${pick.dep.name} to ${pick.dep.version}\n`)
    }
  } else {
    const picks = await api.window.showQuickPick(
      installed.map((i) => ({ label: i.name, description: i.version, installed: i })),
      { title, placeHolder: 'Libraries to uninstall', canPickMany: true }
    )
    for (const pick of picks ?? []) {
      await api.workspace.fs.delete(pick.installed.uri)
      write(`Uninstalled ${pick.installed.name}\n`)
    }
  }
}

function basename(uri: vscode.Uri): string {
  return uri.path.split('/').filter(Boolean).pop() ?? uri.path
}

// Projects live in the browser workspace, up to two levels below it. The open folder counts too.
async function findRobotProjects(api: typeof vscode): Promise<vscode.Uri[]> {
  const out: vscode.Uri[] = []
  const visit = async (dir: vscode.Uri, depth: number) => {
    if (await isRobotProject(workspaceFiles(api, dir))) {
      out.push(dir)
      return
    }
    if (depth === 0) return
    const entries = await api.workspace.fs.readDirectory(dir).then(undefined, () => [] as [string, vscode.FileType][])
    for (const [name, type] of entries) {
      if (type === api.FileType.Directory && !name.startsWith('.') && name !== 'build' && name !== 'node_modules') {
        await visit(api.Uri.joinPath(dir, name), depth - 1)
      }
    }
  }
  await visit(api.Uri.file(workspaceFolder.path), 2)
  const open = api.workspace.workspaceFolders?.[0]?.uri
  if (open && !out.some((uri) => uri.path === open.path) && (await isRobotProject(workspaceFiles(api, open)))) out.push(open)
  return out
}

const NOT_MIRRORED = new Set(['.git', '.gradle', 'build', 'node_modules'])

// Everything in the workspace except folders that are large and generated.
async function readWorkspaceFiles(api: typeof vscode, root: vscode.Uri): Promise<Record<string, Uint8Array>> {
  const out: Record<string, Uint8Array> = {}
  const walk = async (rel: string) => {
    const entries = await api.workspace.fs.readDirectory(api.Uri.joinPath(root, rel)).then(undefined, () => [] as [string, vscode.FileType][])
    for (const [name, type] of entries) {
      if (NOT_MIRRORED.has(name)) continue
      if (type === api.FileType.Directory) await walk(`${rel}${name}/`)
      else if (type === api.FileType.File) out[`${rel}${name}`] = await api.workspace.fs.readFile(api.Uri.joinPath(root, rel, name))
    }
  }
  await walk('')
  return out
}

async function writeWorkspaceChanges(api: typeof vscode, root: vscode.Uri, changes: FileChange[]) {
  for (const { path, data } of changes) {
    const uri = api.Uri.joinPath(root, path)
    if (data) await api.workspace.fs.writeFile(uri, data)
    else await api.workspace.fs.delete(uri).then(undefined, () => undefined)
  }
  await api.commands.executeCommand('workbench.files.action.refreshFilesExplorer')
}

function workspaceFiles(api: typeof vscode, root: vscode.Uri): ProjectFiles {
  return {
    async read(path) {
      return api.workspace.fs.readFile(api.Uri.joinPath(root, path)).then(undefined, () => undefined)
    },
    async list(dir) {
      const out: string[] = []
      const walk = async (rel: string) => {
        const entries = await api.workspace.fs.readDirectory(api.Uri.joinPath(root, rel)).then(undefined, () => [] as [string, vscode.FileType][])
        for (const [name, type] of entries) {
          const path = `${rel}${name}`
          if (type === api.FileType.Directory) await walk(`${path}/`)
          else if (type === api.FileType.File) out.push(path)
        }
      }
      await walk(dir)
      return out
    }
  }
}

function reportDiagnostics(
  api: typeof vscode,
  collection: vscode.DiagnosticCollection,
  root: vscode.Uri,
  result: CompileResult,
  write: (text: string) => void,
  sourceRoot: string
) {
  collection.clear()
  const byFile = new Map<string, vscode.Diagnostic[]>()
  for (const d of result.diagnostics) {
    if (d.kind === 'NOTE' || d.kind === 'OTHER') {
      write(`${d.message}\n`)
      continue
    }
    const severity = d.kind === 'ERROR' ? api.DiagnosticSeverity.Error : api.DiagnosticSeverity.Warning
    const label = d.kind === 'ERROR' ? `${RED}error${RESET}` : `${YELLOW}warning${RESET}`
    const path = d.file ? `${sourceRoot}/${d.file}` : undefined
    write(`${path ? `${path}:${d.line}: ` : ''}${label}: ${d.message}\n`)
    if (!path) continue
    const line = Math.max(0, d.line - 1)
    const column = Math.max(0, d.column - 1)
    const diagnostic = new api.Diagnostic(new api.Range(line, column, line, column + 1), d.message, severity)
    diagnostic.source = 'javac'
    const list = byFile.get(path) ?? []
    list.push(diagnostic)
    byFile.set(path, list)
  }
  for (const [path, list] of byFile) collection.set(api.Uri.joinPath(root, path), list)
}
