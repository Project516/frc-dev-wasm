import { Bash, defineCommand, type Command, type CommandName } from 'just-bash/browser'
import { createBuiltins, type Builtin, type Session, type ShellHost } from './builtins'
import { copyRecursive, isRecursiveCopy, isRecursiveRemove, move, removeRecursive, removesRoot } from './fileops'
import { gh } from './gh'
import { gradlew } from './gradle'
import { completeLine, formatPrompt, type Completion } from './interactive'
import { Collector, type Io, type Sink } from './io'
import { dirname, join, resolvePath } from './paths'
import { GRADLEW, HELP, LIVE, TTY, rewriteScript, type Script, type Statement } from './rewrite'
import { tar } from './tar'
import { UTILITIES } from './utilities'
import { WorkspaceFs } from './workspacefs'

// just-bash runs these itself: the language needs them, or it does them well
const NATIVE: CommandName[] = [
  'echo', 'printf', 'pwd', 'sleep', 'env', 'timeout', 'time', 'xargs', 'sed', 'awk', 'jq', 'diff', 'gzip', 'gunzip', 'zcat', 'tree',
  'rg', 'grep', 'egrep', 'fgrep', 'find', 'du', 'column', 'strings', 'rev', 'file', 'stat', 'readlink', 'hostname', 'whoami',
  'bash', 'sh', 'alias', 'unalias'
]
const KEYWORDS = ['cd', 'export', 'unset', 'read', 'type', 'set', 'shopt', 'source', 'local', 'declare', 'exit', 'test', 'true', 'false', 'return', 'shift']
const NOT_UUTILS = new Set(['printf', 'true', 'false', 'test'])
const UTILITY_SET = new Set(UTILITIES)
const STATE = '__frc_state'
const CUT_OFF_STATUS = 141

// What the next line needs from this one, besides the variables and the working directory:
// arrays, aliases, shell options and the positional parameters
const SAVE_STATE = `{ echo $?; declare -p; alias; shopt -p; echo ---; printf ' %q' "$@"; echo; } | ${STATE}`
const ARRAY = /^declare -(\w+) (\w+)=(.*)$/
const SPECIAL_ARRAY = /^(PIPESTATUS|BASH_\w+|FUNCNAME|DIRSTACK|GROUPS|COMP_\w+)$/

type Mode = 'pipe' | 'tty' | 'live'
type Run = (args: string[], io: Io, ctx: { cwd: string; env: Map<string, string>; signal?: AbortSignal }) => Promise<number> | number

// A part of a line that runs as one script
interface Group {
  script: string
  // Part of an && or || list: it runs when the status of what came before says so
  after?: '&&' | '||'
  // Statements that print nothing as they go can share a script
  joinable?: boolean
  functions: Array<{ name: string; script: string }>
}

const LATIN1_CHUNK = 8192
const strict = new TextDecoder('utf-8', { fatal: true })
const decoder = new TextDecoder()

// Text goes through as text, which is fast. Other bytes go as a string with one character per byte.
function toOutput(bytes: Uint8Array): { stdout: string; stdoutKind: 'text' | 'bytes' } {
  try {
    return { stdout: strict.decode(bytes), stdoutKind: 'text' }
  } catch {
    let out = ''
    for (let i = 0; i < bytes.length; i += LATIN1_CHUNK) out += String.fromCharCode(...bytes.subarray(i, i + LATIN1_CHUNK))
    return { stdout: out, stdoutKind: 'bytes' }
  }
}

function fromLatin1(text: string): Uint8Array {
  const bytes = new Uint8Array(text.length)
  for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i)
  return bytes
}

// Runs a line with just-bash. just-bash forgets everything between scripts, so what the next line
// needs is carried over here. Commands that print for a long time, such as ./gradlew, get a script
// of their own, so that their output appears while they run.
export class Shell implements Session {
  readonly vars = new Map<string, string>()
  cwd: string
  status = 0
  private readonly bash: Bash
  private readonly parser: Bash
  private readonly custom = new Set<string>()
  private readonly functions = new Map<string, string>()
  private header: string[] = []
  private aliases: string[] = []
  private saved: string | undefined
  private pick: [statement: number, pipeline?: number] | undefined

