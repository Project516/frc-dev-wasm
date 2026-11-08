// `git` for the terminal. It drives the same Repository, GitHub and isomorphic-git code as the
// Git commands, so the Source Control view stays in sync with what the terminal does.
import type * as vscode from 'vscode'
import { resolvePath } from '../frc/terminal/paths'
import type { Git, Progress } from './github'
import { parseGitHubRepo } from './objects'
import type { Repository } from './repository'
import { classify, type Change } from './status'

export interface GitIo {
  out(text: string): void
  err(text: string): void
  // Set when both go to the terminal, which is `columns` wide
  tty?: boolean
  columns?: number
}

export interface GitDeps {
  api: typeof vscode
  git: Git
  // The repository that holds a directory, if any
  repoFor(dir: string): Promise<Repository | undefined>
  init(dir: string): Promise<void>
  author(repo: Repository): Promise<{ name: string; email: string }>
  sync(repo: Repository, direction: 'push' | 'pull', progress: Progress): Promise<string>
  clone(target: vscode.Uri, input: string, progress: Progress, branch?: string): Promise<void>
  discover(): Promise<void>
}

class UsageError extends Error {}

const SUPPORTED = 'init, clone, status, add, commit, log, diff, branch, checkout, switch, restore, reset, remote, config, pull, push'

const LABEL = { A: 'new file', M: 'modified', D: 'deleted', U: 'untracked' } as const

const decoder = new TextDecoder()

// Number of lines two versions differ by (Myers, distance only)
function lineDistance(a: string[], b: string[]): number {
  const n = a.length
  const m = b.length
  const offset = n + m + 1
  const v = new Int32Array(2 * offset + 2)
  for (let d = 0; d <= n + m; d++) {
    for (let k = -d; k <= d; k += 2) {
      let x = k === -d || (k !== d && v[offset + k - 1] < v[offset + k + 1]) ? v[offset + k + 1] : v[offset + k - 1] + 1
      let y = x - k
      while (x < n && y < m && a[x] === b[y]) {
        x++
        y++
      }
      v[offset + k] = x
      if (x >= n && y >= m) return d
    }
  }
  return n + m
}

function changedLines(before: Uint8Array, after: Uint8Array): { added: number; removed: number } | undefined {
  if (before.subarray(0, 8000).includes(0) || after.subarray(0, 8000).includes(0)) return undefined
  const a = decoder.decode(before).split('\n')
  const b = decoder.decode(after).split('\n')
  let start = 0
  while (start < a.length && start < b.length && a[start] === b[start]) start++
  let endA = a.length
  let endB = b.length
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--
    endB--
  }
  const x = a.slice(start, endA)
  const y = b.slice(start, endB)
  if (x.length + y.length > 20_000) return { added: y.length, removed: x.length }
  const common = (x.length + y.length - lineDistance(x, y)) / 2
  return { added: y.length - common, removed: x.length - common }
}

const relative = (repo: Repository, cwd: string, arg: string) => {
  const path = resolvePath(cwd, arg)
  if (path !== repo.root.path && !path.startsWith(`${repo.root.path}/`)) throw new UsageError(`'${arg}' is outside repository at '${repo.root.path}'`)
  return path.slice(repo.root.path.length + 1)
}

const under = (path: string, prefix: string) => prefix === '' || path === prefix || path.startsWith(`${prefix}/`)

// Splits short and long flags. Anything not in `allowed` or `valued` is refused, so an option that
// would change what a command does is never ignored.
function parseFlags(args: string[], allowed: string[], valued: string[] = []) {
  const flags = new Map<string, string[]>()
  const operands: string[] = []
  let dashdash = false
  const put = (name: string, display: string, value = '') => {
    if (!allowed.includes(name) && !valued.includes(name)) throw new UsageError(`option '${display}' is not supported in this terminal`)
    flags.set(name, [...(flags.get(name) ?? []), value])
  }
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]
    if (arg === '--') {
      dashdash = true
      operands.push(...args.slice(i + 1))
      break
    }
    if (arg.startsWith('--')) {
      const [name, value] = arg.slice(2).split(/=(.*)/s)
      put(name, `--${name}`, value ?? (valued.includes(name) ? (args[++i] ?? '') : ''))
    } else if (arg.startsWith('-') && arg.length > 1) {
      for (let j = 1; j < arg.length; j++) {
        if (valued.includes(arg[j])) {
          put(arg[j], `-${arg[j]}`, arg.slice(j + 1) || (args[++i] ?? ''))
          break
        }
        put(arg[j], `-${arg[j]}`)
      }
    } else operands.push(arg)
  }
  return { flags, operands, dashdash }
}

