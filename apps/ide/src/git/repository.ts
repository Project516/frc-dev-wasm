import type * as vscode from 'vscode'
import { createGitFs } from './fs'
import type { Git, RepoContext } from './github'
import { classify, type Change, type Kind } from './status'

export const CONTENT_SCHEME = 'frc-git'
export const PROVIDER_ID = 'frc-git'

export type Side = 'HEAD' | 'INDEX'

const KIND_LABEL: Record<Kind, string> = { M: 'Modified', A: 'Added', D: 'Deleted', U: 'Untracked' }
const KIND_COLOR: Record<Kind, string> = {
  M: 'gitDecoration.modifiedResourceForeground',
  A: 'gitDecoration.addedResourceForeground',
  D: 'gitDecoration.deletedResourceForeground',
  U: 'gitDecoration.untrackedResourceForeground'
}

export class Repository implements vscode.Disposable {
  readonly ctx: RepoContext
  readonly scm: vscode.SourceControl
  private readonly staged: vscode.SourceControlResourceGroup
  private readonly changes: vscode.SourceControlResourceGroup
  private readonly decorations = new Map<string, Kind>()
  private readonly changed: vscode.EventEmitter<vscode.Uri[]>
  readonly onDidChangeDecorations: vscode.Event<vscode.Uri[]>
  private readonly refreshed: vscode.EventEmitter<void>
  readonly onDidRefresh: vscode.Event<void>
  private readonly disposables: vscode.Disposable[] = []
  private head: string | undefined
  private queue: Promise<unknown> = Promise.resolve()
  private timer: ReturnType<typeof setTimeout> | undefined

  constructor(
    private readonly api: typeof vscode,
    git: Git,
    readonly root: vscode.Uri
  ) {
    const executable = new Set<string>()
    this.ctx = { git, dir: root.path, executable, fs: createGitFs(api, executable) }
    const scm = (this.scm = api.scm.createSourceControl(PROVIDER_ID, root.path.split('/').pop() || root.path, root))
    this.staged = scm.createResourceGroup('index', 'Staged Changes')
    this.staged.hideWhenEmpty = true
    this.changes = scm.createResourceGroup('workingTree', 'Changes')
    this.changes.hideWhenEmpty = true
    scm.inputBox.placeholder = 'Message (Ctrl+Enter to commit)'
    scm.acceptInputCommand = { command: 'git.commit', title: 'Commit', arguments: [scm] }
    scm.quickDiffProvider = {
      provideOriginalResource: (uri) => (this.contains(uri) && !this.isGitInternal(uri) ? this.contentUri(this.relative(uri), 'HEAD') : undefined)
    }
    this.changed = new api.EventEmitter()
    this.onDidChangeDecorations = this.changed.event
    this.refreshed = new api.EventEmitter()
    this.onDidRefresh = this.refreshed.event

    const watcher = api.workspace.createFileSystemWatcher(new api.RelativePattern(root, '**'))
    const onFile = (uri: vscode.Uri) => {
      if (!this.isGitInternal(uri)) this.schedule()
    }
    this.disposables.push(
      scm,
      this.changed,
      this.refreshed,
      watcher,
      watcher.onDidChange(onFile),
      watcher.onDidCreate(onFile),
      watcher.onDidDelete(onFile),
      api.workspace.onDidSaveTextDocument((doc) => {
        if (this.contains(doc.uri)) onFile(doc.uri)
      })
    )
  }

  dispose(): void {
    clearTimeout(this.timer)
    for (const d of this.disposables) d.dispose()
  }

  contains(uri: vscode.Uri): boolean {
    return uri.scheme === 'file' && (uri.path === this.root.path || uri.path.startsWith(`${this.root.path}/`))
  }

  private isGitInternal(uri: vscode.Uri): boolean {
    const rel = this.relative(uri)
    return rel === '.git' || rel.startsWith('.git/')
  }

  relative(uri: vscode.Uri): string {
    return uri.path.slice(this.root.path.length + 1)
  }

  uri(path: string): vscode.Uri {
    return this.api.Uri.joinPath(this.root, path)
  }

  contentUri(path: string, side: Side): vscode.Uri {
    return this.api.Uri.from({ scheme: CONTENT_SCHEME, path: this.uri(path).path, query: JSON.stringify({ root: this.root.path, side }) })
  }

