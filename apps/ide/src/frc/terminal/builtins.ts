import type * as vscode from 'vscode'
import type { Coreutils } from './coreutils'
import type { Files } from './files'
import type { GitIo } from '../../git/cli'
import type { FrcServices } from '../services'
import type { Io } from './io'
import { resolvePath } from './paths'

export interface ShellHost {
  api: typeof vscode
  files: Files
  coreutils: Coreutils
  services: FrcServices
  git(args: string[], io: GitIo, cwd: string, signal: AbortSignal): Promise<number>
  githubToken(prompt: boolean): Promise<string | undefined>
  githubLogin(token?: string): Promise<boolean>
  githubLogout(): Promise<boolean>
  home: string
  history: string[]
  columns(): number
  exit(code: number): void
}

export interface Session {
  readonly host: ShellHost
  readonly vars: Map<string, string>
  cwd: string
}

export type Builtin = (args: string[], io: Io, session: Session, signal: AbortSignal) => Promise<number> | number

const HELP = `FRC Dev shell. A bash shell in your browser, on the workspace files.

  Language  pipes, redirects, && || ;, $VAR, $(cmd), arrays, functions, aliases,
            if, for, while, case, [[ ]], $(( )), here strings, globs, brace expansion
  Files     ls cat cp mv rm mkdir touch head tail sort uniq wc cut tr tee find grep rg
            sed awk jq diff tree tar gzip xargs, and the rest of coreutils
  Git       git status add commit log diff branch checkout restore reset remote config pull push clone init
  GitHub    gh api auth repo issue pr
  Build     ./gradlew build, ./gradlew simulateJava (2026) or run (2027), ./gradlew clean
  Shell     cd pwd echo export type which help history clear code <file>
  Keys      Tab completes, Up and Down walk the history, Ctrl+C interrupts, Ctrl+L clears

Not available: background jobs (& runs in the foreground), trap, programs that wait for typed input.
`

export function createBuiltins(): Record<string, Builtin> {
  return {
    clear: (_args, io) => (io.stdout.write('\x1b[2J\x1b[3J\x1b[H'), 0),
    help: (_args, io) => (io.stdout.write(HELP), 0),
    history: (_args, io, session) => {
      session.host.history.forEach((line, i) => io.stdout.write(`${String(i + 1).padStart(5)}  ${line}\n`))
      return 0
    },
    code: async (args, io, session) => {
      const { api, files } = session.host
      if (args.length === 0) {
        io.stderr.write('usage: code <file>...\n')
        return 1
      }
      for (const arg of args) {
        const path = resolvePath(session.cwd, arg)
        const stat = await files.stat(path)
        if (!stat || stat.dir) {
          io.stderr.write(`code: ${arg}: ${stat ? 'Is a directory' : 'No such file'}\n`)
          return 1
        }
        await api.window.showTextDocument(api.Uri.file(path), { preview: false })
      }
      return 0
    }
  }
}
