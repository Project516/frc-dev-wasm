// clone, fetch, pull, ls-remote and push with the WebAssembly build over HTTP. Requests go to desktop
// git's http-backend, run as a CGI program for each request, so no server or network is needed.
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { runGit } from './runner.mjs'

const base = fs.mkdtempSync(path.join(tmpdir(), 'git-wasm-net-'))
const env = {
  LC_ALL: 'C',
  GIT_CONFIG_NOSYSTEM: '1',
  GIT_CONFIG_GLOBAL: '/dev/null',
  GIT_TEMPLATE_DIR: '',
  GIT_AUTHOR_NAME: 'Dev',
  GIT_AUTHOR_EMAIL: 'dev@example.com',
  GIT_COMMITTER_NAME: 'Dev',
  GIT_COMMITTER_EMAIL: 'dev@example.com',
  GIT_AUTHOR_DATE: '2026-01-01T12:00:00+0000',
  GIT_COMMITTER_DATE: '2026-01-01T12:00:00+0000'
}
const desktop = (cwd, ...args) => {
  const r = spawnSync('git', args, { cwd, env: { ...env, PATH: process.env.PATH, HOME: base }, encoding: 'utf8' })
  if (r.status !== 0) throw new Error(`git ${args.join(' ')}: ${r.stderr}`)
  return r.stdout
}