  constructor(
    readonly host: ShellHost,
    private readonly out: Sink,
    private readonly onStatus: (text: string) => void
  ) {
    this.cwd = host.home
    for (const [k, v] of Object.entries({ HOME: host.home, PWD: host.home, USER: 'frc', SHELL: 'frc-shell', TERM: 'xterm-256color', LANG: 'C.UTF-8' })) this.vars.set(k, v)
    const customCommands = this.customCommands()
    this.bash = new Bash({
      fs: new WorkspaceFs(host.files, host.home),
      cwd: host.home,
      env: Object.fromEntries(this.vars),
      commands: NATIVE,
      customCommands,
      executionLimits: { maxExecutionTimeMs: Infinity }
    })
    this.parser = new Bash({ commands: [] })
    this.parser.registerTransformPlugin({
      name: 'frc',
      transform: ({ ast }) => {
        const script = ast as unknown as Script
        const statements = rewriteScript(script, this.custom, new Set([...this.functions.keys(), ...this.aliases]))
        const [i, j] = this.pick ?? []
        const chosen = i === undefined ? script.statements : [j === undefined ? script.statements[i] : { ...script.statements[i], pipelines: [script.statements[i].pipelines[j]], operators: [] }]
        return { ast: { ...ast, statements: chosen } as unknown as typeof ast, metadata: { statements } }
      }
    })
  }

  private customCommands(): Command[] {
    const commands: Command[] = []
    // A command is registered three times. The copy for the terminal knows its output is shown there,
    // and the live copy writes it as it goes.
    const add = (name: string, run: Run) => {
      this.custom.add(name)
      for (const [prefix, mode] of [['', 'pipe'], [TTY, 'tty'], [LIVE, 'live']] as const) {
        commands.push(defineCommand(prefix + name, (args, ctx) => this.call(name, mode, run, args, ctx)))
      }
    }
    const session = (cwd: string): Session => ({ host: this.host, vars: this.vars, cwd })
    const builtins = createBuiltins()
    const wrap = (builtin: Builtin): Run => (args, io, ctx) => builtin(args, io, session(ctx.cwd), ctx.signal ?? new AbortController().signal)
    add(HELP, wrap(builtins.help))
    for (const name of ['history', 'code', 'clear']) add(name, wrap(builtins[name]))
    add('which', (args, io) => {
      const names = new Set(this.commandNames())
      for (const name of args) if (!names.has(name)) io.stderr.write(`which: no ${name} in (/usr/bin:/bin)\n`)
      for (const name of args) if (names.has(name)) io.stdout.write(`/usr/bin/${name}\n`)
      return args.every((name) => names.has(name)) ? 0 : 1
    })

    const { files } = this.host
    for (const name of UTILITIES) {
      if (NOT_UUTILS.has(name)) continue
      add(name, (args, io, ctx) => {
        const ops = { files, cwd: ctx.cwd }
        const signal = ctx.signal ?? new AbortController().signal
        if (name === 'cp' && isRecursiveCopy(args)) return copyRecursive(args, io, ops)
        if (name === 'rm') {
          if (removesRoot(args, ctx.cwd)) {
            io.stderr.write("rm: it is dangerous to operate on '/'\n")
            return 1
          }
          if (isRecursiveRemove(args)) return removeRecursive(args, io, ops)
        }
        return this.utility(name, args, io, ctx.cwd, ctx.env, signal)
      })
    }
    add('mv', (args, io, ctx) => move(args, io, { files, cwd: ctx.cwd }))
    add('tar', (args, io, ctx) => tar(args, io, { files, cwd: ctx.cwd }))
    add('git', (args, io, ctx) =>
      this.host.git(args, { out: (t) => io.stdout.write(t), err: (t) => io.stderr.write(t), tty: io.stdoutIsTty, columns: this.host.columns() }, ctx.cwd, ctx.signal ?? new AbortController().signal)
    )
    add('gh', (args, io, ctx) => gh(this.host, args, io, ctx.cwd, ctx.signal ?? new AbortController().signal))
    add(GRADLEW, async (args, io, ctx) => {
      const name = args[0]
      const path = name.includes('/') ? resolvePath(ctx.cwd, name) : join(ctx.cwd, name)
      if (!(await files.stat(path))) {
        io.stderr.write(`sh: ${name}: No such file or directory\n`)
        return 127
      }
      return gradlew(args.slice(1), io, { api: this.host.api, services: this.host.services, root: dirname(path), signal: ctx.signal ?? new AbortController().signal })
    })
    commands.push(
      defineCommand(STATE, async (_args, ctx) => {
        this.saved = decoder.decode(fromLatin1(ctx.stdin as unknown as string))
        return { stdout: '', stderr: '', exitCode: Number(this.saved.split('\n')[0]) }
      })
    )
    return commands
  }

