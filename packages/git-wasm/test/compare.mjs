// Runs the same commands with desktop git and with the WebAssembly build (through GitHost, files
// mounted lazily and changes written back after each command) on identical repositories, and
// compares stdout, stderr and exit code byte for byte. Needs `pnpm build:git` and git on the host.
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { runGit } from './runner.mjs'

const base = fs.mkdtempSync(path.join(tmpdir(), 'git-wasm-'))
const env = {
  LC_ALL: 'C',
  TZ: 'UTC',
  GIT_CONFIG_NOSYSTEM: '1',
  GIT_CONFIG_GLOBAL: '/dev/null',
  GIT_TEMPLATE_DIR: '/nonexistent',
  GIT_PAGER: 'cat',
  GIT_TERMINAL_PROMPT: '0',
  GIT_AUTHOR_NAME: 'Dev',
  GIT_AUTHOR_EMAIL: 'dev@example.com',
  GIT_COMMITTER_NAME: 'Dev',
  GIT_COMMITTER_EMAIL: 'dev@example.com',
  GIT_CONFIG_COUNT: '3',
  GIT_CONFIG_KEY_0: 'gc.auto',
  GIT_CONFIG_VALUE_0: '0',
  GIT_CONFIG_KEY_1: 'maintenance.auto',
  GIT_CONFIG_VALUE_1: 'false',
  GIT_CONFIG_KEY_2: 'init.defaultBranch',
  GIT_CONFIG_VALUE_2: 'main'
}
let tick = 0
const stamp = () => {
  const date = `2026-01-01T12:${String(++tick).padStart(2, '0')}:00+0000`
  return { GIT_AUTHOR_DATE: date, GIT_COMMITTER_DATE: date }
}

const desktopDir = path.join(base, 'desktop')
const wasmDir = path.join(base, 'wasm')
fs.mkdirSync(desktopDir)
fs.mkdirSync(wasmDir)

const desktop = (args, extra) => {
  const r = spawnSync('git', args, { cwd: desktopDir, env: { ...env, ...extra, PATH: process.env.PATH, HOME: base }, encoding: 'buffer' })
  return { status: r.status, stdout: r.stdout, stderr: r.stderr }
}

const wasm = (args, extra) => runGit(wasmDir, args, { env: { ...env, ...extra, HOME: '/home' } })

const steps = [
  ['git', 'init'],
  // An unknown command must not start git again: there is no git-<command> to run
  ['git', 'frc-no-such-command'],
  ['write', 'a.txt', 'one\ntwo\nthree\n'],
  ['git', 'status'],
  ['git', 'add', 'a.txt'],
  ['git', 'commit', '-m', 'first'],
  ['git', 'log'],
  ['write', 'a.txt', 'one\ntwo changed\nthree\nfour\n'],
  ['write', 'dir/b.txt', 'b\n'],
  ['git', 'status'],
  ['git', 'diff'],
  ['git', 'add', '-A'],
  ['git', 'diff', '--cached', '--stat'],
  ['git', 'commit', '-m', 'second', '-m', 'body text'],
  ['git', 'log', '--oneline', '--graph', '--decorate'],
  ['git', 'show', 'HEAD~1:a.txt'],
  ['git', 'switch', '-c', 'feature'],
  ['write', 'f.txt', 'feature\n'],
  ['git', 'add', 'f.txt'],
  ['git', 'commit', '-m', 'feature work'],
  ['git', 'switch', 'main'],
  ['git', 'merge', 'feature'],
  ['git', 'switch', '-c', 'topic'],
  ['write', 'a.txt', 'one\ntwo topic\nthree\nfour\n'],
  ['git', 'commit', '-am', 'topic change'],
  ['git', 'switch', 'main'],
  ['write', 'a.txt', 'one\ntwo main\nthree\nfour\n'],
  ['git', 'commit', '-am', 'main change'],
  ['git', 'merge', 'topic', '-m', 'merge topic'],
  ['git', 'status'],
  ['git', 'diff'],
  ['git', 'merge', '--abort'],
  ['git', 'tag', '-a', 'v1', '-m', 'release one'],
  ['git', 'mv', 'f.txt', 'g.txt'],
  ['git', 'status', '--porcelain=v2', '--branch'],
  ['git', 'commit', '-m', 'rename'],
  ['write', 'a.txt', 'stashed\n'],
  ['write', 'new.txt', 'untracked\n'],
  ['git', 'stash', '-u'],
  ['git', 'stash', 'list'],
  ['git', 'status', '-s'],
  ['git', 'stash', 'pop'],
  ['git', 'status', '-s'],
  ['git', 'checkout', '--', 'a.txt'],
  ['git', 'rm', '-r', '--cached', 'dir'],
  ['git', 'reset', '--hard'],
  ['git', 'cherry-pick', 'topic'],
  ['git', 'revert', '--no-edit', 'HEAD'],
  ['git', 'log', '--oneline', '--all'],
  ['git', 'ls-files', '--stage'],
  ['git', 'fsck']
]

