import type { Files } from './files'
import { resolvePath } from './paths'

const GIT_COMMANDS = ['init', 'clone', 'status', 'add', 'commit', 'log', 'diff', 'branch', 'checkout', 'switch', 'restore', 'reset', 'remote', 'config', 'pull', 'push']

export interface Completion {
  // Where the word being completed starts
  start: number
  candidates: string[]
}

export interface CompletionContext {
  files: Files
  cwd: string
  home: string
  commandNames(): string[]
}

const unescape = (text: string) => text.replace(/\\(.)/g, '$1')
const shellEscape = (name: string) => name.replace(/[^\w@%+=:,./~-]/g, '\\$&')

export function formatPrompt(home: string, cwd: string): { text: string; width: number } {
  const shown = cwd === home ? '~' : cwd.startsWith(`${home}/`) ? `~${cwd.slice(home.length)}` : cwd
  return { text: `\x1b[1;32mfrc\x1b[0m:\x1b[1;34m${shown}\x1b[0m$ `, width: `frc:${shown}$ `.length }
}

export async function completeLine(line: string, cursor: number, ctx: CompletionContext): Promise<Completion> {
  const before = line.slice(0, cursor)
  let start = before.length
  while (start > 0 && !(/[\s|&;<>]/.test(before[start - 1]) && before[start - 2] !== '\\')) start--
  const word = before.slice(start)
  const previous = before.slice(0, start).trimEnd()
  const commandPosition = previous === '' || /[|&;]$/.test(previous)
  const words = previous.split(/[|&;]/).pop()!.trim().split(/\s+/)

  if (commandPosition && !word.includes('/')) {
    return { start, candidates: ctx.commandNames().filter((n) => n.startsWith(word)).map((n) => `${n} `) }
  }
  if (!commandPosition && words[0] === 'git' && words.length === 1) {
    return { start, candidates: GIT_COMMANDS.filter((n) => n.startsWith(word)).map((n) => `${n} `) }
  }

  const slash = word.lastIndexOf('/')
  const dirPart = word.slice(0, slash + 1)
  const prefix = unescape(word.slice(slash + 1))
  const expanded = dirPart.startsWith('~') ? `${ctx.home}${dirPart.slice(1)}` : unescape(dirPart)
  const entries = await ctx.files.list(resolvePath(ctx.cwd, expanded || '.')).catch(() => [])
  const candidates = entries
    .filter(([name]) => name.startsWith(prefix) && (prefix.startsWith('.') || !name.startsWith('.')))
    .sort((a, b) => (a[0] < b[0] ? -1 : 1))
    .map(([name, dir]) => `${dirPart}${shellEscape(name)}${dir ? '/' : ' '}`)
  return { start, candidates }
}