  private async call(name: string, mode: Mode, run: Run, args: string[], ctx: { cwd: string; env: Map<string, string>; stdin: unknown; signal?: AbortSignal }) {
    const stdout = new Collector()
    const stderr = new Collector()
    const io: Io = {
      stdin: ctx.stdin ? fromLatin1(ctx.stdin as string) : undefined,
      stdout: mode === 'live' ? this.out : stdout,
      stderr: mode === 'live' ? this.out : stderr,
      stdoutIsTty: mode !== 'pipe'
    }
    let exitCode: number
    try {
      exitCode = await run(args, io, { cwd: ctx.cwd, env: ctx.env, signal: ctx.signal })
    } catch (error) {
      if (ctx.signal?.aborted) exitCode = 130
      else {
        io.stderr.write(`${name}: ${error instanceof Error ? error.message : String(error)}\n`)
        exitCode = 1
      }
    }
    // A utility that reached the output limit was stopped. What it wrote so far is not passed on.
    const cutOff = exitCode === CUT_OFF_STATUS && UTILITY_SET.has(name)
    if (cutOff) io.stderr.write(`${name}: output was cut off\n`)
    return { ...(cutOff ? { stdout: '', stdoutKind: 'text' as const } : toOutput(stdout.bytes())), stderr: decoder.decode(stderr.bytes()), exitCode }
  }

  private async utility(name: string, args: string[], io: Io, cwd: string, env: Map<string, string>, signal: AbortSignal): Promise<number> {
    const vars = Object.fromEntries([...env].filter(([k]) => /^[A-Za-z_]\w*$/.test(k)))
    vars.COLUMNS = String(this.host.columns())
    try {
      return await this.host.coreutils.run({
        command: name,
        args,
        env: vars,
        cwd,
        stdin: io.stdin,
        stdoutIsTty: io.stdoutIsTty,
        onOutput: (fd, data) => (fd === 1 ? io.stdout : io.stderr).write(data),
        onStatus: this.onStatus,
        signal
      })
    } catch (error) {
      this.onStatus('')
      if (signal.aborted) return 130
      io.stderr.write(`${name}: ${error instanceof Error ? error.message : String(error)}\n`)
      return 1
    }
  }

  private commandNames(): string[] {
    const own = ['help', 'history', 'code', 'clear', 'which', 'mv', 'tar', 'git', 'gh', ...UTILITIES]
    return [...new Set([...own, ...NATIVE, ...KEYWORDS])].sort()
  }

  prompt(): { text: string; width: number } {
    return formatPrompt(this.host.home, this.cwd)
  }

  complete(line: string, cursor: number): Promise<Completion> {
    return completeLine(line, cursor, { files: this.host.files, cwd: this.cwd, home: this.host.home, commandNames: () => this.commandNames() })
  }

