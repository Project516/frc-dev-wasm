import type * as IsoGit from 'isomorphic-git'
import type { GitFs } from './fs'
import { commitPayload, gitOid, rebuildCommit, treeBody, type RepoRef, type RestCommit, type TreeEntry } from './objects'

const API = 'https://api.github.com'
const RAW = 'https://raw.githubusercontent.com'
const CONCURRENCY = 6

export type Git = typeof IsoGit

export interface RepoContext {
  git: Git
  fs: GitFs
  dir: string
  // Paths git should see as executable. Shared with the fs adapter.
  executable: Set<string>
}

export interface Progress {
  report(message: string): void
  isCancelled(): boolean
}

export type GitHubErrorKind = 'rate-limit' | 'auth' | 'not-found' | 'other'

export class GitHubError extends Error {
  constructor(
    message: string,
    readonly kind: GitHubErrorKind,
    readonly status = 0
  ) {
    super(message)
  }
}

interface ApiCommit {
  sha: string
  commit: {
    author: { name: string; email: string; date: string }
    committer: { name: string; email: string; date: string }
    message: string
    tree: { sha: string }
    verification?: { signature?: string | null; payload?: string | null }
  }
  parents: Array<{ sha: string }>
}

interface ApiTree {
  tree: Array<{ path: string; mode: string; type: 'blob' | 'tree' | 'commit'; sha: string }>
  truncated: boolean
}

export function toRestCommit(c: ApiCommit): RestCommit {
  return {
    tree: c.commit.tree.sha,
    parents: c.parents.map((p) => p.sha),
    author: c.commit.author,
    committer: c.commit.committer,
    message: c.commit.message,
    verification: c.commit.verification
  }
}

export class GitHubClient {
  // Requests made to api.github.com, which is what the rate limit counts
  apiRequests = 0

  constructor(private readonly token: () => string | undefined) {}

  private headers(accept: string): Record<string, string> {
    const token = this.token()
    return { Accept: accept, ...(token ? { Authorization: `Bearer ${token}` } : {}) }
  }

  private async send(path: string, init: RequestInit & { accept?: string } = {}): Promise<Response> {
    this.apiRequests++
    let response: Response
    try {
      response = await fetch(`${API}${path}`, {
        ...init,
        headers: {
          ...this.headers(init.accept ?? 'application/vnd.github+json'),
          ...(init.body ? { 'Content-Type': 'application/json' } : {})
        }
      })
    } catch {
      throw new GitHubError('Cannot reach api.github.com. Check the network connection.', 'other')
    }
    if (response.ok) return response
    const text = await response.text()
    let message = text
    try {
      message = JSON.parse(text).message ?? text
    } catch {
      // not JSON
    }
    const limited = response.headers.get('x-ratelimit-remaining') === '0' || response.status === 429 || /rate limit/i.test(message)
    if ((response.status === 403 || response.status === 429) && limited) {
      const reset = Number(response.headers.get('x-ratelimit-reset'))
      const when = reset ? ` Resets at ${new Date(reset * 1000).toLocaleTimeString()}.` : ''
      const hint = this.token() ? '' : ' Requests without a token are limited to 60 per hour.'
      throw new GitHubError(`GitHub API rate limit reached.${when}${hint}`, 'rate-limit', response.status)
    }
    if (response.status === 401) throw new GitHubError('GitHub rejected the token', 'auth', 401)
    if (response.status === 404) throw new GitHubError(`Not found: ${path.split('?')[0]}`, 'not-found', 404)
    throw new GitHubError(`GitHub API error ${response.status}: ${message}`, 'other', response.status)
  }

  async json<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await this.send(path, { method, body: body === undefined ? undefined : JSON.stringify(body) })
    return (await response.json()) as T
  }

  // raw.githubusercontent.com is not rate limited like the API, so public blobs come from there
  async raw(owner: string, repo: string, sha: string, path: string): Promise<Uint8Array | undefined> {
    const encoded = path.split('/').map(encodeURIComponent).join('/')
    try {
      const response = await fetch(`${RAW}/${owner}/${repo}/${sha}/${encoded}`)
      return response.ok ? new Uint8Array(await response.arrayBuffer()) : undefined
    } catch {
      return undefined
    }
  }

  async apiBlob(owner: string, repo: string, sha: string): Promise<Uint8Array> {
    const response = await this.send(`/repos/${owner}/${repo}/git/blobs/${sha}`, { accept: 'application/vnd.github.raw+json' })
    return new Uint8Array(await response.arrayBuffer())
  }
}