  // Git operations share the index, so they run one at a time
  exclusive<T>(task: () => Promise<T>): Promise<T> {
    const result = this.queue.then(task, task)
    this.queue = result.catch(() => undefined)
    return result
  }

  schedule(): void {
    clearTimeout(this.timer)
    this.timer = setTimeout(() => void this.refresh(), 400)
  }

  decoration(uri: vscode.Uri): vscode.FileDecoration | undefined {
    const kind = this.decorations.get(uri.path)
    if (!kind) return undefined
    return { badge: kind, tooltip: KIND_LABEL[kind], color: new this.api.ThemeColor(KIND_COLOR[kind]) }
  }

  refresh(): Promise<void> {
    return this.exclusive(async () => {
      const { git, fs, dir } = this.ctx
      try {
        const head = await git.resolveRef({ fs, dir, ref: 'HEAD' }).catch(() => undefined)
        if (head !== this.head) {
          this.head = head
          await this.seedExecutable()
        }
        const rows = await git.statusMatrix({ fs, dir })
        const { staged, changes } = classify(rows as Array<[string, number, number, number]>)
        this.staged.resourceStates = staged.map((c) => this.resourceState(c, true))
        this.changes.resourceStates = changes.map((c) => this.resourceState(c, false, staged.some((s) => s.path === c.path)))
        this.scm.count = changes.length
        const old = [...this.decorations.keys()].map((p) => this.api.Uri.file(p))
        this.decorations.clear()
        for (const c of [...staged, ...changes]) this.decorations.set(this.uri(c.path).path, c.kind)
        this.changed.fire([...old, ...[...this.decorations.keys()].map((p) => this.api.Uri.file(p))])
        await this.updateStatusBar()
        this.refreshed.fire()
      } catch (error) {
        console.error('git refresh failed', error)
      }
    })
  }

  private resourceState(change: Change, staged: boolean, alsoStaged = false): vscode.SourceControlResourceState {
    const uri = this.uri(change.path)
    const title = `${change.path.split('/').pop()} (${staged ? 'Index' : 'Working Tree'})`
    let command: vscode.Command
    if (change.kind === 'U') command = { command: 'vscode.open', title: 'Open', arguments: [uri] }
    else if (change.kind === 'D') command = { command: 'vscode.open', title: 'Open', arguments: [this.contentUri(change.path, staged ? 'HEAD' : alsoStaged ? 'INDEX' : 'HEAD')] }
    else if (staged && change.kind === 'A') command = { command: 'vscode.open', title: 'Open', arguments: [this.contentUri(change.path, 'INDEX')] }
    else {
      const left = this.contentUri(change.path, staged || !alsoStaged ? 'HEAD' : 'INDEX')
      const right = staged ? this.contentUri(change.path, 'INDEX') : uri
      command = { command: 'vscode.diff', title: 'Open Changes', arguments: [left, right, title] }
    }
    return {
      resourceUri: uri,
      command,
      contextValue: change.kind,
      decorations: { tooltip: KIND_LABEL[change.kind], strikeThrough: change.kind === 'D' }
    }
  }

  private async seedExecutable(): Promise<void> {
    const { git, fs, dir, executable } = this.ctx
    executable.clear()
    if (!this.head) return
    await git.walk({
      fs,
      dir,
      trees: [git.TREE({ ref: 'HEAD' })],
      map: async (path, [entry]) => {
        if (entry && (await entry.type()) === 'blob' && (await entry.mode()) === 0o100755) executable.add(`${dir}/${path}`)
        return undefined
      }
    })
  }

  async branch(): Promise<string | undefined> {
    const { git, fs, dir } = this.ctx
    return (await git.currentBranch({ fs, dir })) ?? undefined
  }

  // Commits that exist locally but not on the remote-tracking branch
  async ahead(): Promise<number> {
    const { git, fs, dir } = this.ctx
    const branch = await this.branch()
    if (!branch) return 0
    const tracking = await git.resolveRef({ fs, dir, ref: `refs/remotes/origin/${branch}` }).catch(() => undefined)
    if (!tracking) return 0
    const log = await git.log({ fs, dir, depth: 100 }).catch(() => [])
    const index = log.findIndex((entry) => entry.oid === tracking)
    return index < 0 ? 0 : index
  }

