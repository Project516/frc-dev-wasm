// git, the real program: the WebAssembly build runs in a worker on the files of a project in the
// origin private file system or a folder opened from disk. Remotes are reached through a CORS proxy.
import { resolvePath } from '../../frc/terminal/paths'
import { diskFolderFor, notifyDiskChanges, workspaceFolder, workspaceStore } from '../../workspace'
import type { RunRequest, WorkerMessage } from './worker'

const NETWORK_COMMANDS = new Set(['clone', 'fetch', 'pull', 'push', 'ls-remote'])
const UNSUPPORTED_COMMANDS = new Set(['submodule'])

export interface Remotes {
  proxy?: string
  auth: Record<string, string>
}

const ENV: Record<string, string> = {
  GIT_CONFIG_NOSYSTEM: '1',
  GIT_TEMPLATE_DIR: '',
  GIT_PAGER: 'cat',
  GIT_TERMINAL_PROMPT: '0',
  // Every command sees a new file system, so inode numbers and change times say nothing. There are
  // no file modes or symbolic links, and nothing runs in the background.
  GIT_CONFIG_COUNT: '6',
  GIT_CONFIG_KEY_0: 'core.checkStat',
  GIT_CONFIG_VALUE_0: 'minimal',
  GIT_CONFIG_KEY_1: 'core.trustCtime',
  GIT_CONFIG_VALUE_1: 'false',
  GIT_CONFIG_KEY_2: 'core.fileMode',
  GIT_CONFIG_VALUE_2: 'false',
  GIT_CONFIG_KEY_3: 'core.symlinks',
  GIT_CONFIG_VALUE_3: 'false',
  GIT_CONFIG_KEY_4: 'gc.auto',
  GIT_CONFIG_VALUE_4: '0',
  GIT_CONFIG_KEY_5: 'maintenance.auto',
  GIT_CONFIG_VALUE_5: 'false'
}

// The directory git runs in after its -C options, and its command
function parse(args: string[], cwd: string): { dir: string; command: string | undefined } {
  let dir = cwd
  let i = 0
  for (; i < args.length && args[i].startsWith('-'); i++) {
    if (args[i] === '-C') dir = resolvePath(dir, args[++i] ?? '.')
    else if (args[i] === '-c') i++
  }
  return { dir, command: args[i] }
}

const inWorkspace = (path: string) => path === workspaceFolder.path || path.startsWith(`${workspaceFolder.path}/`)

const RUNTIME = new URL(`${import.meta.env.BASE_URL}runtime/git/`, window.location.href).href

let available: Promise<boolean> | undefined
const built = () =>
  (available ??= fetch(new URL('git.wasm', RUNTIME), { method: 'HEAD' }).then(
    (r) => r.ok,
    () => false
  ))

// Whether the real git runs this command. Browsers without the origin private file system, sites
// built without `pnpm build:git` and network commands without a proxy keep isomorphic-git.
export async function usesRealGit(args: string[], cwd: string, proxy: string | undefined): Promise<boolean> {
  const { dir, command = '' } = parse(args, cwd)
  if (UNSUPPORTED_COMMANDS.has(command) || (NETWORK_COMMANDS.has(command) && !proxy)) return false
  const disk = diskFolderFor(cwd)
  const reachable = disk ? diskFolderFor(dir)?.base === disk.base : !!workspaceStore && inWorkspace(cwd) && inWorkspace(dir)
  return reachable && (await built())
}

let worker: Worker | undefined
// Whether the worker holds files of the workspace open after its last command
let holding = false

// Closes the files the worker keeps open, so the page can write them. The store calls this before
// each write, while no git command runs. Writes at the same time share one release.
let releasing: Promise<void> | undefined
function release(): Promise<void> {
  if (!holding || !worker) return Promise.resolve()
  const current = worker
  return (releasing ??= new Promise<void>((resolve) => {
    current.onmessage = (event: MessageEvent<WorkerMessage>) => {
      if (event.data.type !== 'released') return
      holding = false
      releasing = undefined
      resolve()
    }
    current.postMessage({ type: 'release' })
  }))
}

// A git command, as a process: its arguments, directory, extra environment and standard streams
export interface GitProcess {
  args: string[]
  cwd: string
  env?: Record<string, string>
  input?: Uint8Array
  // Set when the output is shown in a terminal `columns` wide
  tty?: boolean
  columns?: number
  stdout(bytes: Uint8Array): void
  stderr(bytes: Uint8Array): void
  signal?: AbortSignal
}

// Variables that would make git start a program, which it cannot do here
const UNUSABLE_ENV = /ASKPASS|EDITOR|^VSCODE_GIT_IPC/

// Commands run one at a time
let queue: Promise<unknown> = Promise.resolve()

export function runRealGit(remotes: Remotes, process: GitProcess): Promise<{ status: number; changed: string[] }> {
  const { args, cwd } = process
  const { dir } = parse(args, cwd)
  const disk = diskFolderFor(dir)
  const extra = Object.fromEntries(Object.entries(process.env ?? {}).filter(([key]) => !UNUSABLE_ENV.test(key)))
  const request: RunRequest = {
    type: 'run',
    args,
    env: { ...extra, ...ENV },
    cwd,
    dir,
    limit: disk ? disk.base : workspaceFolder.path,
    disk,
    input: process.input,
    runtime: RUNTIME,
    ...remotes,
    tty: !!process.tty,
    columns: process.columns ?? 80
  }
  const execute = () =>
    new Promise<{ status: number; changed: string[] }>((resolve, reject) => {
      const stop = (error: Error) => {
        worker?.terminate()
        worker = undefined
        holding = false
        process.signal?.removeEventListener('abort', aborted)
        reject(error)
      }
      const aborted = () => stop(new DOMException('git was stopped', 'AbortError'))
      if (process.signal?.aborted) return aborted()
      process.signal?.addEventListener('abort', aborted)
      worker ??= new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' })
      worker.onerror = (event) => stop(new Error(event.message || 'git could not start'))
      worker.onmessage = (event: MessageEvent<WorkerMessage>) => {
        const m = event.data
        if (m.type === 'out') process.stdout(m.data)
        else if (m.type === 'err') process.stderr(m.data)
        else if (m.type === 'done') {
          process.signal?.removeEventListener('abort', aborted)
          if (disk) notifyDiskChanges(m.changed)
          else {
            holding = true
            workspaceStore!.notifyChanged(m.changed)
          }
          resolve({ status: m.status, changed: m.changed })
        } else if (m.type === 'error') stop(Object.assign(new Error(m.message), m.stack ? { stack: m.stack } : {}))
      }
      worker.postMessage(request)
    })
  const store = workspaceStore
  if (store) store.releaseFiles = release
  // In the origin private file system, writes from the page wait while git has files open
  const result = queue.then(() => (disk || !store ? execute() : store.exclusive(execute)))
  queue = result.catch(() => undefined)
  return result
}
