// A small `gh` over the GitHub REST API. Output follows what gh prints when it is not writing
// to a terminal. No VS Code or DOM imports, so it runs in Node too.
import { parseGitHubRepo, type RepoRef } from '../../git/objects.ts'

const API = 'https://api.github.com'
const decoder = new TextDecoder()

export interface GhHost {
  fetch(url: string, init: RequestInit): Promise<Response>
  // The stored token. With `prompt` set it asks for one when there is none.
  token(prompt: boolean): Promise<string | undefined>
  // Stores the token, or asks for one when none is given. Resolves to false if the prompt was dismissed.
  login(token?: string): Promise<boolean>
  // Forgets the stored token. Resolves to false if there was none.
  logout(): Promise<boolean>
  // The GitHub repository of the working directory's origin remote
  defaultRepo(): Promise<RepoRef | undefined>
  currentBranch(): Promise<string | undefined>
  stdin?: Uint8Array
  out(text: string): void
  err(text: string): void
}

class GhError extends Error {
  hint?: string

  constructor(message: string, hint?: string) {
    super(message)
    this.hint = hint
  }
}

const USAGE = `GitHub CLI for the FRC Dev shell, over the GitHub REST API. Supported:
  gh api <endpoint> [-X <method>] [-f <key=value>] [-F <key=value>]
  gh auth login | logout | status
  gh repo view [<owner/repo>]
  gh issue list | view | create
  gh pr list | view | create | status
Repository commands take -R <owner/repo>. Without it they use the origin remote.
`

interface Spec {
  value?: Record<string, string>
  bool?: Record<string, string>
}

// Flags are named with their dashes, and both spellings of a flag map to one key
function parseFlags(args: string[], spec: Spec) {
  const values = new Map<string, string[]>()
  const bools = new Set<string>()
  const operands: string[] = []
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]
    if (arg === '--') {
      operands.push(...args.slice(i + 1))
      break
    }
    if (!arg.startsWith('-') || arg === '-') {
      operands.push(arg)
      continue
    }
    const eq = arg.startsWith('--') ? arg.indexOf('=') : -1
    const name = eq > 0 ? arg.slice(0, eq) : arg
    const key = spec.value?.[name]
    if (key) {
      const value = eq > 0 ? arg.slice(eq + 1) : args[++i]
      if (value === undefined) throw new GhError(`flag needs an argument: ${name}`)
      values.set(key, [...(values.get(key) ?? []), value])
    } else if (spec.bool?.[name]) bools.add(spec.bool[name])
    else throw new GhError(`unknown flag: ${name}`)
  }
  return { values, bools, operands, first: (key: string) => values.get(key)?.[0] }
}

const REPO_FLAG = { '-R': 'repo', '--repo': 'repo' }

async function repoOf(host: GhHost, given: string | undefined): Promise<RepoRef> {
  if (given) {
    const repo = parseGitHubRepo(given)
    if (!repo) throw new GhError(`expected the "OWNER/REPO" format, got "${given}"`)
    return repo
  }
  const repo = await host.defaultRepo()
  if (!repo) throw new GhError('no GitHub repository found', 'Run it in a clone of a GitHub repository, or pass -R owner/repo.')
  return repo
}

interface Reply {
  status: number
  ok: boolean
  text: string
}

interface CallOptions {
  accept?: string
  // A 404 may be a private repository, so ask for a token when there is none
  promptOn404?: boolean
}

// Asks for a token and retries once when the request shows that one is needed
async function call(host: GhHost, method: string, path: string, body?: unknown, options: CallOptions = {}): Promise<Reply> {
  let token = await host.token(false)
  for (let attempt = 0; ; attempt++) {
    let response: Response
    try {
      response = await host.fetch(`${API}${path}`, {
        method,
        headers: {
          Accept: options.accept ?? 'application/vnd.github+json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
        },
        body: body === undefined ? undefined : JSON.stringify(body)
      })
    } catch {
      throw new GhError('error connecting to api.github.com', 'Check your network connection.')
    }
    const text = await response.text()
    const limited = response.status === 429 || (response.status === 403 && response.headers.get('x-ratelimit-remaining') === '0')
    const needsToken = response.status === 401 || limited || (response.status === 404 && options.promptOn404)
    if (needsToken && !token && attempt === 0) {
      token = await host.token(true)
      if (token) continue
    }
    return { status: response.status, ok: response.ok, text }
  }
}