async function pool<T>(items: T[], task: (item: T) => Promise<void>, progress?: Progress): Promise<void> {
  let next = 0
  const worker = async () => {
    while (next < items.length) {
      if (progress?.isCancelled()) throw new Error('Cancelled')
      await task(items[next++])
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, items.length) }, worker))
}

const join = (dir: string, path: string) => `${dir}/${path}`

async function writeVerified(ctx: RepoContext, type: 'blob' | 'tree' | 'commit', body: Uint8Array, expected: string): Promise<void> {
  const oid = await gitOid(type, body)
  if (oid !== expected) throw new Error(`Hash mismatch for ${type} ${expected.slice(0, 7)}`)
  await ctx.git.writeObject({ fs: ctx.fs, dir: ctx.dir, type, object: body, format: 'content' })
}

async function fetchTree(client: GitHubClient, ref: RepoRef, sha: string): Promise<ApiTree['tree']> {
  const listing = await client.json<ApiTree>(`/repos/${ref.owner}/${ref.repo}/git/trees/${sha}?recursive=1`)
  if (listing.truncated) throw new Error('The repository is too large for the GitHub tree API (listing truncated)')
  return listing.tree
}

// Writes the commit's trees, and the blobs missing from `have`. Every object is checked against its API hash.
async function writeSnapshot(
  ctx: RepoContext,
  client: GitHubClient,
  ref: RepoRef,
  isPrivate: boolean,
  commitSha: string,
  rootTree: string,
  entries: ApiTree['tree'],
  have: Set<string>,
  progress: Progress
): Promise<void> {
  const blobs = entries.filter((e) => e.type === 'blob')
  for (const e of blobs) if (e.mode === '100755') ctx.executable.add(join(ctx.dir, e.path))

  const wanted = [...new Map(blobs.filter((e) => !have.has(e.sha)).map((e) => [e.sha, e])).values()]
  let done = 0
  await pool(
    wanted,
    async (entry) => {
      let bytes = isPrivate ? undefined : await client.raw(ref.owner, ref.repo, commitSha, entry.path)
      // LFS files and similar come back different from raw, so those are fetched by hash
      if (!bytes || (await gitOid('blob', bytes)) !== entry.sha) bytes = await client.apiBlob(ref.owner, ref.repo, entry.sha)
      await writeVerified(ctx, 'blob', bytes, entry.sha)
      progress.report(`Downloading files ${++done}/${wanted.length}`)
    },
    progress
  )

  const dirs = new Map<string, TreeEntry[]>([['', []]])
  for (const e of entries) {
    const slash = e.path.lastIndexOf('/')
    const parent = slash < 0 ? '' : e.path.slice(0, slash)
    if (e.type === 'tree') dirs.set(e.path, dirs.get(e.path) ?? [])
    dirs.get(parent)?.push({ mode: e.mode, path: e.path.slice(slash + 1), sha: e.sha })
  }
  const shaOf = new Map(entries.filter((e) => e.type === 'tree').map((e) => [e.path, e.sha]))
  shaOf.set('', rootTree)
  for (const [path, treeEntries] of dirs) await writeVerified(ctx, 'tree', treeBody(treeEntries), shaOf.get(path)!)
}

async function writeCommits(ctx: RepoContext, commits: ApiCommit[]): Promise<void> {
  for (const c of commits) await writeVerified(ctx, 'commit', await rebuildCommit(toRestCommit(c), c.sha), c.sha)
}

export async function cloneFromGitHub(ctx: RepoContext, client: GitHubClient, ref: RepoRef, progress: Progress, requested?: string): Promise<string> {
  const { git, fs, dir } = ctx
  progress.report('Reading repository')
  const info = await client.json<{ default_branch: string; private: boolean }>(`/repos/${ref.owner}/${ref.repo}`)
  const branch = requested ?? info.default_branch
  const head = await client
    .json<ApiCommit>(`/repos/${ref.owner}/${ref.repo}/commits/${encodeURIComponent(branch)}`)
    .catch((error) => {
      throw requested ? new Error(`Remote branch ${requested} not found in upstream origin`) : error
    })
  progress.report('Reading file list')
  const entries = await fetchTree(client, ref, head.commit.tree.sha)

  await git.init({ fs, dir, defaultBranch: branch })
  await writeSnapshot(ctx, client, ref, info.private, head.sha, head.commit.tree.sha, entries, new Set(), progress)
  await writeCommits(ctx, [head])

  await git.addRemote({ fs, dir, remote: 'origin', url: `https://github.com/${ref.owner}/${ref.repo}.git` })
  await git.setConfig({ fs, dir, path: `branch.${branch}.remote`, value: 'origin' })
  await git.setConfig({ fs, dir, path: `branch.${branch}.merge`, value: `refs/heads/${branch}` })
  await git.writeRef({ fs, dir, ref: `refs/heads/${branch}`, value: head.sha, force: true })
  await git.writeRef({ fs, dir, ref: `refs/remotes/origin/${branch}`, value: head.sha, force: true })
  if (head.parents.length > 0) await fs.promises.writeFile(`${dir}/.git/shallow`, `${head.sha}\n`)
  progress.report('Checking out files')
  await git.checkout({ fs, dir, ref: branch })
  return head.sha
}