const failed = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok || !detail ? '' : `: ${detail}`}`)
  if (!ok) failed.push(name)
}

// The remote: a few commits on main and a tag
const src = path.join(base, 'src')
fs.mkdirSync(src)
desktop(src, 'init', '-q', '-b', 'main')
const commit = (message) => {
  fs.appendFileSync(path.join(src, 'f.txt'), `${message}\n`)
  desktop(src, 'add', '.')
  desktop(src, 'commit', '-qm', message)
}
for (let i = 1; i <= 3; i++) commit(`commit ${i}`)
desktop(src, 'tag', 'v1')
const srv = path.join(base, 'srv')
fs.mkdirSync(srv)
desktop(base, 'clone', '-q', '--bare', src, path.join(srv, 'remote.git'))
const push = () => desktop(src, 'push', '-q', path.join(srv, 'remote.git'), 'main', 'feature')

const URL_BASE = 'https://git.example/'
function frcHttp(method, url, headers, body) {
  const target = new URL(url)
  const r = spawnSync('git', ['http-backend'], {
    input: body ?? Buffer.alloc(0),
    env: {
      ...env,
      PATH: process.env.PATH,
      GIT_PROJECT_ROOT: srv,
      GIT_HTTP_EXPORT_ALL: '1',
      REQUEST_METHOD: method,
      PATH_INFO: target.pathname,
      QUERY_STRING: target.search.slice(1),
      CONTENT_TYPE: headers['Content-Type'] ?? '',
      CONTENT_LENGTH: String(body?.length ?? 0),
      GIT_PROTOCOL: headers['Git-Protocol'] ?? '',
      // http-backend accepts pushes from authenticated users only
      REMOTE_USER: 'dev'
    }
  })
  const end = r.stdout.indexOf('\r\n\r\n')
  const head = r.stdout.subarray(0, end).toString('latin1')
  const status = Number(/^Status: (\d+)/m.exec(head)?.[1] ?? 200)
  return { status, body: new Uint8Array(r.stdout.subarray(end + 4)) }
}
const moduleOptions = { frcHttp }

const work = path.join(base, 'work')
fs.mkdirSync(work)
const wasm = (args, cwd = '') => runGit(work, args, { env: { ...env, HOME: '/home' }, cwd, moduleOptions })

let r = wasm(['clone', `${URL_BASE}remote.git`, 'clone'])
check('clone', r.status === 0 && r.stderr.toString() === "Cloning into 'clone'...\n", r.stderr.toString())
check('the clone has the remote history', wasm(['log', '--oneline'], 'clone').stdout.toString() === desktop(src, 'log', '--oneline'))
check('the clone checks out the files', fs.readFileSync(path.join(work, 'clone', 'f.txt'), 'utf8') === 'commit 1\ncommit 2\ncommit 3\n')
check('the clone has the tag', wasm(['tag'], 'clone').stdout.toString() === 'v1\n')

// clone --recursive runs git submodule, a shell script on the desktop
r = wasm(['clone', '--recursive', `${URL_BASE}remote.git`, 'recursive'])
check('clone --recursive without submodules', r.status === 0, r.stderr.toString())
const sup = path.join(base, 'super')
fs.mkdirSync(sup)
desktop(sup, 'init', '-q', '-b', 'main')
fs.writeFileSync(path.join(sup, '.gitmodules'), `[submodule "lib"]\n\tpath = lib\n\turl = ${URL_BASE}remote.git\n`)
desktop(sup, 'update-index', '--add', '--cacheinfo', `160000,${desktop(src, 'rev-parse', 'HEAD').trim()},lib`)
desktop(sup, 'add', '.gitmodules')
desktop(sup, 'commit', '-qm', 'superproject')
desktop(base, 'clone', '-q', '--bare', sup, path.join(srv, 'super.git'))
r = wasm(['clone', '--recursive', `${URL_BASE}super.git`, 'super'])
check('clone --recursive checks out a submodule', r.status === 0 && fs.readFileSync(path.join(work, 'super', 'lib', 'f.txt'), 'utf8') === 'commit 1\ncommit 2\ncommit 3\n', r.stderr.toString())
check('the submodule keeps its repository in the superproject', fs.readFileSync(path.join(work, 'super', 'lib', '.git'), 'utf8') === 'gitdir: ../.git/modules/lib\n')

commit('commit 4')
desktop(src, 'switch', '-qc', 'feature')
commit('feature work')
desktop(src, 'switch', '-q', 'main')
push()
r = wasm(['fetch'], 'clone')
check('fetch', r.status === 0, r.stderr.toString())
check('fetch brings the new branch', wasm(['branch', '-r'], 'clone').stdout.toString() === '  origin/HEAD -> origin/main\n  origin/feature\n  origin/main\n')
r = wasm(['pull'], 'clone')
check('pull', r.status === 0, r.stderr.toString())
check('pull fast-forwards main', wasm(['log', '-1', '--format=%s'], 'clone').stdout.toString() === 'commit 4\n')
r = wasm(['ls-remote', 'origin'], 'clone')
check('ls-remote lists what desktop git lists', r.stdout.toString() === desktop(base, 'ls-remote', path.join(srv, 'remote.git')), r.stdout.toString())

const bareLog = () => desktop(path.join(srv, 'remote.git'), 'log', '--format=%s', 'main')
fs.appendFileSync(path.join(work, 'clone', 'f.txt'), 'from the browser\n')
wasm(['commit', '-qam', 'pushed commit'], 'clone')
r = wasm(['push'], 'clone')
check('push', r.status === 0 && /^To https:\/\/git\.example\/remote\.git\n   [0-9a-f]+\.\.[0-9a-f]+  main -> main\n$/.test(r.stderr.toString()), r.stderr.toString())
check('the remote has the pushed commit', bareLog().startsWith('pushed commit\n'))
check('push updates the remote-tracking branch', wasm(['status', '-sb'], 'clone').stdout.toString() === '## main...origin/main\n')

desktop(src, 'pull', '-q', '--ff-only', path.join(srv, 'remote.git'), 'main')
commit('commit 5')
push()
fs.appendFileSync(path.join(work, 'clone', 'f.txt'), 'diverged\n')
wasm(['commit', '-qam', 'diverged'], 'clone')
r = wasm(['push'], 'clone')
check('a push behind the remote is rejected', r.status === 1 && /! \[rejected\]\s+main -> main \(fetch first\)/.test(r.stderr.toString()), r.stderr.toString())

wasm(['switch', '-qc', 'topic'], 'clone')
r = wasm(['push', '-u', 'origin', 'topic'], 'clone')
check('push -u creates a branch on the remote', r.status === 0 && /\* \[new branch\]\s+topic -> topic/.test(r.stderr.toString()) && desktop(path.join(srv, 'remote.git'), 'branch').includes('topic'), r.stderr.toString())
check('push -u sets the upstream', wasm(['rev-parse', '--abbrev-ref', '@{u}'], 'clone').stdout.toString() === 'origin/topic\n')

r = wasm(['clone', `${URL_BASE}missing.git`])
check('a missing repository fails as git does', r.status === 128 && /^Cloning into 'missing'\.\.\.\nfatal: remote error: repository 'https:\/\/git\.example\/missing\.git\/' not found\n$/.test(r.stderr.toString()), r.stderr.toString())
check('a failed clone leaves nothing behind', !fs.existsSync(path.join(work, 'missing')))

// Like GitHub: a token it rejects fails even a public fetch, and a push asks for credentials first
const sent = []
const github = (token) => ({
  frcAuth: () => `Basic ${token}`,
  frcHttp(method, url, headers, body) {
    sent.push({ url, auth: headers.Authorization })
    const push = url.includes('git-receive-pack')
    if (headers.Authorization ? headers.Authorization !== 'Basic good' : push) return { status: 401, body: new Uint8Array(0) }
    return frcHttp(method, url, headers, body)
  }
})
const withServer = (args, cwd, token) => runGit(work, args, { env: { ...env, HOME: '/home' }, cwd, moduleOptions: github(token) })
r = withServer(['clone', `${URL_BASE}remote.git`, 'public'], '', 'rejected')
check('a public clone works with a saved token the server rejects', r.status === 0, r.stderr.toString())
check('a public clone sends no credentials', sent.length > 0 && sent.every((s) => !s.auth))
sent.length = 0
fs.appendFileSync(path.join(work, 'public', 'f.txt'), 'with a token\n')
withServer(['commit', '-qam', 'token commit'], 'public', 'good')
r = withServer(['push', 'origin', 'HEAD:token'], 'public', 'good')
check('a push sends the token after the server asks for it', r.status === 0 && !sent[0].auth && sent.slice(1).every((s) => s.auth === 'Basic good'), r.stderr.toString())
r = withServer(['push', 'origin', 'HEAD:token2'], 'public', 'rejected')
check('a rejected token fails a push as git does', r.status !== 0 && /Authentication failed/.test(r.stderr.toString()), r.stderr.toString())

fs.rmSync(base, { recursive: true, force: true })
console.log(failed.length ? `failed: ${failed.join(', ')}` : 'all network checks passed')
process.exit(failed.length ? 1 : 0)