function errorOf(reply: Reply): GhError {
  let message = reply.text
  try {
    const body = JSON.parse(reply.text) as { message?: string; errors?: unknown[] }
    message = body.message ?? message
    const details = (body.errors ?? []).map((e) => (typeof e === 'string' ? e : ((e as { message?: string }).message ?? '')))
    if (details.some(Boolean)) message += `: ${details.filter(Boolean).join(', ')}`
  } catch {
    // not JSON
  }
  return new GhError(`${message} (HTTP ${reply.status})`, reply.status === 401 ? 'Try authenticating with:  gh auth login' : undefined)
}

async function json<T>(host: GhHost, method: string, path: string, body?: unknown, options: CallOptions = {}): Promise<T> {
  const reply = await call(host, method, path, body, { promptOn404: true, ...options })
  if (!reply.ok) throw errorOf(reply)
  return JSON.parse(reply.text) as T
}

const slug = (repo: RepoRef) => `/repos/${repo.owner}/${repo.repo}`
const names = (items: Array<{ name?: string; login?: string }>) => items.map((i) => i.name ?? i.login ?? '').join(', ')

async function api(host: GhHost, args: string[]): Promise<number> {
  const { values, operands, first } = parseFlags(args, {
    value: { '-X': 'method', '--method': 'method', '-f': 'raw', '--raw-field': 'raw', '-F': 'typed', '--field': 'typed' }
  })
  if (operands.length !== 1) throw new GhError(`accepts 1 arg(s), received ${operands.length}`, 'usage: gh api <endpoint> [-X <method>] [-f <key=value>] [-F <key=value>]')
  let path = `/${operands[0].replace(/^\/+/, '')}`
  if (/\{(owner|repo)\}|:(owner|repo)\b/.test(path)) {
    const repo = await repoOf(host, undefined)
    path = path.replace(/\{owner\}|:owner\b/g, repo.owner).replace(/\{repo\}|:repo\b/g, repo.repo)
  }

  const fields: Record<string, unknown> = {}
  for (const [kind, pairs] of [['raw', values.get('raw') ?? []], ['typed', values.get('typed') ?? []]] as const) {
    for (const pair of pairs) {
      const eq = pair.indexOf('=')
      if (eq < 1) throw new GhError(`field "${pair}" requires a value separated by an '=' sign`)
      const key = pair.slice(0, eq)
      const value = pair.slice(eq + 1)
      if (kind === 'raw') fields[key] = value
      else if (value.startsWith('@')) throw new GhError('reading a field from a file is not supported in this terminal')
      else fields[key] = value === 'true' ? true : value === 'false' ? false : value === 'null' ? null : /^-?\d+$/.test(value) ? Number(value) : value
    }
  }
  const hasFields = Object.keys(fields).length > 0
  const method = (first('method') ?? (hasFields ? 'POST' : 'GET')).toUpperCase()
  let body: unknown
  if (hasFields && (method === 'GET' || method === 'HEAD')) {
    path += `${path.includes('?') ? '&' : '?'}${Object.entries(fields)
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
      .join('&')}`
  } else if (hasFields) body = fields

  const reply = await call(host, method, path, body)
  host.out(reply.text)
  if (!reply.ok) throw errorOf(reply)
  return 0
}

interface Account {
  login: string
}

function mask(token: string): string {
  const prefix = token.includes('_') ? token.slice(0, token.indexOf('_') + 1) : ''
  return `${prefix}${'*'.repeat(Math.max(4, token.length - prefix.length))}`
}