export async function remoteOf(ctx: RepoContext): Promise<RepoRef | undefined> {
  const url = await ctx.git.getConfig({ fs: ctx.fs, dir: ctx.dir, path: 'remote.origin.url' })
  const match = typeof url === 'string' ? /github\.com[/:]([\w.-]+)\/([\w.-]+?)(?:\.git)?\/?$/.exec(url) : undefined
  return match ? { owner: match[1], repo: match[2] } : undefined
}

async function blobOids(ctx: RepoContext, treeOid: string, into = new Set<string>()): Promise<Set<string>> {
  const { tree } = await ctx.git.readTree({ fs: ctx.fs, dir: ctx.dir, oid: treeOid })
  for (const e of tree) {
    into.add(e.oid)
    if (e.type === 'tree') await blobOids(ctx, e.oid, into)
  }
  return into
}

// Fast-forward only. Resolves to a message for the user.
export async function pullFromGitHub(ctx: RepoContext, client: GitHubClient, ref: RepoRef, progress: Progress): Promise<string> {
  const { git, fs, dir } = ctx
  const branch = await git.currentBranch({ fs, dir })
  if (!branch) throw new Error('Not on a branch')
  const local = await git.resolveRef({ fs, dir, ref: 'HEAD' })

  progress.report('Checking GitHub')
  const compare = await client
    .json<{ status: string; total_commits: number; commits: ApiCommit[] }>(
      `/repos/${ref.owner}/${ref.repo}/compare/${local}...${encodeURIComponent(branch)}`
    )
    .catch((error) => {
      if (error instanceof GitHubError && error.kind === 'not-found') {
        throw new Error(`Cannot fast-forward: GitHub does not have the branch "${branch}" or your latest commit`)
      }
      throw error
    })
  if (compare.status === 'identical') return 'Already up to date'
  if (compare.status === 'behind') return 'Already up to date. Local commits are ahead of GitHub.'
  if (compare.status !== 'ahead') throw new Error(`Cannot fast-forward: "${branch}" has diverged from GitHub`)
  if (compare.commits.length < compare.total_commits) {
    throw new Error(`GitHub has ${compare.total_commits} new commits, too many to fast-forward. Clone the repository again.`)
  }

  const tip = compare.commits[compare.commits.length - 1]
  const entries = await fetchTree(client, ref, tip.commit.tree.sha)
  const localCommit = await git.readCommit({ fs, dir, oid: local })
  const have = await blobOids(ctx, localCommit.commit.tree)
  await writeSnapshot(ctx, client, ref, false, tip.sha, tip.commit.tree.sha, entries, have, progress)
  await writeCommits(ctx, compare.commits)

  progress.report('Updating files')
  await git.writeRef({ fs, dir, ref: `refs/heads/${branch}`, value: tip.sha, force: true })
  try {
    await git.checkout({ fs, dir, ref: branch })
  } catch (error) {
    await git.writeRef({ fs, dir, ref: `refs/heads/${branch}`, value: local, force: true })
    throw new Error(`Cannot pull while local changes would be overwritten: ${error instanceof Error ? error.message : error}`)
  }
  await git.writeRef({ fs, dir, ref: `refs/remotes/origin/${branch}`, value: tip.sha, force: true })
  return `Pulled ${compare.commits.length} commit${compare.commits.length === 1 ? '' : 's'}`
}

interface LocalCommit {
  oid: string
  commit: Awaited<ReturnType<Git['readCommit']>>['commit']
}

// First-parent chain from head down to, not including, base. Oldest first.
async function commitsSince(ctx: RepoContext, head: string, base: string): Promise<LocalCommit[]> {
  const chain: LocalCommit[] = []
  let oid = head
  while (oid !== base) {
    let commit: LocalCommit['commit']
    try {
      commit = (await ctx.git.readCommit({ fs: ctx.fs, dir: ctx.dir, oid })).commit
    } catch {
      throw new Error('Cannot push: the local history does not contain the latest commit on GitHub. Pull first.')
    }
    if (commit.parent.length > 1) throw new Error('Cannot push merge commits')
    chain.push({ oid, commit })
    if (commit.parent.length === 0) throw new Error('Cannot push: the local history does not contain the latest commit on GitHub. Pull first.')
    oid = commit.parent[0]
  }
  return chain.reverse()
}

function toBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(binary)
}

async function createTree(ctx: RepoContext, client: GitHubClient, ref: RepoRef, oid: string, known: Set<string>): Promise<void> {
  if (known.has(oid)) return
  const { tree } = await ctx.git.readTree({ fs: ctx.fs, dir: ctx.dir, oid })
  const blobs = tree.filter((e) => e.type === 'blob' && !known.has(e.oid))
  await pool(blobs, async (e) => {
    const { blob } = await ctx.git.readBlob({ fs: ctx.fs, dir: ctx.dir, oid: e.oid })
    const created = await client.json<{ sha: string }>(`/repos/${ref.owner}/${ref.repo}/git/blobs`, 'POST', {
      content: toBase64(blob),
      encoding: 'base64'
    })
    if (created.sha !== e.oid) throw new Error(`GitHub stored ${e.path} with a different hash`)
    known.add(e.oid)
  })
  for (const e of tree) if (e.type === 'tree') await createTree(ctx, client, ref, e.oid, known)
  const created = await client.json<{ sha: string }>(`/repos/${ref.owner}/${ref.repo}/git/trees`, 'POST', {
    tree: tree.map((e) => ({ path: e.path, mode: e.mode, type: e.type, sha: e.oid }))
  })
  if (created.sha !== oid) throw new Error('GitHub stored a directory with a different hash')
  known.add(oid)
}

// Fast-forward only. The commits are rebuilt on GitHub from the same data, so their ids stay the same.
export async function pushToGitHub(ctx: RepoContext, client: GitHubClient, ref: RepoRef, progress: Progress): Promise<string> {
  const { git, fs, dir } = ctx
  const branch = await git.currentBranch({ fs, dir })
  if (!branch) throw new Error('Not on a branch')
  const head = await git.resolveRef({ fs, dir, ref: 'HEAD' })

  progress.report('Checking GitHub')
  const remote = await client
    .json<{ object: { sha: string } }>(`/repos/${ref.owner}/${ref.repo}/git/ref/heads/${branch}`)
    .then((r) => r.object.sha, (error) => (error instanceof GitHubError && error.kind === 'not-found' ? undefined : Promise.reject(error)))
  if (remote === head) return 'Everything is up to date'

  let base = remote
  if (!base) {
    const info = await client.json<{ default_branch: string }>(`/repos/${ref.owner}/${ref.repo}`)
    base = await git.resolveRef({ fs, dir, ref: `refs/remotes/origin/${info.default_branch}` }).catch(() => undefined)
    if (!base) throw new Error(`Cannot push a new branch: no commit of ${info.default_branch} is known locally`)
  }
  const chain = await commitsSince(ctx, head, base)

  const known = await blobOids(ctx, (await git.readCommit({ fs, dir, oid: base })).commit.tree).catch(() => {
    throw new Error('Cannot push: the files of the latest GitHub commit are not stored locally. Clone the repository again.')
  })
  let parent = base
  for (const [i, { oid, commit }] of chain.entries()) {
    progress.report(`Uploading commit ${i + 1}/${chain.length}`)
    await createTree(ctx, client, ref, commit.tree, known)
    const created = await client.json<{ sha: string }>(`/repos/${ref.owner}/${ref.repo}/git/commits`, 'POST', commitPayload(commit, parent))
    if (created.sha !== oid) throw new Error(`GitHub created commit ${created.sha.slice(0, 7)} instead of ${oid.slice(0, 7)}. Nothing was pushed.`)
    parent = oid
  }

  progress.report('Updating branch')
  await (remote
    ? client.json(`/repos/${ref.owner}/${ref.repo}/git/refs/heads/${branch}`, 'PATCH', { sha: head, force: false })
    : client.json(`/repos/${ref.owner}/${ref.repo}/git/refs`, 'POST', { ref: `refs/heads/${branch}`, sha: head })
  ).catch((error) => {
    if (error instanceof GitHubError && error.status === 422) throw new Error('Cannot push: GitHub has new commits on this branch. Pull first.')
    throw error
  })
  await git.writeRef({ fs, dir, ref: `refs/remotes/origin/${branch}`, value: head, force: true })
  return `Pushed ${chain.length} commit${chain.length === 1 ? '' : 's'} to ${ref.owner}/${ref.repo}`
}
