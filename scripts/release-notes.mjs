// Prints the change list of the next release as Markdown: the squash-merged pull requests on master since
// the last tag, grouped by their Conventional Commit type. Write a short summary for users above it.
// Usage: node scripts/release-notes.mjs [since-tag [next-tag]]   (since: the newest tag reachable from HEAD)
import { execFileSync } from 'node:child_process'

const REPO = 'https://github.com/Project516/frc-dev-wasm'
const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim()

const since = process.argv[2] ?? git('describe', '--tags', '--abbrev=0')
const next = process.argv[3]
const titles = git('log', '--first-parent', '--format=%s', `${since}..HEAD`).split('\n').filter(Boolean)

const sections = [
  ['Breaking changes', (t) => t.breaking],
  ['Features', (t) => t.type === 'feat'],
  ['Fixes', (t) => t.type === 'fix' || t.type === 'perf'],
  ['Dependencies', (t) => t.scope === 'deps'],
  ['Build and CI', (t) => t.type === 'build' || t.type === 'ci'],
  ['Documentation', (t) => t.type === 'docs'],
  ['Other', () => true],
]

const groups = new Map(sections.map(([name]) => [name, []]))
for (const title of titles) {
  const m = /^(\w+)(?:\(([^)]+)\))?(!)?: (.*?)(?: \(#(\d+)\))?$/.exec(title)
  const entry = m ? { type: m[1], scope: m[2], breaking: !!m[3], summary: m[4], pr: m[5] } : { summary: title }
  const [name] = sections.find(([, matches]) => matches(entry))
  const scope = entry.scope && entry.scope !== 'deps' ? `**${entry.scope}:** ` : ''
  groups.get(name).push(`- ${scope}${entry.summary}${entry.pr ? ` ([#${entry.pr}](${REPO}/pull/${entry.pr}))` : ''}`)
}

const out = []
for (const [name, lines] of groups) if (lines.length) out.push(`## ${name}\n\n${lines.join('\n')}\n`)
if (next) out.push(`**Full list:** ${REPO}/compare/${since}...${next}`)
console.log(out.join('\n'))
