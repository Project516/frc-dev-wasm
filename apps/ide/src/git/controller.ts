import type { HttpClient } from 'isomorphic-git'
import type * as vscode from 'vscode'
import { GitHubAuth } from './auth'
import { childFolders, findRepositories, workspaceRoots } from './discover'
import { workspaceFolder } from '../workspace'
import { runRealGit, usesRealGit, type GitProcess, type Remotes } from './wasm/client'
import { runGit, type GitIo } from './cli'
import { createGitFs } from './fs'
import { cloneFromGitHub, pullFromGitHub, pushToGitHub, remoteOf, type Git, type Progress, type RepoContext } from './github'
import { loadIsoGit } from './isogit'
import { parseGitHubRepo } from './objects'
import { CONTENT_SCHEME, Repository, type Side } from './repository'
import type { Change } from './status'
import { plainText } from '../plainText'

type Args = unknown[]

const decoder = new TextDecoder()
const PUBLIC_PROXY = 'https://cors.isomorphic-git.org'

class Cancelled extends Error {}

export interface Controller {
  run(command: string, args: Args): Promise<void>
  discover(): Promise<void>
  // The `git` command of the terminal. Resolves to the exit status.
  cli(args: string[], io: GitIo, cwd: string, signal: AbortSignal): Promise<number>
  // Runs the real git on a project in the browser workspace. Resolves to the exit status.
  realGit(process: GitProcess): Promise<number>
  // The stored GitHub token, for gh. With `prompt` set it asks for one when there is none.
  token(prompt: boolean): Promise<string | undefined>
  // Stores the token, or asks for one when none is given. Resolves to false if the prompt was dismissed.
  login(token?: string): Promise<boolean>
  // Forgets the stored token. Resolves to false if there was none.
  logout(): Promise<boolean>
}

// What the terminal uses
export type TerminalGit = Pick<Controller, 'cli' | 'realGit' | 'token' | 'login' | 'logout'>