// Revisions (HEAD~1, a commit, a branch) where a path is expected. Nothing here understands them.
async function rejectRevisions(deps: GitDeps, repo: Repository, cwd: string, operands: string[]): Promise<void> {
  const branches = await repo.listBranches()
  for (const operand of operands) {
    const looksLikeRevision = /^HEAD|[~^]|^[0-9a-f]{7,40}$/.test(operand) || branches.includes(operand)
    if (looksLikeRevision && !(await exists(deps.api, repo, relative(repo, cwd, operand)))) {
      throw new UsageError(`'${operand}': revisions are not supported here, only paths`)
    }
  }
}

export async function runGit(deps: GitDeps, args: string[], io: GitIo, cwd: string, signal: AbortSignal): Promise<number> {
  const { api, git } = deps
  const [sub, ...rest] = args
  const progress = (): Progress => ({
    report: (message) => io.out(`\r${message}\x1b[K`),
    isCancelled: () => signal.aborted
  })
  const clearProgress = () => io.out('\r\x1b[K')
  const requireRepo = async (): Promise<Repository> => {
    const repo = await deps.repoFor(cwd)
    if (!repo) throw new UsageError('not a git repository (or any of the parent directories): .git')
    return repo
  }
  const state = async (repo: Repository) => {
    const { fs, dir } = repo.ctx
    return classify((await git.statusMatrix({ fs, dir })) as Array<[string, number, number, number]>)
  }

  try {
    switch (sub) {
      case undefined:
      case 'help':
      case '--help':
        io.out(`usage: git <command> [<args>]\nSupported commands: ${SUPPORTED}\n`)
        return sub === undefined ? 1 : 0
      case '--version':
      case 'version':
        io.out('git version (isomorphic-git)\n')
        return 0

      case 'init': {
        const { operands } = parseFlags(rest, ['q', 'quiet'])
        if (operands.length > 1) throw new UsageError('usage: git init [directory]')
        const path = resolvePath(cwd, operands[0] ?? '.')
        await deps.init(path)
        io.out(`Initialized empty Git repository in ${path}/.git/\n`)
        return 0
      }

      case 'clone': {
        // --depth is accepted because clones are shallow already
        const { flags, operands } = parseFlags(rest, ['q', 'quiet', 'single-branch', 'no-tags'], ['depth', 'b', 'branch'])
        const [input, folder] = operands
        if (!input || operands.length > 2) throw new UsageError('usage: git clone [-b <branch>] [--depth <n>] <repository> [<directory>]')
        const branch = flags.get('b')?.[0] ?? flags.get('branch')?.[0]
        const name = parseGitHubRepo(input)?.repo ?? input.replace(/\/+$/, '').replace(/\.git$/, '').split('/').pop()!
        const target = api.Uri.file(resolvePath(cwd, folder ?? name))
        io.out(`Cloning into '${folder ?? name}'...\n`)
        try {
          await deps.clone(target, input, progress(), branch)
        } finally {
          clearProgress()
        }
        await deps.discover()
        await api.commands.executeCommand('workbench.files.action.refreshFilesExplorer')
        return 0
      }

      case 'status': {
        const repo = await requireRepo()
        const { flags, operands } = parseFlags(rest, ['s', 'short'])
        if (operands.length > 0) throw new UsageError('pathspecs are not supported for git status')
        const { staged, changes } = await state(repo)
        const short = flags.has('s') || flags.has('short')
        if (short) {
          const paths = new Set([...staged, ...changes].map((c) => c.path))
          for (const path of [...paths].sort()) {
            const s = staged.find((c) => c.path === path)
            const w = changes.find((c) => c.path === path)
            io.out(w?.kind === 'U' ? `?? ${path}\n` : `${s?.kind ?? ' '}${w?.kind ?? ' '} ${path}\n`)
          }
          return 0
        }
        io.out(`On branch ${(await repo.branch()) ?? '(detached HEAD)'}\n`)
        const list = (title: string, items: Change[]) => {
          if (items.length === 0) return
          io.out(`\n${title}\n`)
          for (const c of items) io.out(c.kind === 'U' ? `\t${c.path}\n` : `\t${(LABEL[c.kind] + ':').padEnd(12)}${c.path}\n`)
        }
        list('Changes to be committed:', staged)
        list('Changes not staged for commit:', changes.filter((c) => c.kind !== 'U'))
        list('Untracked files:', changes.filter((c) => c.kind === 'U'))
        if (staged.length === 0 && changes.length === 0) io.out('\nnothing to commit, working tree clean\n')
        return 0
      }

      case 'add': {
        const repo = await requireRepo()
        const { flags, operands } = parseFlags(rest, ['A', 'all', 'u', 'update'])
        const { changes } = await state(repo)
        const tracked = flags.has('u') || flags.has('update')
        const pool = tracked ? changes.filter((c) => c.kind !== 'U') : changes
        const paths = new Set<string>()
        if (flags.has('A') || flags.has('all')) pool.forEach((c) => paths.add(c.path))
        for (const operand of operands) {
          const prefix = relative(repo, cwd, operand)
          const matches = pool.filter((c) => under(c.path, prefix))
          if (matches.length === 0 && !(await exists(api, repo, prefix))) throw new UsageError(`pathspec '${operand}' did not match any files`)
          matches.forEach((c) => paths.add(c.path))
        }
        if (operands.length === 0 && !flags.has('A') && !flags.has('all') && !tracked) {
          io.err('Nothing specified, nothing added.\n')
          return 1
        }
        if (operands.length === 0 && tracked) pool.forEach((c) => paths.add(c.path))
        await repo.stage([...paths])
        return 0
      }

      case 'commit': {
        const repo = await requireRepo()
        const { flags, operands } = parseFlags(rest, ['a', 'all', 'allow-empty'], ['m', 'message'])
        if (operands.length > 0) throw new UsageError('pathspecs are not supported for git commit')
        const messages = [...(flags.get('m') ?? []), ...(flags.get('message') ?? [])]
        if (messages.length === 0) throw new UsageError('Aborting commit: no message given. Use -m "message".')
        if (flags.has('a') || flags.has('all')) {
          const { changes } = await state(repo)
          await repo.stage(changes.filter((c) => c.kind !== 'U').map((c) => c.path))
        }
        const { staged, changes } = await state(repo)
        if (staged.length === 0 && !flags.has('allow-empty')) {
          io.out(changes.length > 0 ? 'no changes added to commit (use "git add" or "git commit -a")\n' : 'nothing to commit, working tree clean\n')
          return 1
        }
        const message = messages.join('\n\n')
        const oid = await repo.commit(message, await deps.author(repo))
        io.out(`[${(await repo.branch()) ?? 'detached HEAD'} ${oid.slice(0, 7)}] ${message.split('\n')[0]}\n`)
        io.out(` ${staged.length} file${staged.length === 1 ? '' : 's'} changed\n`)
        return 0
      }

      case 'log': {
        const repo = await requireRepo()
        const numeric = rest.find((a) => /^-\d+$/.test(a))
        const { flags, operands } = parseFlags(
          rest.filter((a) => a !== numeric),
          ['oneline'],
          ['n', 'max-count']
        )
        if (operands.length > 0) throw new UsageError('revisions and paths are not supported for git log')
        const limit = Number(flags.get('n')?.[0] ?? flags.get('max-count')?.[0] ?? numeric?.slice(1) ?? 1000)
        const oneline = flags.has('oneline')
        let first = true
        for (const entry of await repo.log(limit)) {
          const { commit, oid } = entry
          if (oneline) io.out(`${oid.slice(0, 7)} ${commit.message.split('\n')[0]}\n`)
          else {
            const date = new Date(commit.author.timestamp * 1000).toString().replace(/ \(.*\)$/, '')
            io.out(`${first ? '' : '\n'}commit ${oid}\nAuthor: ${commit.author.name} <${commit.author.email}>\nDate:   ${date}\n\n`)
            io.out(`${commit.message.trimEnd().replace(/^/gm, '    ')}\n`)
          }
          first = false
        }
        return 0
      }

      case 'diff': {
        const repo = await requireRepo()
        const { flags, operands } = parseFlags(rest, ['cached', 'staged'])
        await rejectRevisions(deps, repo, cwd, operands)
        const cached = flags.has('cached') || flags.has('staged')
        const prefixes = operands.map((o) => relative(repo, cwd, o))
        const { staged, changes } = await state(repo)
        const files = (cached ? staged : changes.filter((c) => c.kind !== 'U')).filter((c) => prefixes.length === 0 || prefixes.some((p) => under(c.path, p)))
        let added = 0
        let removed = 0
        for (const { path } of files) {
          const before = await repo.content(path, cached ? 'HEAD' : 'INDEX')
          const after = cached ? await repo.content(path, 'INDEX') : await api.workspace.fs.readFile(repo.uri(path)).then((d) => d, () => new Uint8Array())
          const counts = changedLines(before, after)
          if (!counts) io.out(` ${path} | Bin\n`)
          else {
            added += counts.added
            removed += counts.removed
            io.out(` ${path} | ${counts.added + counts.removed} ${'+'.repeat(Math.min(counts.added, 30))}${'-'.repeat(Math.min(counts.removed, 30))}\n`)
          }
        }
        if (files.length > 0) {
          io.out(` ${files.length} file${files.length === 1 ? '' : 's'} changed, ${added} insertion${added === 1 ? '' : 's'}(+), ${removed} deletion${removed === 1 ? '' : 's'}(-)\n`)
        }
        return 0
      }

      case 'branch': {
        const repo = await requireRepo()
        const { flags, operands } = parseFlags(rest, ['d', 'D', 'delete', 'show-current'])
        const { fs, dir } = repo.ctx
        if (flags.has('show-current')) {
          io.out(`${(await repo.branch()) ?? ''}\n`)
        } else if (flags.has('d') || flags.has('D') || flags.has('delete')) {
          if (operands.length === 0) throw new UsageError('branch name required')
          const force = flags.has('D')
          const current = await repo.branch()
          for (const name of operands) {
            if (name === current) throw new UsageError(`cannot delete branch '${name}' used by the checked out worktree`)
            if (!force && !(await isMerged(deps, repo, name))) throw new UsageError(`the branch '${name}' is not fully merged. If you are sure you want to delete it, run 'git branch -D ${name}'`)
            await git.deleteBranch({ fs, dir, ref: name })
            io.out(`Deleted branch ${name}\n`)
          }
        } else if (operands.length > 1) {
          throw new UsageError('creating a branch at a start point is not supported: git branch <name>')
        } else if (operands.length === 1) {
          await git.branch({ fs, dir, ref: operands[0] })
        } else {
          const current = await repo.branch()
          for (const name of (await repo.listBranches()).sort()) io.out(`${name === current ? '* ' : '  '}${name}\n`)
        }
        return 0
      }

      case 'checkout':
      case 'switch': {
        const repo = await requireRepo()
        const { flags, operands, dashdash } = parseFlags(rest, [], sub === 'checkout' ? ['b'] : ['c', 'create'])
        const create = flags.get('b')?.[0] ?? flags.get('c')?.[0] ?? flags.get('create')?.[0]
        if (create) {
          if (operands.length > 0) throw new UsageError('creating a branch at a start point is not supported')
          await repo.createBranch(create)
          io.out(`Switched to a new branch '${create}'\n`)
          return 0
        }
        const [target] = operands
        if (!target) throw new UsageError('missing branch name')
        const branches = await repo.listBranches()
        if (sub === 'checkout' && (dashdash || !branches.includes(target))) {
          if (!dashdash) await rejectRevisions(deps, repo, cwd, operands)
          await restoreFromIndex(deps, repo, cwd, operands, io)
          return 0
        }
        if (operands.length > 1) throw new UsageError('too many arguments')
        await repo.checkout(target)
        io.out(`Switched to branch '${target}'\n`)
        return 0
      }

      case 'restore': {
        const repo = await requireRepo()
        const { flags, operands } = parseFlags(rest, ['staged', 'S', 'worktree', 'W'])
        if (operands.length === 0) throw new UsageError('you must specify path(s) to restore')
        const staged = flags.has('staged') || flags.has('S')
        if (staged && (flags.has('worktree') || flags.has('W'))) throw new UsageError('restoring both the index and the working tree is not supported')
        if (staged) await unstage(repo, cwd, operands)
        else await restoreFromIndex(deps, repo, cwd, operands, io)
        return 0
      }

      case 'reset': {
        const repo = await requireRepo()
        const { operands } = parseFlags(rest, [])
        // `git reset HEAD <paths>` is the usual way to unstage, and means the same as `git reset <paths>`
        const paths = operands[0] === 'HEAD' ? operands.slice(1) : operands
        await rejectRevisions(deps, repo, cwd, paths)
        await unstage(repo, cwd, paths)
        return 0
      }

      case 'remote': {
        const repo = await requireRepo()
        const { fs, dir } = repo.ctx
        const { flags, operands } = parseFlags(rest, ['v', 'verbose'])
        const [action, name, url] = operands
        if (action !== undefined && !['add', 'remove', 'rm'].includes(action)) throw new UsageError(`git remote ${action} is not supported`)
        if (action === 'add') {
          if (!name || !url) throw new UsageError('usage: git remote add <name> <url>')
          await git.addRemote({ fs, dir, remote: name, url })
        } else if (action === 'remove' || action === 'rm') {
          if (!name) throw new UsageError('usage: git remote remove <name>')
          await git.deleteRemote({ fs, dir, remote: name })
        } else {
          const verbose = flags.has('v') || flags.has('verbose')
          for (const remote of await git.listRemotes({ fs, dir })) {
            io.out(verbose ? `${remote.remote}\t${remote.url} (fetch)\n${remote.remote}\t${remote.url} (push)\n` : `${remote.remote}\n`)
          }
        }
        return 0
      }

      case 'config': {
        const { operands: words } = parseFlags(rest, ['global', 'local', 'get'])
        const [key, value] = words
        if (words.length > 2) throw new UsageError('usage: git config [--get] <key> [<value>]')
        if (!key) throw new UsageError('usage: git config [--get] <key> [<value>]')
        const userKey = /^user\.(name|email)$/.exec(key)
        if (value !== undefined) {
          if (userKey) await api.workspace.getConfiguration('git').update(key, value, api.ConfigurationTarget.Global)
          else {
            const repo = await requireRepo()
            await git.setConfig({ fs: repo.ctx.fs, dir: repo.ctx.dir, path: key, value })
          }
          return 0
        }
        let found = userKey ? api.workspace.getConfiguration('git').get<string>(key)?.trim() : undefined
        if (!found) {
          const repo = await deps.repoFor(cwd)
          if (repo) found = ((await git.getConfig({ fs: repo.ctx.fs, dir: repo.ctx.dir, path: key })) as string | undefined) ?? undefined
        }
        if (found === undefined) return 1
        io.out(`${found}\n`)
        return 0
      }

      case 'pull':
      case 'push': {
        const repo = await requireRepo()
        const { operands } = parseFlags(rest, [])
        if (operands.length > 0) throw new UsageError(`git ${sub} takes no remote or branch: it uses origin and the current branch`)
        try {
          io.out(`${await deps.sync(repo, sub, progress())}\n`)
        } finally {
          clearProgress()
        }
        return 0
      }

      default:
        io.err(`git: '${sub}' is not supported in this terminal. Supported: ${SUPPORTED}\n`)
        return 1
    }
  } catch (error) {
    if (signal.aborted) return 130
    const message = error instanceof Error ? error.message : String(error)
    if (message !== 'Cancelled') io.err(`${error instanceof UsageError ? 'fatal: ' : 'git: '}${message}\n`)
    return error instanceof UsageError ? 128 : 1
  }
}