  private async updateStatusBar(): Promise<void> {
    const branch = (await this.branch()) ?? 'HEAD'
    const ahead = await this.ahead()
    const commands: vscode.Command[] = [{ command: 'git.checkout', title: `$(git-branch) ${branch}`, tooltip: 'Checkout to...', arguments: [this.scm] }]
    if (ahead > 0) commands.push({ command: 'git.push', title: `$(arrow-up) ${ahead}`, tooltip: `Push ${ahead} commit${ahead === 1 ? '' : 's'}`, arguments: [this.scm] })
    this.scm.statusBarCommands = commands
  }

  async stage(paths: string[]): Promise<void> {
    await this.exclusive(async () => {
      const { git, fs, dir } = this.ctx
      for (const filepath of paths) {
        const exists = await fs.promises.stat(`${dir}/${filepath}`).then(() => true, () => false)
        await (exists ? git.add({ fs, dir, filepath }) : git.remove({ fs, dir, filepath }))
      }
    })
    await this.refresh()
  }

  async unstage(paths: string[]): Promise<void> {
    await this.exclusive(async () => {
      const { git, fs, dir } = this.ctx
      for (const filepath of paths) await git.resetIndex({ fs, dir, filepath })
    })
    await this.refresh()
  }

  async discard(changes: Change[]): Promise<void> {
    await this.exclusive(async () => {
      const { git, fs, dir } = this.ctx
      const ref = (await this.branch()) ?? this.head
      for (const { path, kind } of changes) {
        if (kind === 'U') await this.api.workspace.fs.delete(this.uri(path))
        else if (ref) await git.checkout({ fs, dir, ref, filepaths: [path], force: true })
      }
    })
    await this.refresh()
  }

  ownsGroup(group: unknown): 'index' | 'workingTree' | undefined {
    return group === this.staged ? 'index' : group === this.changes ? 'workingTree' : undefined
  }

  allChanges(group: 'index' | 'workingTree'): Change[] {
    const states = (group === 'index' ? this.staged : this.changes).resourceStates
    return states.map((s) => ({ path: this.relative(s.resourceUri), kind: (s.contextValue ?? 'M') as Kind }))
  }

  hasStaged(): boolean {
    return this.staged.resourceStates.length > 0
  }

  async commit(message: string, author: { name: string; email: string }): Promise<string> {
    const oid = await this.exclusive(() => {
      const { git, fs, dir } = this.ctx
      return git.commit({ fs, dir, message: message.trim(), author })
    })
    await this.refresh()
    return oid
  }

  async checkout(ref: string): Promise<void> {
    await this.exclusive(() => this.ctx.git.checkout({ fs: this.ctx.fs, dir: this.ctx.dir, ref }))
    await this.refresh()
  }

  async createBranch(ref: string): Promise<void> {
    await this.exclusive(() => this.ctx.git.branch({ fs: this.ctx.fs, dir: this.ctx.dir, ref, checkout: true }))
    await this.refresh()
  }

  listBranches(): Promise<string[]> {
    return this.ctx.git.listBranches({ fs: this.ctx.fs, dir: this.ctx.dir })
  }

  log(depth: number) {
    return this.ctx.git.log({ fs: this.ctx.fs, dir: this.ctx.dir, depth })
  }

  // Content of a file in HEAD or in the index. Empty when the file is not there.
  async content(path: string, side: Side): Promise<Uint8Array> {
    const { git, fs, dir } = this.ctx
    try {
      if (side === 'HEAD') {
        const oid = await git.resolveRef({ fs, dir, ref: 'HEAD' })
        return (await git.readBlob({ fs, dir, oid, filepath: path })).blob
      }
      const found = await git.walk({
        fs,
        dir,
        trees: [git.STAGE()],
        map: async (filepath, [entry]) => {
          if (filepath === path) return entry?.oid()
          return filepath === '.' || path.startsWith(`${filepath}/`) ? '' : null
        }
      })
      const oid = (found as string[]).find((o) => o)
      return oid ? (await git.readBlob({ fs, dir, oid })).blob : new Uint8Array()
    } catch {
      return new Uint8Array()
    }
  }
}
