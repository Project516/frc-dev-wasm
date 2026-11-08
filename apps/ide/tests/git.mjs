// Git support tests. Node sections run the Git code against an in-process fake of the GitHub REST API.
// The browser section needs a build (pnpm build). It clones from the real GitHub API, 3 requests per clone
// (unauthenticated requests are limited to 60 per hour), and drives the Source Control view.
// GIT_TEST_SKIP_BROWSER=1 runs the Node sections only. GIT_TEST_SECTION=folder (or clone, unsupported, big)
// runs one browser section.
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import fs from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as git from 'isomorphic-git'
import { createServer } from 'vite'
import { createFakeGitHub } from './fakeGithub.mjs'
import { browserTests } from './gitBrowser.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`)
  if (!ok) failures.push(name)
}
const fixture = (name) => JSON.parse(readFileSync(join(root, 'tests/fixtures', name), 'utf8'))

const vite = await createServer({ root, configFile: false, appType: 'custom', server: { middlewareMode: true }, optimizeDeps: { noDiscovery: true }, logLevel: 'error' })
const load = (path) => vite.ssrLoadModule(path)
const objects = await load('/src/git/objects.ts')
const github = await load('/src/git/github.ts')
const { classify } = await load('/src/git/status.ts')

async function unitTests() {
  const { gitOid, treeBody, rebuildCommit, parseGitHubRepo, commitBody, commitPayload } = objects
  const bytes = (s) => new TextEncoder().encode(s)

  for (const [input, expected] of [
    ['https://github.com/octocat/Hello-World', 'octocat/Hello-World'],
    ['https://github.com/octocat/Hello-World.git', 'octocat/Hello-World'],
    ['https://github.com/octocat/Hello-World/', 'octocat/Hello-World'],
    ['git@github.com:octocat/Hello-World.git', 'octocat/Hello-World'],
    ['octocat/Hello-World', 'octocat/Hello-World'],
    ['https://example.com/a/b', undefined]
  ]) {
    const parsed = parseGitHubRepo(input)
    check(`parses ${input}`, (parsed ? `${parsed.owner}/${parsed.repo}` : undefined) === expected)
  }

  const helloCommit = fixture('hello-world-commit.json')
  const helloTree = fixture('hello-world-tree.json')
  const readme = helloTree.tree[0]
  check('blob id matches the API', (await gitOid('blob', bytes('Hello World!\n'))) === readme.sha)
  check('tree id matches the API', (await gitOid('tree', treeBody([{ mode: readme.mode, path: readme.path, sha: readme.sha }]))) === helloTree.sha)

  const rebuilt = await rebuildCommit(github.toRestCommit(helloCommit), helloCommit.sha)
  check('commit built from API JSON matches its id (author in UTC-8)', (await gitOid('commit', rebuilt)) === helloCommit.sha, helloCommit.sha.slice(0, 7))

  const signed = fixture('actions-checkout-signed-commit.json')
  const signedBody = await rebuildCommit(github.toRestCommit(signed), signed.sha)
  check('signed commit is rebuilt from the verification payload', (await gitOid('commit', signedBody)) === signed.sha && new TextDecoder().decode(signedBody).includes('gpgsig '))

  for (const c of fixture('spoon-knife-commits.json')) {
    const body = await rebuildCommit(github.toRestCommit(c), c.sha)
    check(`commit ${c.sha.slice(0, 7)} with separate author and committer dates matches its id`, (await gitOid('commit', body)) === c.sha)
  }

  // Local commits survive the trip through the push payload
  const dir = mkdtempSync(join(tmpdir(), 'frc-git-payload-'))
  await git.init({ fs, dir, defaultBranch: 'main' })
  writeFileSync(join(dir, 'a.txt'), 'a')
  await git.add({ fs, dir, filepath: 'a.txt' })
  let parent = '0'.repeat(40)
  for (const tz of [-300, 0, 60, 330, -570, 765, -720]) {
    const person = { name: 'Dev <x>', email: 'dev@example.com', timestamp: 1_700_000_000 + tz, timezoneOffset: tz }
    const oid = await git.commit({ fs, dir, message: 'Fix the arm\n\nLonger text', author: person, committer: { ...person, timestamp: person.timestamp + 5 } })
    const { commit } = await git.readCommit({ fs, dir, oid })
    const payload = commitPayload(commit, parent)
    const rebuiltLocal = commitBody({ ...payload, parents: commit.parent })
    check(`push payload for UTC offset ${-tz} min rebuilds the same commit`, (await gitOid('commit', bytes(rebuiltLocal))) === oid, payload.author.date)
    parent = oid
  }
  rmSync(dir, { recursive: true, force: true })

  const rows = [
    ['new.txt', 0, 2, 0], ['added.txt', 0, 2, 2], ['added-edited.txt', 0, 2, 3], ['same.txt', 1, 1, 1], ['edited.txt', 1, 2, 1],
    ['staged-edit.txt', 1, 2, 2], ['both.txt', 1, 2, 3], ['gone.txt', 1, 0, 1], ['staged-gone.txt', 1, 0, 0], ['untracked-again.txt', 1, 1, 0]
  ]
  const { staged, changes } = classify(rows)
  const show = (list) => list.map((c) => `${c.kind}:${c.path}`).sort().join(' ')
  check('status matrix staged group', show(staged) === 'A:added-edited.txt A:added.txt D:staged-gone.txt D:untracked-again.txt M:both.txt M:staged-edit.txt', show(staged))
  check('status matrix changes group', show(changes) === 'D:gone.txt M:added-edited.txt M:both.txt M:edited.txt U:new.txt U:untracked-again.txt', show(changes))
}