async function exists(api: typeof vscode, repo: Repository, path: string): Promise<boolean> {
  return api.workspace.fs.stat(repo.uri(path)).then(() => true, () => false)
}

async function unstage(repo: Repository, cwd: string, operands: string[]): Promise<void> {
  const prefixes = operands.map((o) => relative(repo, cwd, o))
  const { fs, dir } = repo.ctx
  const { staged } = classify((await repo.ctx.git.statusMatrix({ fs, dir })) as Array<[string, number, number, number]>)
  await repo.unstage(staged.filter((c) => prefixes.length === 0 || prefixes.some((p) => under(c.path, p))).map((c) => c.path))
}

// Puts the staged version back in the working tree. A file that was staged and then edited again
// goes back to what was staged, not to HEAD.
async function restoreFromIndex(deps: GitDeps, repo: Repository, cwd: string, operands: string[], io: GitIo): Promise<void> {
  const prefixes = operands.map((o) => relative(repo, cwd, o))
  const { fs, dir } = repo.ctx
  const { changes } = classify((await repo.ctx.git.statusMatrix({ fs, dir })) as Array<[string, number, number, number]>)
  const tracked = changes.filter((c) => c.kind !== 'U' && prefixes.some((p) => under(c.path, p)))
  if (tracked.length === 0) io.err(`error: pathspec '${operands.join(' ')}' did not match any file(s) known to git\n`)
  for (const { path } of tracked) await deps.api.workspace.fs.writeFile(repo.uri(path), await repo.content(path, 'INDEX'))
  await repo.refresh()
}

// A branch is merged when its tip is HEAD or one of HEAD's ancestors
async function isMerged(deps: GitDeps, repo: Repository, name: string): Promise<boolean> {
  const { fs, dir } = repo.ctx
  try {
    const tip = await deps.git.resolveRef({ fs, dir, ref: `refs/heads/${name}` })
    const head = await deps.git.resolveRef({ fs, dir, ref: 'HEAD' })
    return tip === head || (await deps.git.isDescendent({ fs, dir, oid: head, ancestor: tip, depth: -1 }))
  } catch {
    return false
  }
}
