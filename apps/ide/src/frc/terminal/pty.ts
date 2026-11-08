import type * as vscode from 'vscode'
import type { TerminalGit } from '../../git/controller'
import type { FrcServices } from '../services'
import type { ShellHost } from './builtins'
import { Coreutils } from './coreutils'
import { Files } from './files'
import { TtySink } from './io'
import { LineEditor } from './readline'
import { Shell } from './shell'

const HISTORY_KEY = 'frc-dev-wasm.shell-history'

function loadHistory(): string[] {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(HISTORY_KEY) ?? '[]')
    return Array.isArray(saved) ? saved.filter((l): l is string => typeof l === 'string') : []
  } catch {
    return []
  }
}

// Shared by every shell terminal
const history = loadHistory()

export interface ShellDeps {
  api: typeof vscode
  services: FrcServices
  git: TerminalGit
  cwd?: string
}

const ANSI = /\x1b\[[0-9;?]*[ -/]*[@-~]/g

function createShell({ api, services, git, cwd }: ShellDeps, emit: (text: string) => void, columns: () => number, exit: (code: number) => void) {
  const files = new Files(api)
  const coreutils = new Coreutils(files)
  const home = api.workspace.workspaceFolders?.[0]?.uri.path ?? '/workspace'
  const host: ShellHost = {
    api,
    files,
    coreutils,
    services,
    git: git.cli,
    githubToken: git.token,
    githubLogin: git.login,
    githubLogout: git.logout,
    home,
    history,
    columns,
    exit
  }
  const shell = new Shell(host, new TtySink(emit), (text) => emit(`\r\x1b[K${text}`))
  if (cwd) {
    shell.cwd = cwd
    shell.vars.set('PWD', cwd)
  }
  return { shell, coreutils }
}

export function createShellPty(deps: ShellDeps): vscode.Pseudoterminal {
  const { api } = deps
  const written = new api.EventEmitter<string>()
  const closed = new api.EventEmitter<number | void>()
  let columns = 80
  let atLineStart = true
  let running: AbortController | undefined
  let queued = ''
  let finishRun: (() => void) | undefined
  let chain: Promise<unknown> = Promise.resolve()

  const emit = (text: string) => {
    written.fire(text)
    const visible = text.replace(ANSI, '')
    if (visible) atLineStart = /[\r\n]$/.test(visible)
  }
  const { shell, coreutils } = createShell(deps, emit, () => columns, (code) => closed.fire(code))
  const editor = new LineEditor({
    write: emit,
    columns: () => columns,
    prompt: () => shell.prompt(),
    history,
    complete: (line, cursor) => shell.complete(line, cursor),
    onEof: () => closed.fire(0)
  })

  // Shows the prompt again and runs what was typed or pasted while the last line was busy
  function resume() {
    editor.start()
    const rest = queued
    queued = ''
    if (rest) feed(rest)
  }

  function run(line: string) {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history))
    if (!line.trim()) return resume()
    const controller = (running = new AbortController())
    const done = () => {
      if (running !== controller) return
      running = undefined
      if (!atLineStart) emit('\r\n')
      resume()
    }
    finishRun = done
    shell.runLine(line, controller.signal).then(done, (error: unknown) => {
      emit(`sh: ${error instanceof Error ? error.message : String(error)}\r\n`)
      done()
    })
  }

  function feed(data: string) {
    chain = chain.then(async () => {
      const { line, rest } = await editor.input(data)
      if (line === undefined) return
      queued = rest
      run(line)
    })
  }

  return {
    onDidWrite: written.event,
    onDidClose: closed.event,
    open(dimensions) {
      if (dimensions) columns = dimensions.columns
      emit('FRC Dev shell. Type help for commands.\r\n\r\n')
      editor.start()
    },
    close() {
      running?.abort(new DOMException('Terminal closed', 'AbortError'))
      coreutils.dispose()
    },
    setDimensions(dimensions) {
      columns = dimensions.columns
    },
    handleInput(data) {
      if (!running) return feed(data)
      if (data.includes('\x03')) {
        queued = ''
        emit('^C\r\n')
        running.abort(new DOMException('Interrupted', 'AbortError'))
        // Some operations, such as a network request, do not stop on abort. Give the prompt back anyway.
        const finish = finishRun
        setTimeout(() => finish?.(), 2000)
      } else queued += data
    }
  }
}

export interface TaskDeps extends ShellDeps {
  command: string
  env?: Record<string, string>
  // Called once with the exit code, 130 when the terminal closed first
  onExit: (code: number) => void
}