async function auth(host: GhHost, args: string[]): Promise<number> {
  const [sub, ...rest] = args
  if (sub === 'login') {
    if (rest.includes('--with-token')) {
      const token = decoder.decode(host.stdin ?? new Uint8Array()).trim()
      if (!token) throw new GhError('no token on standard input', 'Use: echo <token> | gh auth login --with-token')
      await host.login(token)
    } else if (!(await host.login())) return 1
    host.err('Token saved for github.com\n')
    return 0
  }
  if (sub === 'logout') {
    const had = await host.logout()
    host.err(had ? 'Logged out of github.com\n' : 'You are not logged in to github.com\n')
    return had ? 0 : 1
  }
  if (sub === 'status') {
    const token = await host.token(false)
    if (!token) {
      host.err('You are not logged into any GitHub hosts. To log in, run: gh auth login\n')
      return 1
    }
    const reply = await call(host, 'GET', '/user')
    if (!reply.ok) {
      host.out(`github.com\n  Failed to log in to github.com using token (${mask(token)})\n`)
      return 1
    }
    const { login } = JSON.parse(reply.text) as Account
    host.out(`github.com\n  Logged in to github.com account ${login} (secret storage)\n  Active account: true\n  Git operations protocol: https\n  Token: ${mask(token)}\n`)
    return 0
  }
  throw unsupported(`gh auth ${sub ?? ''}`.trim(), ['login', 'logout', 'status'], sub)
}

interface Label {
  name: string
}

interface Issue {
  number: number
  title: string
  state: string
  body: string | null
  html_url: string
  updated_at: string
  user: Account | null
  labels: Label[]
  assignees: Account[]
  milestone: { title: string } | null
  comments: number
  pull_request?: unknown
}

interface Pull {
  number: number
  title: string
  state: string
  draft: boolean
  merged_at: string | null
  body: string | null
  html_url: string
  created_at: string
  head: { ref: string }
  user: Account | null
  labels: Label[]
  assignees: Account[]
  milestone: { title: string } | null
  additions?: number
  deletions?: number
}

const pullState = (p: Pull) => (p.merged_at ? 'MERGED' : p.draft ? 'DRAFT' : p.state.toUpperCase())

function listFlags(args: string[]) {
  const parsed = parseFlags(args, { value: { ...REPO_FLAG, '-s': 'state', '--state': 'state', '-L': 'limit', '--limit': 'limit' } })
  const state = parsed.first('state') ?? 'open'
  if (!['open', 'closed', 'all', 'merged'].includes(state)) throw new GhError(`invalid argument "${state}" for "-s, --state" flag`)
  const limit = Number(parsed.first('limit') ?? 30)
  if (!Number.isInteger(limit) || limit < 1) throw new GhError('invalid limit: must be a positive number')
  return { ...parsed, state, limit: Math.min(limit, 100) }
}

async function issueList(host: GhHost, args: string[]): Promise<number> {
  const { first, state, limit } = listFlags(args)
  const repo = await repoOf(host, first('repo'))
  // The issues endpoint also lists pull requests, so ask for more and drop them
  const issues = await json<Issue[]>(host, 'GET', `${slug(repo)}/issues?state=${state}&per_page=100`)
  for (const i of issues.filter((i) => !i.pull_request).slice(0, limit)) {
    host.out(`${i.number}\t${i.state.toUpperCase()}\t${i.title}\t${i.labels.map((l) => l.name).join(', ')}\t${i.updated_at}\n`)
  }
  return 0
}