async function fakeGitHubTests() {
  const dir = mkdtempSync(join(tmpdir(), 'frc-git-fake-'))
  const hub = await createFakeGitHub(dir)
  const ref = { owner: hub.owner, repo: hub.repo }
  const progress = { report() {}, isCancelled: () => false }
  const client = new github.GitHubClient(() => undefined)
  const ctx = { git, fs, dir: join(dir, 'client'), executable: new Set() }
  const read = (path) => readFileSync(join(ctx.dir, path), 'utf8')
  const clientHead = () => git.resolveRef({ fs, dir: ctx.dir, ref: 'HEAD' })
  const now = Math.floor(Date.now() / 1000)

  try {
    const initial = await hub.commit({ 'README.md': '# Robot\n', 'gradlew': { content: '#!/bin/sh\n', mode: 0o755 }, 'src/main/java/Robot.java': 'class Robot {}\n', 'vendordeps/a.json': '{}\n' }, 'Initial robot', { timezoneOffset: -480, timestamp: now - 300 })
    await git.branch({ fs, dir: hub.server, ref: 'offseason', object: initial })
    await hub.commit({ 'src/main/java/Robot.java': 'class Robot { int x; }\n', 'src/main/java/Arm.java': 'class Arm {}\n' }, 'Add arm\n', { timezoneOffset: -330, timestamp: now - 200 })

    await github.cloneFromGitHub(ctx, client, ref, progress)
    check('clone: HEAD matches the remote head', (await clientHead()) === (await hub.head()))
    check('clone: files are checked out', read('src/main/java/Arm.java') === 'class Arm {}\n' && read('README.md') === '# Robot\n')
    const status = await git.statusMatrix({ fs, dir: ctx.dir })
    check('clone: working tree is clean', status.every((row) => row[1] === 1 && row[2] === 1 && row[3] === 1), JSON.stringify(status.filter((r) => r[1] !== 1 || r[2] !== 1 || r[3] !== 1)))
    check('clone: shallow file marks the head', readFileSync(join(ctx.dir, '.git/shallow'), 'utf8').trim() === (await hub.head()))
    check('clone: executable bit is kept', (statSync(join(ctx.dir, 'gradlew')).mode & 0o111) !== 0 && ctx.executable.has(join(ctx.dir, 'gradlew')))
    const log = await git.log({ fs, dir: ctx.dir })
    check('clone: log has the head commit only', log.length === 1 && log[0].commit.message.startsWith('Add arm'))
    check('clone: 3 API requests', hub.apiRequests() === 3, hub.requests.filter((r) => !r.startsWith('RAW ')).join(', '))
    check('clone: files come from raw.githubusercontent.com', hub.requests.filter((r) => r.startsWith('RAW ')).length === 5)

    const branchCtx = { ...ctx, dir: join(dir, 'branch'), executable: new Set() }
    await github.cloneFromGitHub(branchCtx, client, ref, progress, 'offseason')
    const branchHead = await git.resolveRef({ fs, dir: branchCtx.dir, ref: 'HEAD' })
    check('clone -b: checks out the branch', (await git.currentBranch({ fs, dir: branchCtx.dir })) === 'offseason' && branchHead === initial)
    check('clone -b: has the branch files', !existsSync(join(branchCtx.dir, 'src/main/java/Arm.java')))
    check('clone -b: tracks the remote branch', (await git.getConfig({ fs, dir: branchCtx.dir, path: 'branch.offseason.merge' })) === 'refs/heads/offseason')
    const missing = await github.cloneFromGitHub({ ...ctx, dir: join(dir, 'missing'), executable: new Set() }, client, ref, progress, 'nope').then(() => '', (e) => e.message)
    check('clone -b: a missing branch fails like git', missing === 'Remote branch nope not found in upstream origin', missing)

    // Two local commits, one with a new directory
    writeFileSync(join(ctx.dir, 'src/main/java/Robot.java'), 'class Robot { int y; }\n')
    mkdirSync(join(ctx.dir, 'src/main/deploy'), { recursive: true })
    writeFileSync(join(ctx.dir, 'src/main/deploy/auto.json'), '{"a":1}\n')
    await git.add({ fs, dir: ctx.dir, filepath: 'src/main/java/Robot.java' })
    await git.add({ fs, dir: ctx.dir, filepath: 'src/main/deploy/auto.json' })
    const person = { name: 'Local Dev', email: 'dev@example.com', timezoneOffset: 300 }
    await git.commit({ fs, dir: ctx.dir, message: 'Tune robot', author: person })
    writeFileSync(join(ctx.dir, 'README.md'), '# Robot\nMore text\n')
    await git.add({ fs, dir: ctx.dir, filepath: 'README.md' })
    await git.commit({ fs, dir: ctx.dir, message: 'Update readme\n\nWith a body', author: { ...person, timezoneOffset: -540 } })

    hub.requests.length = 0
    const pushMessage = await github.pushToGitHub(ctx, client, ref, progress)
    check('push: remote head equals the local head', (await hub.head()) === (await clientHead()), pushMessage)
    check('push: tracking ref follows', (await git.resolveRef({ fs, dir: ctx.dir, ref: 'refs/remotes/origin/main' })) === (await clientHead()))
    console.log(`     push API requests: ${hub.requests.join(', ')}`)
    check('push again is a no-op', (await github.pushToGitHub(ctx, client, ref, progress)) === 'Everything is up to date')

    // Someone else pushes, and we fast-forward
    await hub.commit({ 'docs/notes.md': 'notes\n', 'vendordeps/a.json': '{"v":2}\n' }, 'Remote change', { timezoneOffset: 0, timestamp: now })
    hub.requests.length = 0
    const pullMessage = await github.pullFromGitHub(ctx, client, ref, progress)
    check('pull: local head equals the remote head', (await clientHead()) === (await hub.head()), pullMessage)
    check('pull: new files are checked out', read('docs/notes.md') === 'notes\n' && read('vendordeps/a.json') === '{"v":2}\n')
    check('pull: only changed files are downloaded', hub.requests.filter((r) => r.startsWith('RAW ')).length === 2 && hub.apiRequests() === 2, hub.requests.join(', '))
    console.log(`     pull API requests: ${hub.requests.filter((r) => !r.startsWith('RAW ')).join(', ')}`)
    check('pull again is a no-op', (await github.pullFromGitHub(ctx, client, ref, progress)) === 'Already up to date')

    // Local edits that the pull would overwrite are protected
    await hub.commit({ 'README.md': '# Robot remote edit\n' }, 'Readme from remote', { timestamp: now + 10 })
    writeFileSync(join(ctx.dir, 'README.md'), 'dirty local edit\n')
    const before = await clientHead()
    const blocked = await github.pullFromGitHub(ctx, client, ref, progress).then(() => undefined, (error) => error.message)
    check('pull: refuses to overwrite local changes', !!blocked && (await clientHead()) === before && read('README.md') === 'dirty local edit\n', blocked)
    await git.checkout({ fs, dir: ctx.dir, ref: 'main', filepaths: ['README.md'], force: true })
    await github.pullFromGitHub(ctx, client, ref, progress)

    // Diverged branches
    writeFileSync(join(ctx.dir, 'local.txt'), 'x')
    await git.add({ fs, dir: ctx.dir, filepath: 'local.txt' })
    await git.commit({ fs, dir: ctx.dir, message: 'Local only', author: person })
    await hub.commit({ 'remote.txt': 'y' }, 'Remote only', { timestamp: now + 20 })
    const pushError = await github.pushToGitHub(ctx, client, ref, progress).then(() => undefined, (error) => error.message)
    check('push: refuses when GitHub has new commits', /Pull first/.test(pushError ?? ''), pushError)
    const pullError = await github.pullFromGitHub(ctx, client, ref, progress).then(() => undefined, (error) => error.message)
    check('pull: refuses a non fast-forward', /fast-forward/.test(pullError ?? ''), pullError)

    hub.setRateLimited(true)
    const limited = await client.json('/repos/test/robot').then(() => undefined, (error) => error)
    check('rate limit is reported clearly', limited?.kind === 'rate-limit' && /rate limit/i.test(limited.message) && /60 per hour/.test(limited.message), limited?.message)
  } finally {
    hub.close()
    rmSync(dir, { recursive: true, force: true })
  }
}

try {
  if (!process.env.GIT_TEST_SECTION) {
    await unitTests()
    await fakeGitHubTests()
  }
  if (!process.env.GIT_TEST_SKIP_BROWSER) await browserTests({ root, check })
} catch (error) {
  failures.push('exception')
  console.error(error)
}

await vite.close()
if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