  // Splits a line into the scripts it runs as. The whole line is one script unless a statement prints while it runs.
  private split(line: string): Group[] {
    this.pick = undefined
    const whole = this.parser.transform(line)
    const info = (whole.metadata as { statements: Statement[] }).statements
    const scriptOf = (i: number, j?: number) => {
      if (info.length === 1 && j === undefined) return whole.script
      this.pick = [i, j]
      return this.parser.transform(line).script
    }
    const functionOf = (i: number) => (info[i].fn ? [{ name: info[i].fn, script: scriptOf(i) }] : [])
    if (!info.some((s) => s.live.includes(true))) return [{ script: whole.script, functions: info.flatMap((_, i) => functionOf(i)) }]
    const groups: Group[] = []
    for (const [i, s] of info.entries()) {
      const last = groups[groups.length - 1]
      if (!s.live.includes(true)) {
        if (last?.joinable) {
          last.script += `\n${scriptOf(i)}`
          last.functions.push(...functionOf(i))
        } else groups.push({ script: scriptOf(i), joinable: true, functions: functionOf(i) })
      } else if (s.live.length === 1) groups.push({ script: scriptOf(i), functions: [] })
      else {
        for (const j of s.live.keys()) groups.push({ script: scriptOf(i, j), after: j > 0 ? (s.operators[j - 1] as '&&' | '||') : undefined, functions: [] })
      }
    }
    return groups
  }

  private restore(text: string): void {
    const lines = text.split('\n')
    const end = lines.indexOf('---')
    const header: string[] = []
    this.aliases = []
    for (const line of lines.slice(1, end)) {
      const array = ARRAY.exec(line)
      if (array) {
        const [, flags, name, value] = array
        if (!SPECIAL_ARRAY.test(name) && /[aA]/.test(flags)) header.push(flags.includes('A') ? `declare -A ${name}; ${name}=${value}` : `${name}=${value}`)
      } else if (/^shopt -s /.test(line)) header.push(line)
      else {
        // just-bash prints a quote inside an alias value without escaping it
        const alias = /^alias (\S+)='(.*)'$/.exec(line)
        if (alias) {
          header.push(`alias ${alias[1]}='${alias[2].replaceAll("'", "'\\''")}'`)
          this.aliases.push(alias[1])
        }
      }
    }
    const positional = lines[end + 1]
    if (positional?.trim()) header.push(`set --${positional}`)
    this.header = header
  }

  async runLine(line: string, signal: AbortSignal): Promise<number> {
    let groups: Group[]
    try {
      groups = this.split(line)
    } catch (error) {
      this.out.write(`sh: ${error instanceof Error ? error.message : String(error)}\n`)
      return (this.status = 2)
    }
    for (const group of groups) {
      if (signal.aborted) return (this.status = 130)
      if (group.after === '&&' ? this.status !== 0 : group.after === '||' && this.status === 0) continue
      if (!group.script.trim()) continue
      const script = ['shopt -s expand_aliases', ...this.header, ...this.functions.values(), ...(this.status !== 0 ? [`(exit ${this.status})`] : []), group.script, SAVE_STATE].join('\n')
      this.saved = undefined
      try {
        const env = Object.fromEntries(this.vars)
        env.COLUMNS = String(this.host.columns())
        const result = await this.bash.exec(script, { env, cwd: this.cwd, signal })
        if (result.stdout) this.out.write(result.stdoutKind === 'bytes' ? fromLatin1(result.stdout) : result.stdout)
        if (result.stderr && !signal.aborted) this.out.write(result.stderr)
        this.status = signal.aborted ? 130 : result.exitCode
        this.vars.clear()
        for (const [k, v] of Object.entries(result.env)) if (/^[A-Za-z_]\w*$/.test(k)) this.vars.set(k, v)
        const pwd = result.env.PWD
        if (pwd?.startsWith('/')) this.cwd = pwd
        for (const fn of group.functions) this.functions.set(fn.name, fn.script)
        if (this.saved !== undefined) this.restore(this.saved)
        // The state is not saved when the script ended in the middle
        else if (!signal.aborted && /\bexit\b/.test(group.script)) this.host.exit(this.status)
      } catch (error) {
        if (signal.aborted) return (this.status = 130)
        this.out.write(`sh: ${error instanceof Error ? error.message : String(error)}\n`)
        this.status = 1
      }
    }
    return this.status
  }
}