function numberOf(operand: string | undefined, what: string): number {
  const n = Number(operand?.replace(/^#/, ''))
  if (!Number.isInteger(n) || n < 1) throw new GhError(`${operand ? `invalid ${what} number: ${operand}` : `${what} number required`}`)
  return n
}

async function issueView(host: GhHost, args: string[]): Promise<number> {
  const { operands, first } = parseFlags(args, { value: REPO_FLAG })
  const repo = await repoOf(host, first('repo'))
  const i = await json<Issue>(host, 'GET', `${slug(repo)}/issues/${numberOf(operands[0], 'issue')}`)
  host.out(
    [
      `title:\t${i.title}`,
      `state:\t${i.state.toUpperCase()}`,
      `author:\t${i.user?.login ?? ''}`,
      `labels:\t${names(i.labels)}`,
      `comments:\t${i.comments}`,
      `assignees:\t${names(i.assignees)}`,
      `projects:\t`,
      `milestone:\t${i.milestone?.title ?? ''}`,
      `number:\t${i.number}`,
      '--',
      `${i.body ?? ''}\n`
    ].join('\n')
  )
  return 0
}

// The body from --body, or from --body-file (- is standard input)
function bodyOf(host: GhHost, first: (key: string) => string | undefined): string | undefined {
  const file = first('bodyFile')
  if (file === undefined) return first('body')
  if (file !== '-') throw new GhError('reading a body from a file is not supported in this terminal', 'Use --body-file - and pipe the file in.')
  return decoder.decode(host.stdin ?? new Uint8Array())
}

const CREATE_FLAGS = { '-t': 'title', '--title': 'title', '-b': 'body', '--body': 'body', '-F': 'bodyFile', '--body-file': 'bodyFile' }

function requireTitleAndBody(title: string | undefined, body: string | undefined): asserts title is string {
  if (title === undefined || body === undefined) throw new GhError('must provide `--title` and `--body` when not running interactively')
}

async function issueCreate(host: GhHost, args: string[]): Promise<number> {
  const { first } = parseFlags(args, { value: { ...REPO_FLAG, ...CREATE_FLAGS } })
  const body = bodyOf(host, first)
  const title = first('title')
  requireTitleAndBody(title, body)
  const repo = await repoOf(host, first('repo'))
  const issue = await json<Issue>(host, 'POST', `${slug(repo)}/issues`, { title, body })
  host.out(`${issue.html_url}\n`)
  return 0
}

async function pullList(host: GhHost, args: string[]): Promise<number> {
  const { first, state, limit } = listFlags(args)
  const repo = await repoOf(host, first('repo'))
  const api = state === 'merged' ? 'closed' : state
  const pulls = await json<Pull[]>(host, 'GET', `${slug(repo)}/pulls?state=${api}&per_page=100`)
  for (const p of pulls.filter((p) => state !== 'merged' || p.merged_at).slice(0, limit)) {
    host.out(`${p.number}\t${p.title}\t${p.head.ref}\t${pullState(p)}\t${p.created_at}\n`)
  }
  return 0
}

// A pull request by number, or by branch name; no argument means the current branch
async function findPull(host: GhHost, repo: RepoRef, operand: string | undefined): Promise<Pull> {
  if (operand && /^#?\d+$/.test(operand)) return json<Pull>(host, 'GET', `${slug(repo)}/pulls/${numberOf(operand, 'pull request')}`)
  const branch = operand ?? (await host.currentBranch())
  if (!branch) throw new GhError('could not determine the current branch')
  const found = await json<Pull[]>(host, 'GET', `${slug(repo)}/pulls?state=open&head=${encodeURIComponent(`${repo.owner}:${branch}`)}`)
  if (found.length === 0) throw new GhError(`no pull requests found for branch "${branch}"`)
  return json<Pull>(host, 'GET', `${slug(repo)}/pulls/${found[0].number}`)
}

async function pullView(host: GhHost, args: string[]): Promise<number> {
  const { operands, first } = parseFlags(args, { value: REPO_FLAG })
  const repo = await repoOf(host, first('repo'))
  const p = await findPull(host, repo, operands[0])
  host.out(
    [
      `title:\t${p.title}`,
      `state:\t${pullState(p)}`,
      `author:\t${p.user?.login ?? ''}`,
      `labels:\t${names(p.labels)}`,
      `assignees:\t${names(p.assignees)}`,
      `projects:\t`,
      `milestone:\t${p.milestone?.title ?? ''}`,
      `number:\t${p.number}`,
      `url:\t${p.html_url}`,
      `additions:\t${p.additions ?? ''}`,
      `deletions:\t${p.deletions ?? ''}`,
      '--',
      `${p.body ?? ''}\n`
    ].join('\n')
  )
  return 0
}

async function pullCreate(host: GhHost, args: string[]): Promise<number> {
  const { first, bools } = parseFlags(args, {
    value: { ...REPO_FLAG, ...CREATE_FLAGS, '-B': 'base', '--base': 'base', '-H': 'head', '--head': 'head' },
    bool: { '-d': 'draft', '--draft': 'draft' }
  })
  const body = bodyOf(host, first)
  const title = first('title')
  requireTitleAndBody(title, body)
  const repo = await repoOf(host, first('repo'))
  const head = first('head') ?? (await host.currentBranch())
  if (!head) throw new GhError('could not determine the head branch', 'Pass --head <branch>.')
  const base = first('base') ?? (await json<{ default_branch: string }>(host, 'GET', slug(repo))).default_branch
  const pull = await json<Pull>(host, 'POST', `${slug(repo)}/pulls`, { title, body, head, base, draft: bools.has('draft') })
  host.out(`${pull.html_url}\n`)
  return 0
}

async function pullStatus(host: GhHost, args: string[]): Promise<number> {
  const { first } = parseFlags(args, { value: REPO_FLAG })
  const repo = await repoOf(host, first('repo'))
  const { login } = await json<Account>(host, 'GET', '/user')
  const search = async (qualifier: string) => {
    const q = encodeURIComponent(`repo:${repo.owner}/${repo.repo} is:pr is:open ${qualifier}:${login}`)
    return (await json<{ items: Array<{ number: number; title: string }> }>(host, 'GET', `/search/issues?q=${q}&per_page=30`)).items
  }
  const line = (n: number, title: string, branch?: string) => `  #${n}  ${title}${branch ? ` [${branch}]` : ''}\n`
  host.out(`\nRelevant pull requests in ${repo.owner}/${repo.repo}\n\nCurrent branch\n`)
  const branch = await host.currentBranch()
  const current = branch ? await json<Pull[]>(host, 'GET', `${slug(repo)}/pulls?state=open&head=${encodeURIComponent(`${repo.owner}:${branch}`)}`) : []
  if (current.length > 0) for (const p of current) host.out(line(p.number, p.title, p.head.ref))
  else host.out(`  There is no pull request associated with [${branch ?? 'no branch'}]\n`)
  host.out('\nCreated by you\n')
  const created = await search('author')
  if (created.length > 0) for (const p of created) host.out(line(p.number, p.title))
  else host.out('  You have no open pull requests\n')
  host.out('\nRequesting a code review from you\n')
  const requested = await search('review-requested')
  if (requested.length > 0) for (const p of requested) host.out(line(p.number, p.title))
  else host.out('  You have no pull requests to review\n')
  return 0
}

async function repoView(host: GhHost, args: string[]): Promise<number> {
  const { operands, first } = parseFlags(args, { value: REPO_FLAG })
  const repo = await repoOf(host, operands[0] ?? first('repo'))
  const info = await json<{ full_name: string; description: string | null }>(host, 'GET', slug(repo))
  host.out(`name:\t${info.full_name}\ndescription:\t${info.description ?? ''}\n--\n`)
  const readme = await call(host, 'GET', `${slug(repo)}/readme`, undefined, { accept: 'application/vnd.github.raw+json' })
  if (readme.ok) host.out(readme.text.endsWith('\n') ? readme.text : `${readme.text}\n`)
  return 0
}

function unsupported(command: string, supported: string[], sub: string | undefined): GhError {
  return new GhError(`${sub ? `'${sub}'` : 'a subcommand'} is not supported in this terminal`, `Supported ${command}: ${supported.join(', ')}`)
}

type Handler = (host: GhHost, args: string[]) => Promise<number>

const GROUPS: Record<string, Record<string, Handler>> = {
  repo: { view: repoView },
  issue: { list: issueList, view: issueView, create: issueCreate },
  pr: { list: pullList, view: pullView, create: pullCreate, status: pullStatus }
}

// Runs one gh command and resolves to its exit status
export async function runGh(args: string[], host: GhHost): Promise<number> {
  const [command, sub, ...rest] = args
  try {
    if (command === undefined || command === 'help' || command === '--help' || command === '-h') {
      host.out(USAGE)
      return command === undefined ? 1 : 0
    }
    if (command === '--version' || command === 'version') {
      host.out('gh version (FRC Dev shell built-in, over the GitHub REST API)\n')
      return 0
    }
    if (command === 'api') return await api(host, args.slice(1))
    if (command === 'auth') return await auth(host, args.slice(1))
    const group = GROUPS[command]
    if (!group) throw unsupported('gh', ['api', 'auth', 'repo', 'issue', 'pr'], command)
    const handler = group[sub ?? '']
    if (!handler) throw unsupported(`gh ${command}`, Object.keys(group), sub)
    return await handler(host, rest)
  } catch (error) {
    if (!(error instanceof GhError)) throw error
    host.err(`gh: ${error.message}\n${error.hint ? `${error.hint}\n` : ''}`)
    return 1
  }
}