const norm = (buf, dir) => Buffer.from(buf.toString('latin1').split(dir).join('<repo>'), 'latin1')
// The text around the first difference
const clip = (buf, other) => {
  let at = 0
  while (at < buf.length && buf[at] === other[at]) at++
  return JSON.stringify(buf.toString('latin1', Math.max(0, at - 80), at + 220))
}
const failed = []
for (const [kind, ...rest] of steps) {
  if (kind === 'write') {
    for (const dir of [desktopDir, wasmDir]) {
      fs.mkdirSync(path.join(dir, rest[0], '..'), { recursive: true })
      fs.writeFileSync(path.join(dir, rest[0]), rest[1])
    }
    continue
  }
  const extra = stamp()
  const x = desktop(rest, extra)
  const y = wasm(rest, extra)
  // Newer git versions hint `git config set advice.`, older ones `git config advice.`
  const hint = (b) => Buffer.from(b.toString('latin1').replaceAll('git config set advice.', 'git config advice.'), 'latin1')
  x.stdout = norm(x.stdout, desktopDir)
  x.stderr = hint(norm(x.stderr, desktopDir))
  y.stdout = norm(y.stdout, '/repo')
  y.stderr = hint(norm(y.stderr, '/repo'))
  const same = x.status === y.status && !Buffer.compare(x.stdout, y.stdout) && !Buffer.compare(x.stderr, y.stderr)
  const name = `git ${rest.join(' ')}`
  console.log(`${same ? 'ok  ' : 'FAIL'} ${name}`)
  if (!same) {
    failed.push(name)
    console.log(`     exit desktop=${x.status} wasm=${y.status}`)
    if (Buffer.compare(x.stdout, y.stdout)) console.log(`     stdout desktop=${clip(x.stdout, y.stdout)}\n            wasm=${clip(y.stdout, x.stdout)}`)
    if (Buffer.compare(x.stderr, y.stderr)) console.log(`     stderr desktop=${clip(x.stderr, y.stderr)}\n            wasm=${clip(y.stderr, x.stderr)}`)
  }
}

// The working trees must match too
const listing = (dir) =>
  fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile() && !path.join(e.parentPath, e.name).includes(`${path.sep}.git${path.sep}`))
    .map((e) => `${path.relative(dir, path.join(e.parentPath, e.name))}:${fs.readFileSync(path.join(e.parentPath, e.name), 'utf8')}`)
    .sort()
    .join('\n')
const sameTree = listing(desktopDir) === listing(wasmDir)
console.log(`${sameTree ? 'ok  ' : 'FAIL'} working trees match`)
if (!sameTree) failed.push('working trees match')

fs.rmSync(base, { recursive: true, force: true })
console.log(failed.length ? `failed: ${failed.join(', ')}` : `all ${steps.filter((s) => s[0] === 'git').length} commands identical`)
process.exit(failed.length ? 1 : 0)