// A task terminal as VS Code runs shell tasks: the command line in the shell, then a key closes it
export function createTaskPty(deps: TaskDeps): vscode.Pseudoterminal {
  const { api, command } = deps
  const written = new api.EventEmitter<string>()
  const closed = new api.EventEmitter<number | void>()
  const controller = new AbortController()
  let columns = 80
  let exitCode: number | undefined
  const emit = (text: string) => written.fire(text)
  const { shell, coreutils } = createShell(deps, emit, () => columns, () => undefined)
  for (const [k, v] of Object.entries(deps.env ?? {})) shell.vars.set(k, v)

  const finish = (code: number) => {
    if (exitCode !== undefined) return
    exitCode = code
    deps.onExit(code)
  }

  return {
    onDidWrite: written.event,
    onDidClose: closed.event,
    open(dimensions) {
      if (dimensions) columns = dimensions.columns
      emit(`\x1b[0m\x1b[1m *  Executing task: ${command} \x1b[0m\r\n\r\n`)
      shell.runLine(command, controller.signal).then(
        (code) => {
          const failed = code === 0 ? '' : ` *  The terminal process terminated with exit code: ${code}. \r\n`
          emit(`\r\n\x1b[0m\x1b[1m${failed} *  Terminal will be reused by tasks, press any key to close it. \x1b[0m\r\n`)
          finish(code)
        },
        (error: unknown) => {
          emit(`sh: ${error instanceof Error ? error.message : String(error)}\r\n`)
          finish(1)
        }
      )
    },
    close() {
      controller.abort(new DOMException('Terminal closed', 'AbortError'))
      coreutils.dispose()
      finish(130)
    },
    setDimensions(dimensions) {
      columns = dimensions.columns
    },
    handleInput(data) {
      if (exitCode !== undefined) closed.fire()
      else if (data.includes('\x03')) {
        emit('^C\r\n')
        controller.abort(new DOMException('Interrupted', 'AbortError'))
      }
    }
  }
}

export interface AgentShell {
  pty: vscode.Pseudoterminal
  // Runs a command line as if typed at the prompt. Resolves to its exit code and output without colors.
  run(command: string, signal: AbortSignal): Promise<{ code: number; output: string }>
}

const CAPTURE_LIMIT = 100_000

// The terminal Copilot runs commands in. Like a user's shell it keeps its directory and variables.
export function createAgentPty(deps: ShellDeps): AgentShell {
  const { api } = deps
  const written = new api.EventEmitter<string>()
  const closed = new api.EventEmitter<number | void>()
  let columns = 80
  let captured: string | undefined
  let running: AbortController | undefined
  let opened!: () => void
  const ready = new Promise<void>((resolve) => (opened = resolve))
  let queue: Promise<unknown> = Promise.resolve()

  const emit = (text: string) => {
    written.fire(text)
    // Only the end of a long output goes back to Copilot
    if (captured !== undefined) captured = (captured + text).slice(-CAPTURE_LIMIT)
  }
  const { shell, coreutils } = createShell(deps, emit, () => columns, (code) => closed.fire(code))

  async function runNow(command: string, signal: AbortSignal) {
    await ready
    emit(`${command}\r\n`)
    captured = ''
    const controller = (running = new AbortController())
    const abort = () => controller.abort(signal.reason)
    signal.addEventListener('abort', abort)
    const code = await shell.runLine(command, controller.signal).catch((error: unknown) => {
      emit(`sh: ${error instanceof Error ? error.message : String(error)}\r\n`)
      return controller.signal.aborted ? 130 : 1
    })
    signal.removeEventListener('abort', abort)
    running = undefined
    const output = captured.replace(ANSI, '').replace(/\r\n/g, '\n')
    captured = undefined
    if (output && !output.endsWith('\n')) emit('\r\n')
    emit(shell.prompt().text)
    return { code, output }
  }

  return {
    pty: {
      onDidWrite: written.event,
      onDidClose: closed.event,
      open(dimensions) {
        if (dimensions) columns = dimensions.columns
        emit(shell.prompt().text)
        opened()
      },
      close() {
        running?.abort(new DOMException('Terminal closed', 'AbortError'))
        coreutils.dispose()
      },
      setDimensions(dimensions) {
        columns = dimensions.columns
      },
      handleInput(data) {
        if (running && data.includes('\x03')) {
          emit('^C\r\n')
          running.abort(new DOMException('Interrupted', 'AbortError'))
        }
      }
    },
    run(command, signal) {
      const result = queue.then(() => runNow(command, signal))
      queue = result.catch(() => undefined)
      return result
    }
  }
}
