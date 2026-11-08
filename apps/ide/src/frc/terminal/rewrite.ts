// Prepares a parsed line before just-bash runs it. just-bash returns the output of a script when
// the script ends, so this decides which commands may write to the terminal as they go.

export const TTY = '__tty_'
export const LIVE = '__live_'
export const GRADLEW = '__gradlew'
export const HELP = '__help'

interface Word {
  type: 'Word'
  parts: Array<{ type: string; value?: string }>
}
interface Redirection {
  type: 'Redirection'
  fd: number | null
  operator: string
  target: Word
}
interface Node {
  type: string
  name?: Word | string | null
  args?: Word[]
  redirections?: Redirection[]
  [key: string]: unknown
}
interface Pipeline {
  commands: Node[]
}
interface StatementNode {
  pipelines: Pipeline[]
  operators: string[]
  background?: boolean
}
export interface Script {
  statements: StatementNode[]
}

export interface Statement {
  // The function it defines
  fn?: string
  // Whether each of its pipelines prints while it runs
  live: boolean[]
  operators: string[]
}

// Commands that print nothing in the normal case, so they cannot come out of order
const QUIET = new Set(['cd', 'export', 'unset', 'read', 'shift', 'local', 'true', 'false', ':', 'test', '[', 'exit', 'return', 'break', 'continue', 'let', 'set', 'sleep'])
const FILE_OPERATORS = new Set(['>', '>>', '>|', '&>', '&>>'])
// Options of xargs that take a value, which it also accepts glued to the option
const XARGS_VALUE = /^-([nLPIdsE])(.+)$/

const word = (value: string): Word => ({ type: 'Word', parts: [{ type: 'Literal', value }] })

export const literal = (name: Word | string | null | undefined): string | undefined =>
  typeof name === 'string' ? name : name?.parts.length === 1 && name.parts[0].type === 'Literal' ? name.parts[0].value : undefined

const toFile = (n: Node) => n.redirections?.some((r) => (r.fd === null || r.fd === 1) && FILE_OPERATORS.has(r.operator)) ?? false
const redirectsStdout = (n: Node) => toFile(n) || (n.redirections?.some((r) => r.fd === null && r.operator === '>&') ?? false)
const redirectsStderr = (n: Node) => n.redirections?.some((r) => r.fd === 2 || r.operator === '&>' || r.operator === '&>>') ?? false

// The commands of a pipeline that print to the terminal, and whether anything else does
interface Scan {
  calls: Node[]
  unsafe: boolean
}

// `custom` names the commands the shell implements, which can print as they go. `outer` names
// the functions and aliases of the earlier lines, which take over a command of the same name.
// Rewrites the script in place.
export function rewriteScript(script: Script, custom: ReadonlySet<string>, outer: ReadonlySet<string>): Statement[] {
  const defined = script.statements.map((statement) => {
    const [first] = statement.pipelines
    const definition = statement.pipelines.length === 1 && first.commands.length === 1 && first.commands[0].type === 'FunctionDef' ? first.commands[0] : undefined
    return definition ? String(definition.name) : undefined
  })
  const shadowed = new Set([...outer, ...defined.filter((name) => name !== undefined)])
  const ignore = (node: unknown) => visit(node, false, { calls: [], unsafe: false })

  function simple(n: Node, tty: boolean, scan: Scan): void {
    ignore([n.assignments, n.name, n.args, n.redirections])
    if (n.name === null || n.name === undefined) return
    let name = literal(n.name)
    if (name === undefined) {
      scan.unsafe = true
      return
    }
    const args = n.args ?? []
    if (name === 'help' && args.length === 0) name = HELP
    else if (/(^|\/)gradlew$/.test(name)) {
      args.unshift(word(name))
      name = GRADLEW
    } else if (name === 'xargs') {
      for (let i = 0; i < args.length && literal(args[i])?.startsWith('-'); i++) {
        const glued = XARGS_VALUE.exec(literal(args[i])!)
        if (glued) args.splice(i, 1, word(`-${glued[1]}`), word(glued[2]))
      }
    }
    n.name = word(name)
    if (!tty) return
    const eligible = custom.has(name) && !shadowed.has(name) && !redirectsStdout(n) && !redirectsStderr(n)
    // The error output goes where the output goes, in the order it was written
    if (!redirectsStderr(n)) (n.redirections ??= []).unshift({ type: 'Redirection', fd: 2, operator: '>&', target: word('1') })
    if (eligible) scan.calls.push(n)
    else if (!QUIET.has(name) && !toFile(n)) scan.unsafe = true
  }

  // `tty` is true where output goes straight to the terminal: not into a pipe, a file or $( )
  function visit(node: unknown, tty: boolean, scan: Scan): void {
    if (Array.isArray(node)) return node.forEach((child) => visit(child, tty, scan))
    if (!node || typeof node !== 'object') return
    const n = node as Node
    switch (n.type) {
      case 'SimpleCommand':
        return simple(n, tty, scan)
      case 'CommandSubstitution':
      case 'FunctionDef':
        return visit(Object.values(n), false, { calls: [], unsafe: false })
      case 'Pipeline': {
        const commands = (n as unknown as Pipeline).commands
        return commands.forEach((command, i) => visit(command, tty && i === commands.length - 1, scan))
      }
      case 'Statement':
        return visit(n.pipelines, tty && !n.background, scan)
    }
    const inner = tty && !n.redirections?.length
    for (const value of Object.values(n)) visit(value, inner, scan)
  }

  return script.statements.map((statement, i) => {
    const live = statement.pipelines.map((pipeline) => {
      const scan: Scan = { calls: [], unsafe: false }
      visit(pipeline, !statement.background, scan)
      const prints = !scan.unsafe && scan.calls.length > 0
      for (const call of scan.calls) call.name = word(`${prints ? LIVE : TTY}${literal(call.name)}`)
      return prints
    })
    return { fn: defined[i], live, operators: statement.operators }
  })
}
