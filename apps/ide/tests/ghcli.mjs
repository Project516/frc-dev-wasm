// Unit tests for the terminal's gh: argument parsing and output against a stubbed fetch. No browser needed.
import { runGh } from '../src/frc/terminal/ghcli.ts'

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok || !detail ? '' : `: ${detail}`}`)
  if (!ok) failures.push(name)
}

const encoder = new TextEncoder()

// routes: 'METHOD /path?query' -> body, or res(status, body)
const res = (status, body) => ({ isResponse: true, status, body })
function setup(routes, options = {}) {
  const { branch = 'feature', stdin, askedToken } = options
  const repo = 'repo' in options ? options.repo : { owner: 'acme', repo: 'robot' }
  const token = 'token' in options ? options.token : 'tok'
  const calls = []
  const state = { token, out: '', err: '', prompts: 0, stored: undefined }
  const host = {
    async fetch(url, init) {
      const path = url.replace('https://api.github.com', '')
      calls.push({ method: init.method, path, headers: init.headers, body: init.body === undefined ? undefined : JSON.parse(init.body) })
      const route = routes[`${init.method} ${path}`]
      if (route === undefined) return new Response(JSON.stringify({ message: 'Not Found' }), { status: 404 })
      const { status = 200, body } = route?.isResponse ? route : { body: route }
      return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status })
    },
    async token(prompt) {
      if (!state.token && prompt) {
        state.prompts++
        state.token = askedToken
      }
      return state.token
    },
    async login(value) {
      state.stored = value ?? askedToken
      state.token = state.stored
      return !!state.stored
    },
    async logout() {
      const had = !!state.token
      state.token = undefined
      return had
    },
    defaultRepo: async () => repo,
    currentBranch: async () => branch,
    stdin: stdin === undefined ? undefined : encoder.encode(stdin),
    out: (t) => (state.out += t),
    err: (t) => (state.err += t)
  }
  return { host, calls, state }
}

async function gh(args, routes = {}, options = {}) {
  const s = setup(routes, options)
  const code = await runGh(args, s.host)
  return { code, ...s.state, calls: s.calls }
}

let r = await gh(['api', 'repos/acme/robot'], { 'GET /repos/acme/robot': { full_name: 'acme/robot' } })
check('api prints the response body as is', r.code === 0 && r.out === '{"full_name":"acme/robot"}', JSON.stringify(r.out))
check('api sends the token and the GitHub media type', r.calls[0].headers.Authorization === 'Bearer tok' && r.calls[0].headers.Accept === 'application/vnd.github+json')

r = await gh(['api', '/repos/acme/robot'], { 'GET /repos/acme/robot': {} }, { token: undefined })
check('api works without a token and does not ask for one', r.code === 0 && r.prompts === 0 && r.calls[0].headers.Authorization === undefined)

r = await gh(['api', 'repos/{owner}/{repo}/issues'], { 'GET /repos/acme/robot/issues': [] })
check('api fills {owner} and {repo} from the default repository', r.calls[0]?.path === '/repos/acme/robot/issues', JSON.stringify(r.calls))

r = await gh(['api', 'repos/acme/robot/issues', '-f', 'title=Hi', '-F', 'draft=true', '-F', 'n=3'], { 'POST /repos/acme/robot/issues': { number: 1 } })
check('fields make a POST with a JSON body; -F converts types', r.calls[0].method === 'POST' && JSON.stringify(r.calls[0].body) === '{"title":"Hi","draft":true,"n":3}', JSON.stringify(r.calls[0]))

r = await gh(['api', '-X', 'patch', 'repos/acme/robot/issues/1', '-f', 'state=closed'], { 'PATCH /repos/acme/robot/issues/1': {} })
check('-X sets the method', r.calls[0].method === 'PATCH' && r.calls[0].body.state === 'closed')

r = await gh(['api', 'search/issues', '--method=GET', '-f', 'q=a b'], { 'GET /search/issues?q=a%20b': {} })
check('fields on a GET become the query string', r.code === 0 && r.calls[0].path === '/search/issues?q=a%20b', JSON.stringify(r.calls))

r = await gh(['api', 'repos/acme/missing'], {})
check('an HTTP error prints the body, a message, and exits 1', r.code === 1 && r.out.includes('Not Found') && r.err === 'gh: Not Found (HTTP 404)\n', JSON.stringify(r))
check('a 404 from api does not ask for a token', r.prompts === 0)

r = await gh(['api', 'user'], { 'GET /user': res(401, { message: 'Bad credentials' }) })
check('a rejected token says how to sign in', r.code === 1 && r.err.includes('Bad credentials (HTTP 401)') && r.err.includes('gh auth login'), r.err)

r = await gh(['api'], {})
check('api without an endpoint is a usage error', r.code === 1 && /accepts 1 arg/.test(r.err), r.err)
r = await gh(['api', 'x', '--bogus'], {})
check('an unknown flag is refused', r.code === 1 && r.err.includes('unknown flag: --bogus'), r.err)

// Asking for a token
const need = { 'GET /user': res(401, { message: 'Requires authentication' }) }
r = await gh(['api', 'user'], need, { token: undefined, askedToken: undefined })
check('a 401 without a token asks for one, and gives up if it is dismissed', r.prompts === 1 && r.code === 1)
let calls = 0
const s = setup({}, { token: undefined, askedToken: 'new' })
s.host.fetch = async (url, init) => {
  calls++
  const ok = init.headers.Authorization === 'Bearer new'
  return new Response(JSON.stringify(ok ? { login: 'me' } : { message: 'Requires authentication' }), { status: ok ? 200 : 401 })
}
const code = await runGh(['api', 'user'], s.host)
check('after the prompt the request is retried with the token', code === 0 && calls === 2 && s.state.out.includes('"me"'), JSON.stringify(s.state))

r = await gh(['repo', 'view'], { 'GET /repos/acme/robot': res(404, { message: 'Not Found' }) }, { token: undefined, askedToken: undefined })
check('a 404 from repo view may be a private repository, so it asks for a token', r.prompts === 1)

// auth
r = await gh(['auth', 'login', '--with-token'], {}, { token: undefined, stdin: 'ghp_abc\n' })
check('auth login --with-token stores the token from stdin', r.code === 0 && r.stored === 'ghp_abc' && r.prompts === 0, JSON.stringify(r))
r = await gh(['auth', 'login', '--with-token'], {}, { token: undefined, stdin: '\n' })
check('auth login --with-token needs a token on stdin', r.code === 1 && r.stored === undefined)
r = await gh(['auth', 'login'], {}, { token: undefined, askedToken: 'ghp_typed' })
check('auth login stores the token from signing in', r.code === 0 && r.stored === 'ghp_typed', r.err)
r = await gh(['auth', 'login'], {}, { token: undefined, askedToken: undefined })
check('auth login fails when the prompt is dismissed', r.code === 1 && r.stored === undefined)
r = await gh(['auth', 'logout'], {}, { token: 'x' })
check('auth logout forgets the token', r.code === 0 && r.token === undefined && r.err.includes('Logged out'))
r = await gh(['auth', 'logout'], {}, { token: undefined })
check('auth logout without a token exits 1', r.code === 1)
r = await gh(['auth', 'status'], {}, { token: undefined })
check('auth status without a token exits 1', r.code === 1 && r.err.includes('not logged'))
r = await gh(['auth', 'status'], { 'GET /user': { login: 'octo' } }, { token: 'ghp_secret123' })
check('auth status names the account and masks the token', r.code === 0 && r.out.includes('account octo') && r.out.includes('ghp_***') && !r.out.includes('secret123'), r.out)

// repo
r = await gh(['repo', 'view'], { 'GET /repos/acme/robot': { full_name: 'acme/robot', description: 'Our robot' }, 'GET /repos/acme/robot/readme': '# Robot\nCode' })
check('repo view prints name, description and the README', r.out === 'name:\tacme/robot\ndescription:\tOur robot\n--\n# Robot\nCode\n', JSON.stringify(r.out))
check('repo view asks for the raw README', r.calls[1].headers.Accept === 'application/vnd.github.raw+json')
r = await gh(['repo', 'view', 'other/thing'], { 'GET /repos/other/thing': { full_name: 'other/thing', description: null } })
check('repo view takes owner/repo and copes with no README', r.out === 'name:\tother/thing\ndescription:\t\n--\n', JSON.stringify(r.out))
r = await gh(['repo', 'view'], {}, { repo: undefined })
check('without a GitHub remote it says what to do', r.code === 1 && r.err.includes('no GitHub repository found') && r.err.includes('-R'), r.err)

// issues
const issues = [
  { number: 3, title: 'Third', state: 'open', labels: [{ name: 'bug' }, { name: 'help' }], updated_at: '2026-01-03T00:00:00Z' },
  { number: 2, title: 'A pull request', state: 'open', labels: [], updated_at: '2026-01-02T00:00:00Z', pull_request: {} },
  { number: 1, title: 'First', state: 'open', labels: [], updated_at: '2026-01-01T00:00:00Z' }
]
r = await gh(['issue', 'list'], { 'GET /repos/acme/robot/issues?state=open&per_page=100': issues })
check('issue list prints tab-separated rows and skips pull requests', r.out === '3\tOPEN\tThird\tbug, help\t2026-01-03T00:00:00Z\n1\tOPEN\tFirst\t\t2026-01-01T00:00:00Z\n', JSON.stringify(r.out))
r = await gh(['issue', 'list', '-s', 'all', '-L', '1', '-R', 'x/y'], { 'GET /repos/x/y/issues?state=all&per_page=100': issues })
check('issue list takes --state, --limit and --repo', r.out.split('\n').filter(Boolean).length === 1 && r.out.startsWith('3\t'), JSON.stringify(r.out))
r = await gh(['issue', 'list', '-s', 'bogus'], {})
check('issue list refuses a bad state', r.code === 1 && r.err.includes('invalid argument'))

const issue = { number: 3, title: 'Third', state: 'open', body: 'It breaks.', user: { login: 'ann' }, labels: [{ name: 'bug' }], assignees: [{ login: 'bob' }], milestone: null, comments: 2 }
r = await gh(['issue', 'view', '#3'], { 'GET /repos/acme/robot/issues/3': issue })
check(
  'issue view prints the fields, a rule and the body',
  r.out === 'title:\tThird\nstate:\tOPEN\nauthor:\tann\nlabels:\tbug\ncomments:\t2\nassignees:\tbob\nprojects:\t\nmilestone:\t\nnumber:\t3\n--\nIt breaks.\n',
  JSON.stringify(r.out)
)
r = await gh(['issue', 'view', 'abc'], {})
check('issue view needs a number', r.code === 1 && r.err.includes('invalid issue number'))

r = await gh(['issue', 'create', '-t', 'New', '-b', 'Body'], { 'POST /repos/acme/robot/issues': { html_url: 'https://github.com/acme/robot/issues/9' } })
check('issue create posts the title and body and prints the URL', r.out === 'https://github.com/acme/robot/issues/9\n' && JSON.stringify(r.calls[0].body) === '{"title":"New","body":"Body"}', JSON.stringify(r))
r = await gh(['issue', 'create', '--title', 'New', '--body-file', '-'], { 'POST /repos/acme/robot/issues': { html_url: 'u' } }, { stdin: 'from stdin' })
check('issue create reads the body from stdin with --body-file -', r.calls[0]?.body.body === 'from stdin', JSON.stringify(r.calls))
r = await gh(['issue', 'create', '-t', 'Only a title'], {})
check('issue create needs a title and a body', r.code === 1 && r.err.includes('--title') && r.err.includes('--body'))
check('and sends nothing when they are missing', r.calls.length === 0)

// pull requests
const pulls = [
  { number: 5, title: 'Add arm', state: 'open', draft: false, merged_at: null, head: { ref: 'arm' }, created_at: '2026-02-01T00:00:00Z' },
  { number: 4, title: 'Wip', state: 'open', draft: true, merged_at: null, head: { ref: 'wip' }, created_at: '2026-01-31T00:00:00Z' },
  { number: 3, title: 'Done', state: 'closed', draft: false, merged_at: '2026-01-30T00:00:00Z', head: { ref: 'done' }, created_at: '2026-01-29T00:00:00Z' }
]
r = await gh(['pr', 'list'], { 'GET /repos/acme/robot/pulls?state=open&per_page=100': pulls })
check(
  'pr list prints number, title, branch, state and date',
  r.out === '5\tAdd arm\tarm\tOPEN\t2026-02-01T00:00:00Z\n4\tWip\twip\tDRAFT\t2026-01-31T00:00:00Z\n3\tDone\tdone\tMERGED\t2026-01-29T00:00:00Z\n',
  JSON.stringify(r.out)
)
r = await gh(['pr', 'list', '-s', 'merged'], { 'GET /repos/acme/robot/pulls?state=closed&per_page=100': pulls })
check('pr list --state merged keeps merged pull requests', r.out === '3\tDone\tdone\tMERGED\t2026-01-29T00:00:00Z\n', JSON.stringify(r.out))

const pull = { ...pulls[0], body: 'Adds the arm.', html_url: 'https://github.com/acme/robot/pull/5', user: { login: 'ann' }, labels: [], assignees: [], milestone: null, additions: 10, deletions: 2 }
r = await gh(['pr', 'view', '5'], { 'GET /repos/acme/robot/pulls/5': pull })
check(
  'pr view prints the fields and the body',
  r.out === 'title:\tAdd arm\nstate:\tOPEN\nauthor:\tann\nlabels:\t\nassignees:\t\nprojects:\t\nmilestone:\t\nnumber:\t5\nurl:\thttps://github.com/acme/robot/pull/5\nadditions:\t10\ndeletions:\t2\n--\nAdds the arm.\n',
  JSON.stringify(r.out)
)
r = await gh(['pr', 'view'], { 'GET /repos/acme/robot/pulls?state=open&head=acme%3Afeature': [pulls[0]], 'GET /repos/acme/robot/pulls/5': pull })
check('pr view without an argument finds the pull request of the current branch', r.code === 0 && r.out.startsWith('title:\tAdd arm'), JSON.stringify(r))
r = await gh(['pr', 'view', 'topic'], { 'GET /repos/acme/robot/pulls?state=open&head=acme%3Atopic': [] })
check('pr view says when a branch has no pull request', r.code === 1 && r.err.includes('no pull requests found for branch "topic"'), r.err)

r = await gh(['pr', 'create', '-t', 'T', '-b', 'B'], { 'GET /repos/acme/robot': { default_branch: 'main' }, 'POST /repos/acme/robot/pulls': { html_url: 'https://github.com/acme/robot/pull/6' } })
check(
  'pr create uses the current branch as head and the default branch as base',
  r.out === 'https://github.com/acme/robot/pull/6\n' && JSON.stringify(r.calls[1].body) === '{"title":"T","body":"B","head":"feature","base":"main","draft":false}',
  JSON.stringify(r.calls)
)
r = await gh(['pr', 'create', '-t', 'T', '-b', 'B', '-B', 'dev', '-H', 'topic', '-d'], { 'POST /repos/acme/robot/pulls': { html_url: 'u' } })
check('pr create takes --base, --head and --draft', r.calls.length === 1 && JSON.stringify(r.calls[0].body) === '{"title":"T","body":"B","head":"topic","base":"dev","draft":true}', JSON.stringify(r.calls))
r = await gh(['pr', 'create', '-t', 'T', '-b', 'B'], { 'GET /repos/acme/robot': { default_branch: 'main' }, 'POST /repos/acme/robot/pulls': res(422, { message: 'Validation Failed', errors: [{ message: 'head branch is not on GitHub' }] }) })
check('pr create shows why GitHub refused', r.code === 1 && r.err.includes('Validation Failed: head branch is not on GitHub (HTTP 422)'), r.err)

r = await gh(['pr', 'status'], {
  'GET /user': { login: 'me' },
  'GET /repos/acme/robot/pulls?state=open&head=acme%3Afeature': [pulls[0]],
  'GET /search/issues?q=repo%3Aacme%2Frobot%20is%3Apr%20is%3Aopen%20author%3Ame&per_page=30': { items: [{ number: 4, title: 'Wip' }] },
  'GET /search/issues?q=repo%3Aacme%2Frobot%20is%3Apr%20is%3Aopen%20review-requested%3Ame&per_page=30': { items: [] }
})
check(
  'pr status lists the current branch, yours and review requests',
  r.out === '\nRelevant pull requests in acme/robot\n\nCurrent branch\n  #5  Add arm [arm]\n\nCreated by you\n  #4  Wip\n\nRequesting a code review from you\n  You have no pull requests to review\n',
  JSON.stringify(r.out)
)

// everything else
r = await gh(['pr', 'merge', '5'], {})
check('an unsupported pr subcommand lists the supported ones', r.code === 1 && r.err.includes("'merge' is not supported") && r.err.includes('list, view, create, status'), r.err)
r = await gh(['gist', 'list'], {})
check('an unsupported command lists the supported ones', r.code === 1 && r.err.includes("'gist' is not supported") && r.err.includes('api, auth, repo, issue, pr'), r.err)
r = await gh([], {})
check('no arguments prints the usage and exits 1', r.code === 1 && r.out.includes('gh api'))
r = await gh(['--version'], {})
check('--version prints a version line', r.code === 0 && /^gh version/.test(r.out))
{
  const s2 = setup({})
  s2.host.fetch = async () => {
    throw new TypeError('Failed to fetch')
  }
  const code2 = await runGh(['api', 'user'], s2.host)
  check('a network failure says so and exits 1', code2 === 1 && s2.state.err.includes('error connecting to api.github.com'), s2.state.err)
}

if (failures.length > 0) {
  console.log(`\n${failures.length} failed`)
  process.exit(1)
}
console.log('\nall passed')
