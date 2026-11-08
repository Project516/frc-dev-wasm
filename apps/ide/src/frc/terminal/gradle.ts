import type * as vscode from 'vscode'
import type { FrcServices } from '../services'
import { basename, join } from './paths'
import type { Io } from './io'

const BOLD = '\x1b[1m'
const RESET = '\x1b[0m'

const BUILD_TASKS = new Set(['build', 'assemble', 'classes', 'compileJava', 'check'])

interface ExternalSimTask {
  // The library folder under build/jni, and the file under build/sim, that the task writes
  flavor: string
  file: string
  // What 2027 adds: the Gradle task that runs the program and its debug port
  run?: { taskPath: string; debugPort: number }
}

// GradleRIO's tasks that build and describe the simulation for the WPILib extension: two in 2026, one in 2027
const EXTERNAL_SIM_TASKS: Record<string, ExternalSimTask> = {
  simulateExternalJavaRelease: { flavor: 'release', file: 'release_java.json' },
  simulateExternalJavaDebug: { flavor: 'debug', file: 'debug_java.json' },
  simulateExternalJava: { flavor: 'release', file: 'java.json', run: { taskPath: ':run', debugPort: 5005 } }
}
// GradleRIO 2027 runs the robot with `run` and fails `simulateJava`
const RUN_TASK_YEARS = new Set(['2027'])
const UNAVAILABLE: Record<string, string> = {
  deploy: 'Deploying needs a connection to a roboRIO, which a web page cannot open.'
}

const TASK_LIST = `Supported tasks:
  build                        Compile the robot code (also: assemble, classes, compileJava, check)
  test                         Build, then run the JUnit tests of src/test (also: --tests <pattern>)
  simulateJava                 Build, then run the robot program in simulation (2026). Ctrl+C stops it.
  run                          Build, then run the robot program in simulation (2027). Ctrl+C stops it.
  simulateExternalJavaRelease  Build, and write build/sim/release_java.json for the WPILib extension (2026)
  simulateExternalJava         Build, and write build/sim/java.json for the WPILib extension (2027)
  clean                        Delete the build folder
  tasks                        Show this list
`

export interface GradleContext {
  api: typeof vscode
  services: FrcServices
  // The project folder: where the gradlew script is
  root: string
  signal: AbortSignal
}

// ./gradlew in the terminal runs the same services as the WPILib commands
export async function gradlew(args: string[], io: Io, ctx: GradleContext): Promise<number> {
  const tasks: string[] = []
  const filters: string[] = []
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]
    if (arg === '--version' || arg === '-v') {
      io.stdout.write('Gradle tasks in the browser terminal: build and simulateJava (run in 2027) run the WPILib build and simulation.\n')
      return 0
    }
    if (arg === '-PhwSim') {
      io.stderr.write('Simulating with hardware needs real hardware, which a web page cannot reach.\n')
      return 1
    }
    if (arg === '-x' || arg === '--exclude-task') i++
    else if (arg === '--tests') filters.push(args[++i] ?? '')
    else if (arg.startsWith('--tests=')) filters.push(arg.slice('--tests='.length))
    else if (!arg.startsWith('-')) tasks.push(arg)
  }
  if (tasks.length === 0 || tasks.includes('tasks') || tasks.includes('help')) {
    io.stdout.write(`${BOLD}Gradle in the browser terminal${RESET}\n\nUsage: ./gradlew <task>...\n\n${TASK_LIST}`)
    return 0
  }
  const runTask = tasks.some((t) => t === 'simulateJava' || t === 'run') && RUN_TASK_YEARS.has(await ctx.services.runtimeYear())
  const simulateTask = runTask ? 'run' : 'simulateJava'
  const unsupported = tasks.filter((t) => !BUILD_TASKS.has(t) && !(t in EXTERNAL_SIM_TASKS) && t !== simulateTask && t !== 'clean' && t !== 'test')
  if (runTask && tasks.includes('simulateJava')) {
    io.stderr.write(`FAILURE: Build failed with an exception.\n\n* What went wrong:\nExecution failed for task ':simulateJava'.\n> The simulateJava task has been removed. Use run instead.\n\nBUILD FAILED\n`)
    return 1
  }
  if (unsupported.length > 0) {
    const task = unsupported[0]
    io.stderr.write(`Task '${task}' is not supported in the browser.${UNAVAILABLE[task] ? ` ${UNAVAILABLE[task]}` : ''}\n\n${TASK_LIST}`)
    return 1
  }

  // A build cannot be cancelled, but its output stops once Ctrl+C has given the prompt back
  const write = (text: string) => {
    if (!ctx.signal.aborted) io.stdout.write(text)
  }
  try {
    for (const task of tasks) {
      ctx.signal.throwIfAborted()
      if (task === 'clean') {
        write('\n> Task :clean\n')
        await ctx.api.workspace.fs.delete(ctx.api.Uri.file(join(ctx.root, 'build')), { recursive: true, useTrash: false }).then(undefined, () => undefined)
      } else if (task === 'test') {
        if (!(await ctx.services.test({ root: ctx.root, write, tests: filters, signal: ctx.signal }))) return ctx.signal.aborted ? 130 : 1
      } else if (task === simulateTask) {
        return await simulate(ctx, write)
      } else if (task in EXTERNAL_SIM_TASKS) {
        if (!(await ctx.services.build({ root: ctx.root, write }))) return ctx.signal.aborted ? 130 : 1
        write(`\n> Task :${task}\n`)
        await writeSimulationFile(ctx, EXTERNAL_SIM_TASKS[task])
      } else if (!(await ctx.services.build({ root: ctx.root, write }))) return ctx.signal.aborted ? 130 : 1
      ctx.signal.throwIfAborted()
    }
  } catch (error) {
    if (ctx.signal.aborted) return 130
    io.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
    return 1
  }
  return 0
}

async function simulate(ctx: GradleContext, write: (text: string) => void): Promise<number> {
  let exited!: () => void
  const exit = new Promise<void>((resolve) => (exited = resolve))
  const interrupted = new Promise<'interrupted'>((resolve) => {
    if (ctx.signal.aborted) resolve('interrupted')
    else ctx.signal.addEventListener('abort', () => resolve('interrupted'), { once: true })
  })
  if (!(await ctx.services.simulate({ root: ctx.root, write, onExit: exited }))) return ctx.signal.aborted ? 130 : 1
  if ((await Promise.race([exit, interrupted])) === 'interrupted') {
    await ctx.services.stop()
    return 130
  }
  return 0
}

// What GradleRIO's JavaExternalSimulationTask writes: one entry per jar, extension libraries by path
async function writeSimulationFile(ctx: GradleContext, { flavor, file, run }: ExternalSimTask): Promise<void> {
  const info = await ctx.services.simulationInfo(ctx.root)
  const libraryDir = join(ctx.root, `build/jni/${flavor}`)
  const simInfo = [
    {
      type: 'java',
      name: `jar (in project ${basename(ctx.root)})`,
      extensions: info.extensions.map((e) => ({ name: e.name, libName: `${libraryDir}/lib${e.artifact}.so`, defaultEnabled: e.defaultEnabled })),
      environment: info.environment,
      libraryDir,
      mainClassName: info.mainClass,
      ...run
    }
  ]
  await ctx.api.workspace.fs.writeFile(ctx.api.Uri.file(join(ctx.root, `build/sim/${file}`)), new TextEncoder().encode(JSON.stringify(simInfo, null, 2)))
}
