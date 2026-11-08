// Git object construction from GitHub REST API data. No dependencies, so it runs in Node too.

const encoder = new TextEncoder()

export function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

export function fromHex(hex: string): Uint8Array {
  return Uint8Array.from(hex.match(/../g) ?? [], (h) => parseInt(h, 16))
}

export async function gitOid(type: 'blob' | 'tree' | 'commit', body: Uint8Array): Promise<string> {
  const header = encoder.encode(`${type} ${body.length}\0`)
  const data = new Uint8Array(header.length + body.length)
  data.set(header)
  data.set(body, header.length)
  return toHex(new Uint8Array(await crypto.subtle.digest('SHA-1', data)))
}

export interface TreeEntry {
  mode: string
  path: string
  sha: string
}

const isTree = (mode: string) => mode === '040000' || mode === '40000'

function compareBytes(a: Uint8Array, b: Uint8Array): number {
  for (let i = 0; i < Math.min(a.length, b.length); i++) if (a[i] !== b[i]) return a[i] - b[i]
  return a.length - b.length
}

export function treeBody(entries: TreeEntry[]): Uint8Array {
  const sorted = entries
    .map((e) => ({ e, key: encoder.encode(isTree(e.mode) ? `${e.path}/` : e.path) }))
    .sort((a, b) => compareBytes(a.key, b.key))
  const parts = sorted.map(({ e }) => {
    const head = encoder.encode(`${e.mode.replace(/^0+/, '')} ${e.path}\0`)
    const part = new Uint8Array(head.length + 20)
    part.set(head)
    part.set(fromHex(e.sha), head.length)
    return part
  })
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let offset = 0
  for (const p of parts) {
    out.set(p, offset)
    offset += p.length
  }
  return out
}

export interface RestPerson {
  name: string
  email: string
  date: string
}

// The shape shared by the REST commit responses and the body of POST /git/commits
export interface RestCommit {
  tree: string
  parents: string[]
  author: RestPerson
  committer: RestPerson
  message: string
  verification?: { signature?: string | null; payload?: string | null }
}

function formatTz(minutesEast: number): string {
  const abs = Math.abs(minutesEast)
  return `${minutesEast < 0 ? '-' : '+'}${String(Math.floor(abs / 60)).padStart(2, '0')}${String(abs % 60).padStart(2, '0')}`
}

// Timestamp and offset of an ISO 8601 date. The REST API reports UTC ("Z") for commits created elsewhere.
function parseDate(date: string): { seconds: number; tz: string } {
  const match = /([+-])(\d\d):?(\d\d)$/.exec(date)
  const tz = match ? `${match[1]}${match[2]}${match[3]}` : '+0000'
  return { seconds: Math.floor(Date.parse(date) / 1000), tz }
}

function person(role: string, p: RestPerson, tz?: string): string {
  const { seconds, tz: own } = parseDate(p.date)
  return `${role} ${p.name} <${p.email}> ${seconds} ${tz ?? own}`
}

export function commitBody(c: RestCommit, authorTz?: string, committerTz?: string): string {
  const lines = [`tree ${c.tree}`, ...c.parents.map((p) => `parent ${p}`), person('author', c.author, authorTz), person('committer', c.committer, committerTz)]
  return `${lines.join('\n')}\n\n${c.message}`
}

// Continuation lines of a header start with a space. The signature's final newline becomes a line of one space.
const signatureHeader = (signature: string) => `gpgsig ${signature.split('\n').map((l, i) => (i ? ` ${l}` : l)).join('\n')}`

// Offsets in use worldwide, in minutes east of UTC
const COMMON_OFFSETS = [
  -720, -660, -600, -570, -540, -480, -420, -360, -300, -240, -210, -180, -120, -60, 0, 60, 120, 180, 210, 240, 270, 300, 330, 345, 360, 390, 420, 480,
  525, 540, 570, 600, 630, 660, 720, 765, 780, 840
].map(formatTz)

const EVERY_OFFSET = Array.from({ length: 105 }, (_, i) => formatTz(-720 + i * 15))

async function matches(body: string, sha: string): Promise<boolean> {
  return (await gitOid('commit', encoder.encode(body))) === sha
}

// The REST API drops the timezone of commit dates, so the original object is found by trying
// offsets until the hash matches. Signed commits carry the exact signed text instead.
export async function rebuildCommit(c: RestCommit, sha: string): Promise<Uint8Array> {
  const found = (body: string) => encoder.encode(body)
  const { payload, signature } = c.verification ?? {}
  if (payload && signature) {
    const end = payload.indexOf('\n\n')
    const signed = `${payload.slice(0, end)}\n${signatureHeader(signature)}${payload.slice(end)}`
    if (await matches(signed, sha)) return found(signed)
  }
  const variants = [c, { ...c, message: `${c.message}\n` }]
  for (const variant of variants) {
    for (const tz of EVERY_OFFSET) {
      const body = commitBody(variant, tz, tz)
      if (await matches(body, sha)) return found(body)
    }
  }
  for (const variant of variants) {
    for (const authorTz of COMMON_OFFSETS) {
      for (const committerTz of COMMON_OFFSETS) {
        const body = commitBody(variant, authorTz, committerTz)
        if (await matches(body, sha)) return found(body)
      }
    }
  }
  throw new Error(`Cannot rebuild commit ${sha.slice(0, 7)} from the GitHub API response`)
}

export interface RepoRef {
  owner: string
  repo: string
}

// https://github.com/o/r, with or without .git or a trailing slash, git@github.com:o/r.git, or o/r
export function parseGitHubRepo(input: string): RepoRef | undefined {
  const text = input.trim().replace(/\/+$/, '')
  const match =
    /^(?:https?:\/\/(?:www\.)?github\.com\/|git@github\.com:)([\w.-]+)\/([\w.-]+?)(?:\.git)?(?:\/tree\/.*)?$/.exec(text) ??
    /^([\w.-]+)\/([\w.-]+?)(?:\.git)?$/.exec(text)
  // GitHub has no repository named . or .., which would point a clone at the workspace or above it
  return match && !/^\.+$/.test(match[2]) ? { owner: match[1], repo: match[2] } : undefined
}

// Offset in the form the REST API accepts for commit dates
export function restDate(timestamp: number, timezoneOffset: number): string {
  const local = new Date((timestamp - timezoneOffset * 60) * 1000).toISOString().slice(0, 19)
  const tz = formatTz(-timezoneOffset)
  return `${local}${tz.slice(0, 3)}:${tz.slice(3)}`
}

interface LocalPerson {
  name: string
  email: string
  timestamp: number
  timezoneOffset: number
}

// Body of POST /git/commits for a local commit, so that GitHub recreates the same object
export function commitPayload(commit: { message: string; tree: string; author: LocalPerson; committer: LocalPerson }, parent: string): Omit<RestCommit, 'verification'> {
  const person = (p: LocalPerson): RestPerson => ({ name: p.name, email: p.email, date: restDate(p.timestamp, p.timezoneOffset) })
  return { message: commit.message, tree: commit.tree, parents: [parent], author: person(commit.author), committer: person(commit.committer) }
}