// With `scm` unset, VS Code's Git extension provides Source Control and this only runs git for others.
export async function createController(api: typeof vscode, scm: boolean): Promise<Controller> {
  // isomorphic-git loads when something needs it: Source Control here, or the terminal's git where
  // the real one cannot run
  let git!: Git
  let http!: Awaited<ReturnType<typeof loadIsoGit>>['http']
  const loadIso = async () => {
    if (!git) ({ git, http } = await loadIsoGit())
  }
  if (scm) await loadIso()
  const auth = new GitHubAuth(api)
  // Created on first use, so it does not sit next to the Git extension's own channel
  let channel: vscode.OutputChannel | undefined
  const output = { appendLine: (text: string) => (channel ??= api.window.createOutputChannel('Git')).appendLine(text) }
  const repos = new Map<string, Repository>()
  const contentChanged = new api.EventEmitter<vscode.Uri>()
  const decorationsChanged = new api.EventEmitter<vscode.Uri[]>()
  const served = new Map<string, vscode.Uri>()
  const sessionLogins = new Map<string, { username: string; password: string }>()

  const config = () => api.workspace.getConfiguration('git')
  const corsProxy = () => config().get<string>('corsProxy')?.trim() || undefined

  api.workspace.registerTextDocumentContentProvider(CONTENT_SCHEME, {
    onDidChange: contentChanged.event,
    async provideTextDocumentContent(uri) {
      const { root, side } = JSON.parse(uri.query) as { root: string; side: Side }
      served.set(uri.toString(), uri)
      const repo = repos.get(root)
      return repo ? decoder.decode(await repo.content(uri.path.slice(root.length + 1), side)) : ''
    }
  })
  api.window.registerFileDecorationProvider({
    onDidChangeFileDecorations: decorationsChanged.event,
    provideFileDecoration: (uri) => repositoryOf(uri)?.decoration(uri)
  })

  function repositoryOf(uri: vscode.Uri): Repository | undefined {
    let best: Repository | undefined
    for (const repo of repos.values()) {
      if (repo.contains(uri) && (!best || repo.root.path.length > best.root.path.length)) best = repo
    }
    return best
  }

  function track(root: vscode.Uri): Repository {
    let repo = repos.get(root.path)
    if (repo) return repo
    repo = new Repository(api, git, root)
    const created = repo
    created.onDidChangeDecorations((uris) => decorationsChanged.fire(uris))
    created.onDidRefresh(() => {
      for (const uri of served.values()) if (created.contains(api.Uri.file(uri.path))) contentChanged.fire(uri)
    })
    repos.set(root.path, created)
    void created.refresh()
    return created
  }

  async function discover(): Promise<void> {
    const roots = await findRepositories(api)
    for (const root of roots) track(root)
    for (const [path, repo] of repos) {
      if (roots.some((r) => r.path === path)) continue
      repo.dispose()
      repos.delete(path)
    }
  }

  async function pickRepository(arg?: unknown): Promise<Repository> {
    const found = [...repos.values()]
    const scm = arg as { rootUri?: vscode.Uri } | undefined
    const direct = found.find((r) => r.scm === arg) ?? found.find((r) => scm?.rootUri && r.root.path === scm.rootUri.path)
    if (direct) return direct
    const resource = (arg as { resourceUri?: vscode.Uri } | undefined)?.resourceUri
    const owner = resource && repositoryOf(resource)
    if (owner) return owner
    if (found.length === 0) throw new Error('There is no Git repository. Use "Git: Clone" or "Git: Initialize Repository".')
    if (found.length === 1) return found[0]
    const active = api.window.activeTextEditor?.document.uri
    const current = active && repositoryOf(active)
    if (current) return current
    const pick = await api.window.showQuickPick(
      found.map((r) => ({ label: r.root.path.split('/').pop() || r.root.path, description: r.root.path, repo: r })),
      { placeHolder: 'Select a repository' }
    )
    if (!pick) throw new Cancelled()
    return pick.repo
  }

  function progressOf(progress: vscode.Progress<{ message?: string }>, token?: vscode.CancellationToken): Progress {
    return { report: (message) => progress.report({ message }), isCancelled: () => !!token?.isCancellationRequested }
  }

  async function author(repo: Repository): Promise<{ name: string; email: string }> {
    const ask = async (key: 'name' | 'email', prompt: string): Promise<string> => {
      let value = config().get<string>(`user.${key}`)?.trim()
      value ||= ((await git.getConfig({ fs: repo.ctx.fs, dir: repo.ctx.dir, path: `user.${key}` })) as string | undefined)?.trim()
      if (value) return value
      value = (await api.window.showInputBox({ prompt, ignoreFocusOut: true, validateInput: (v) => (v.trim() ? undefined : 'This is required') }))?.trim()
      if (!value) throw new Cancelled()
      await config().update(`user.${key}`, value, api.ConfigurationTarget.Global)
      return value
    }
    const name = await ask('name', 'Your name for commits (saved as git.user.name)')
    return { name, email: await ask('email', 'Your email for commits (saved as git.user.email)') }
  }

  function askLogin(url: string) {
    const host = new URL(url).host
    return async () => {
      const saved = sessionLogins.get(host)
      if (saved) return saved
      const username = await api.window.showInputBox({ prompt: `Username for ${host}`, ignoreFocusOut: true })
      if (!username) return { cancel: true as const }
      const password = await api.window.showInputBox({ prompt: `Password or token for ${host}`, password: true, ignoreFocusOut: true })
      if (!password) return { cancel: true as const }
      const login = { username, password }
      sessionLogins.set(host, login)
      return login
    }
  }

  const transportOptions = (url: string, progress: Progress) => ({
    http: http as HttpClient,
    corsProxy: corsProxy(),
    onAuth: askLogin(url),
    onAuthFailure: () => {
      sessionLogins.delete(new URL(url).host)
      return { cancel: true as const }
    },
    onProgress: (e: { phase: string; loaded: number; total?: number }) => progress.report(e.total ? `${e.phase} ${e.loaded}/${e.total}` : e.phase)
  })

  // The real git reaches every remote, GitHub too, through a proxy: git.corsProxy, or the git route
  // of the WPILib proxy. A saved GitHub token is sent to github.com only.
  async function realGitRemotes(): Promise<Remotes> {
    const vendorProxy = api.workspace.getConfiguration('wpilib').get<string>('vendorProxy')?.trim().replace(/\/$/, '')
    const token = await auth.getToken().catch(() => undefined)
    return {
      proxy: corsProxy() ?? (vendorProxy ? `${vendorProxy}/git` : undefined),
      auth: token ? { 'github.com': `Basic ${btoa(`x-access-token:${token}`)}` } : {}
    }
  }

  // Runs the real git. Like a credential helper, it signs in to GitHub and runs the command
  // again when GitHub turns it away and none is saved.
  async function realGit(process: GitProcess): Promise<number> {
    const remotes = await realGitRemotes()
    let errors = ''
    const decoder = new TextDecoder()
    const stderr = (bytes: Uint8Array) => {
      errors += decoder.decode(bytes, { stream: true })
      process.stderr(bytes)
    }
    let { status } = await runRealGit(remotes, { ...process, stderr })
    if (status !== 0 && !remotes.auth['github.com'] && /Authentication failed for 'https:\/\/github\.com\//.test(errors)) {
      const token = await auth.getToken('git').catch(() => undefined)
      if (token) ({ status } = await runRealGit(await realGitRemotes(), process))
    }
    // Changes inside .git do not reach this Source Control view on their own
    if (scm) void refreshAll()
    return status
  }

  async function requireProxy(url: string): Promise<void> {
    if (corsProxy()) return
    const usePublic = 'Use the public proxy'
    const own = 'Set my own'
    const pick = await api.window.showWarningMessage(
      `${new URL(url).host} does not allow requests from a browser page, so git needs a CORS proxy to reach it. GitHub works without one.`,
      {
        modal: true,
        detail: `Every request, including your username and password or token, passes through the proxy. ${PUBLIC_PROXY} is a free public proxy run by the isomorphic-git project. Use your own if you do not want to trust it with your credentials.`
      },
      usePublic,
      own
    )
    if (pick === usePublic) {
      await config().update('corsProxy', PUBLIC_PROXY, api.ConfigurationTarget.Global)
      return
    }
    if (pick === own) await api.commands.executeCommand('workbench.action.openSettings', 'git.corsProxy')
    throw new Cancelled('Cancelled')
  }

  async function exists(uri: vscode.Uri): Promise<boolean> {
    return api.workspace.fs.stat(uri).then(() => true, () => false)
  }

  // `link` comes from a ?github= link (frc/githubLink.ts); the folder is always the workspace, as VS Code's Git takes it
  async function clone(link?: unknown): Promise<void> {
    const text = typeof link === 'string' ? link : await api.window.showInputBox({
      title: 'Git: Clone',
      prompt: 'Repository URL',
      placeHolder: 'https://github.com/owner/repo',
      ignoreFocusOut: true,
      validateInput: (v) =>
        parseGitHubRepo(v) || /^https?:\/\/[^/]+\/.+/.test(v.trim()) ? undefined : 'Enter a GitHub URL such as https://github.com/owner/repo, or owner/repo'
    })
    if (!text) return
    const input = text.trim()
    const github = parseGitHubRepo(input)
    const url = github ? `https://github.com/${github.owner}/${github.repo}.git` : input
    const name = github?.repo ?? input.replace(/\/+$/, '').replace(/\.git$/, '').split('/').pop()!
    const target = api.Uri.file(`${workspaceFolder.path}/${name}`)
    await api.window.withProgress(
      { location: api.ProgressLocation.Notification, title: `Cloning ${github ? `${github.owner}/${github.repo}` : url}`, cancellable: true },
      (progress, token) => cloneInto(target, input, progressOf(progress, token))
    )
    await api.commands.executeCommand('vscode.openFolder', target)
  }

  // Clones a GitHub "owner/repo" or URL into a folder that does not exist yet. A failed clone removes the folder.
  async function cloneInto(target: vscode.Uri, input: string, p: Progress, branch?: string): Promise<void> {
    const github = parseGitHubRepo(input)
    const url = github ? `https://github.com/${github.owner}/${github.repo}.git` : input
    if (!github) await requireProxy(input)
    if (await exists(target)) throw new Error(`The folder "${target.path.split('/').pop()}" already exists`)
    const executable = new Set<string>()
    const ctx: RepoContext = { git, dir: target.path, executable, fs: createGitFs(api, executable) }
    const remove = () => Promise.resolve(api.workspace.fs.delete(target, { recursive: true })).then(undefined, () => undefined)
    try {
      if (github) {
        const before = auth.client.apiRequests
        const head = await auth.run(async (client) => {
          await remove()
          return cloneFromGitHub(ctx, client, github, p, branch)
        })
        output.appendLine(`Cloned ${github.owner}/${github.repo} at ${head.slice(0, 7)} with ${auth.client.apiRequests - before} GitHub API requests`)
      } else {
        await git.clone({ fs: ctx.fs, dir: ctx.dir, url, ref: branch, singleBranch: true, depth: 1, ...transportOptions(url, p) })
      }
    } catch (error) {
      await remove()
      throw error
    }
  }

  async function initAt(dir: string): Promise<void> {
    await git.init({ fs: createGitFs(api, new Set()), dir, defaultBranch: 'main' })
    await discover()
  }

  // The repository that holds a directory, even one the Source Control view does not scan for
  async function repoFor(dir: string): Promise<Repository | undefined> {
    for (let path = dir; ; path = path.slice(0, path.lastIndexOf('/')) || '/') {
      if (await exists(api.Uri.joinPath(api.Uri.file(path), '.git'))) return track(api.Uri.file(path))
      if (path === '/') return undefined
    }
  }

  // The Source Control welcome passes true to initialize the workspace folder without asking
  async function init(workspaceFolder?: unknown): Promise<void> {
    const [root] = workspaceRoots(api)
    if (workspaceFolder === true && root && !(await exists(api.Uri.joinPath(root, '.git')))) return initAt(root.path)
    const candidates: vscode.Uri[] = []
    for (const root of workspaceRoots(api)) for (const dir of [root, ...(await childFolders(api, root))]) candidates.push(dir)
    const free: vscode.Uri[] = []
    for (const dir of candidates) if (!(await exists(api.Uri.joinPath(dir, '.git')))) free.push(dir)
    if (free.length === 0) throw new Error('Every folder in the workspace already is a repository')
    const folder =
      free.length === 1
        ? free[0]
        : (await api.window.showQuickPick(
            free.map((uri) => ({ label: uri.path.split('/').pop() || uri.path, description: uri.path, uri })),
            { placeHolder: 'Folder to initialize as a repository' }
          ))?.uri
    if (!folder) return
    await initAt(folder.path)
  }

  async function commit(arg?: unknown): Promise<void> {
    const repo = await pickRepository(arg)
    let message = repo.scm.inputBox.value.trim()
    if (!message) {
      message = (await api.window.showInputBox({ prompt: 'Commit message', ignoreFocusOut: true }))?.trim() ?? ''
      if (!message) return
    }
    if (!repo.hasStaged()) {
      const all = repo.allChanges('workingTree')
      if (all.length === 0) throw new Error('There are no changes to commit')
      const choice = await api.window.showWarningMessage(
        'There are no staged changes to commit. Stage all changes and commit them?',
        { modal: true },
        'Stage All and Commit'
      )
      if (!choice) return
      await repo.stage(all.map((c) => c.path))
    }
    const oid = await repo.commit(message, await author(repo))
    repo.scm.inputBox.value = ''
    api.window.setStatusBarMessage(`Committed ${oid.slice(0, 7)}`, 5000)
  }

  async function remoteFor(repo: Repository): Promise<{ github?: ReturnType<typeof parseGitHubRepo>; url?: string }> {
    const { fs, dir } = repo.ctx
    let url = (await git.getConfig({ fs, dir, path: 'remote.origin.url' })) as string | undefined
    if (!url) {
      const text = await api.window.showInputBox({
        title: 'Add remote',
        prompt: 'URL of the remote repository (origin)',
        placeHolder: 'https://github.com/owner/repo',
        ignoreFocusOut: true,
        validateInput: (v) => (parseGitHubRepo(v) || /^https?:\/\//.test(v.trim()) ? undefined : 'Enter a GitHub URL or owner/repo')
      })
      if (!text) throw new Cancelled()
      const parsed = parseGitHubRepo(text)
      url = parsed ? `https://github.com/${parsed.owner}/${parsed.repo}.git` : text.trim()
      await git.addRemote({ fs, dir, remote: 'origin', url })
      const branch = await repo.branch()
      if (branch) {
        await git.setConfig({ fs, dir, path: `branch.${branch}.remote`, value: 'origin' })
        await git.setConfig({ fs, dir, path: `branch.${branch}.merge`, value: `refs/heads/${branch}` })
      }
    }
    return { github: await remoteOf(repo.ctx), url }
  }

  // Resolves to a message for the user
  async function syncRepo(repo: Repository, direction: 'push' | 'pull', p: Progress): Promise<string> {
    const { github, url } = await remoteFor(repo)
    if (!github && url) await requireProxy(url)
    const title = direction === 'push' ? 'Pushing' : 'Pulling'
    const { fs, dir } = repo.ctx
    let message: string
    if (github) {
      const before = auth.client.apiRequests
      const run = direction === 'push' ? pushToGitHub : pullFromGitHub
      message = await repo.exclusive(() => auth.run((client) => run(repo.ctx, client, github, p), { requireToken: direction === 'push' }))
      output.appendLine(`${title} ${github.owner}/${github.repo}: ${auth.client.apiRequests - before} GitHub API requests`)
    } else {
      const branch = (await repo.branch()) ?? undefined
      const options = { fs, dir, ref: branch, remote: 'origin', ...transportOptions(url!, p) }
      await repo.exclusive(async () => {
        if (direction === 'push') await git.push(options)
        else await git.fastForward({ ...options, singleBranch: true })
      })
      message = direction === 'push' ? 'Pushed' : 'Pulled'
    }
    await repo.refresh()
    return message
  }

  async function sync(arg: unknown, direction: 'push' | 'pull'): Promise<void> {
    const repo = await pickRepository(arg)
    const title = direction === 'push' ? 'Pushing' : 'Pulling'
    const message = await api.window.withProgress({ location: api.ProgressLocation.Notification, title }, (progress) =>
      syncRepo(repo, direction, progressOf(progress))
    )
    void api.window.showInformationMessage(plainText(message))
  }

  async function checkout(arg?: unknown): Promise<void> {
    const repo = await pickRepository(arg)
    const current = await repo.branch()
    const create = { label: '$(plus) Create new branch...', alwaysShow: true }
    const pick = await api.window.showQuickPick(
      [create, ...(await repo.listBranches()).map((b) => ({ label: b, description: b === current ? 'current' : undefined }))],
      { placeHolder: 'Select a branch to checkout' }
    )
    if (!pick) return
    if (pick === create) return createBranch(repo)
    await repo.checkout(pick.label)
  }

  async function createBranch(arg?: unknown): Promise<void> {
    const repo = arg instanceof Repository ? arg : await pickRepository(arg)
    const name = await api.window.showInputBox({
      title: 'Git: Create Branch',
      prompt: 'Branch name',
      ignoreFocusOut: true,
      validateInput: (v) => (/^(?!-)[\w./-]+$/.test(v) && !v.includes('..') && !v.endsWith('/') && !v.endsWith('.lock') ? undefined : 'Not a valid branch name')
    })
    if (name) await repo.createBranch(name)
  }

  async function viewHistory(arg?: unknown): Promise<void> {
    const repo = await pickRepository(arg)
    const entries = await repo.log(100)
    await api.window.showQuickPick(
      entries.map((e) => ({
        label: e.commit.message.split('\n')[0],
        description: e.oid.slice(0, 7),
        detail: `${e.commit.author.name}, ${new Date(e.commit.author.timestamp * 1000).toLocaleString()}`
      })),
      { title: 'Git: View History', placeHolder: 'Commits on the current branch' }
    )
  }

  // Resource arguments: the clicked item first, then the rest of the selection
  function resources(args: Args): Array<{ repo: Repository; path: string; kind: Change['kind'] }> {
    const out = []
    for (const arg of args as Array<{ resourceUri?: vscode.Uri; contextValue?: string }>) {
      if (!arg?.resourceUri) continue
      const repo = repositoryOf(arg.resourceUri)
      if (repo) out.push({ repo, path: repo.relative(arg.resourceUri), kind: (arg.contextValue ?? 'M') as Change['kind'] })
    }
    return out
  }

  function group(arg: unknown): { repo: Repository; changes: Change[]; id: 'index' | 'workingTree' } {
    for (const repo of repos.values()) {
      const id = repo.ownsGroup(arg)
      if (id) return { repo, id, changes: repo.allChanges(id) }
    }
    throw new Error('Select a group in the Source Control view')
  }

  const perRepo = <T extends { repo: Repository }>(items: T[], task: (repo: Repository, items: T[]) => Promise<void>) =>
    Promise.all([...new Set(items.map((i) => i.repo))].map((repo) => task(repo, items.filter((i) => i.repo === repo))))

  async function discard(changes: Array<{ repo: Repository } & Change>): Promise<void> {
    if (changes.length === 0) return
    const detail = changes.length === 1 ? changes[0].path : `${changes.length} files`
    const choice = await api.window.showWarningMessage(`Discard changes to ${detail}?`, { modal: true, detail: 'This cannot be undone.' }, 'Discard Changes')
    if (choice) await perRepo(changes, (repo, items) => repo.discard(items))
  }

  async function refreshAll(): Promise<void> {
    await discover()
    await Promise.all([...repos.values()].map((r) => r.refresh()))
  }

  const commands: Record<string, (...args: Args) => Promise<void>> = {
    'git.clone': clone,
    'git.init': init,
    'git.commit': (arg) => commit(arg),
    'git.push': (arg) => sync(arg, 'push'),
    'git.pull': (arg) => sync(arg, 'pull'),
    'git.checkout': (arg) => checkout(arg),
    'git.branch': (arg) => createBranch(arg),
    'git.refresh': refreshAll,
    'git.viewHistory': (arg) => viewHistory(arg),
    'git.signOutGitHub': async () => {
      const had = await auth.signOut()
      void api.window.showInformationMessage(had ? 'Signed out of GitHub' : 'No GitHub token is stored')
    },
    'git.stage': (...args) => perRepo(resources(args), (repo, items) => repo.stage(items.map((i) => i.path))).then(() => undefined),
    'git.unstage': (...args) => perRepo(resources(args), (repo, items) => repo.unstage(items.map((i) => i.path))).then(() => undefined),
    'git.discard': (...args) => discard(resources(args).map((r) => ({ ...r }))),
    'git.stageAll': async (arg) => {
      const g = group(arg)
      await g.repo.stage(g.changes.map((c) => c.path))
    },
    'git.unstageAll': async (arg) => {
      const g = group(arg)
      await g.repo.unstage(g.changes.map((c) => c.path))
    },
    'git.discardAll': async (arg) => {
      const g = group(arg)
      await discard(g.changes.map((c) => ({ ...c, repo: g.repo })))
    }
  }

  return {
    discover,
    token: (prompt) => auth.getToken(prompt ? 'cli' : undefined),
    login: (token) => auth.login(token),
    logout: () => auth.signOut(),
    async cli(args, io, cwd, signal) {
      const remotes = await realGitRemotes()
      if (!(await usesRealGit(args, cwd, remotes.proxy))) {
        await loadIso()
        return runGit({ api, git, repoFor, init: initAt, author, sync: syncRepo, clone: cloneInto, discover }, args, io, cwd, signal)
      }
      const text = (write: (text: string) => void) => {
        const decoder = new TextDecoder()
        return (bytes: Uint8Array) => write(decoder.decode(bytes, { stream: true }))
      }
      return realGit({ args, cwd, tty: io.tty, columns: io.columns, signal, stdout: text(io.out), stderr: text(io.err) })
    },
    realGit,
    async run(command, args) {
      try {
        await commands[command](...args)
      } catch (error) {
        if (error instanceof Cancelled || (error instanceof Error && error.message === 'Cancelled')) return
        const message = error instanceof Error ? error.message : String(error)
        output.appendLine(`${command}: ${message}`)
        console.error(error)
        void api.window.showErrorMessage(plainText(`Git: ${message}`))
      }
    }
  }
}

export type { Git }
