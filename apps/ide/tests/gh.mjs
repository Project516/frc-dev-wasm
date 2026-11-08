// Runs gh in the FRC shell terminal against a mocked api.github.com. Needs a build of the app
// (pnpm build). GH_URL points at a running server.
import { existsSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { preview } from 'vite'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
mkdirSync(join(root, 'test-results'), { recursive: true })

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok || !detail ? '' : `: ${detail}`}`)
  if (!ok) failures.push(name)
}

if (!existsSync(join(root, 'dist/index.html')) && !process.env.GH_URL) {
  console.error('dist is missing: run pnpm build')
  process.exit(1)
}

const TOKEN = 'ghp_testtoken'
const REPO = { id: 1, name: 'robot', full_name: 'test/robot', private: false, default_branch: 'main', stargazers_count: 7 }
const ISSUES = [{ number: 7, title: 'Arm jams', state: 'open', labels: [{ name: 'bug' }], updated_at: '2026-01-01T00:00:00Z' }]
const requests = []
const bodies = []

// api.github.com allows cross-origin requests, so the mock does too
const cors = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': 'Authorization, Content-Type, Accept, X-GitHub-Api-Version, GraphQL-Features, Time-Zone',
  'access-control-expose-headers': 'Link, X-RateLimit-Remaining'
}
const json = (route, status, body) => route.fulfill({ status, headers: { ...cors, 'content-type': 'application/json' }, body: JSON.stringify(body) })

async function mockGitHub(context) {
  await context.route('https://api.github.com/**', async (route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    const { pathname } = new URL(request.url())
    const authorization = request.headers().authorization
    requests.push({ method: request.method(), pathname, authorization })
    if (request.method() === 'POST') bodies.push(request.postDataJSON())
    if (pathname === '/repos/wpilibsuite/allwpilib') return json(route, 200, { full_name: 'wpilibsuite/allwpilib', stargazers_count: 1 })
    if (pathname === '/user') {
      if (authorization !== `Bearer ${TOKEN}`) return json(route, 401, { message: 'Requires authentication' })
      return json(route, 200, { login: 'tester' })
    }
    if (pathname === '/repos/test/robot') return json(route, 200, REPO)
    if (pathname === '/repos/test/robot/readme') return route.fulfill({ status: 200, headers: { ...cors, 'content-type': 'text/plain' }, body: '# Robot\nTest code\n' })
    if (pathname === '/repos/test/robot/issues' && request.method() === 'GET') return json(route, 200, ISSUES)
    if (pathname === '/repos/test/robot/issues') return json(route, 201, { html_url: 'https://github.com/test/robot/issues/8', ...request.postDataJSON() })
    if (pathname === '/repos/test/robot/pulls') return json(route, 200, [])
    return json(route, 404, { message: 'Not Found' })
  })
  // GitHub's device sign-in, through the proxy. Without deviceSignIn the proxy has no sign-in route,
  // and the token prompt opens.
  await context.route('https://frc-proxy.project516.dev/github/**', (route) => {
    if (!deviceSignIn) return route.fulfill({ status: 404, headers: cors, body: 'Not Found' })
    const body = new URL(route.request().url()).pathname.endsWith('/device/code')
      ? { device_code: 'dc', user_code: 'ABCD-1234', verification_uri: 'https://github.com/login/device', expires_in: 900, interval: 1 }
      : { access_token: TOKEN, token_type: 'bearer', scope: 'repo' }
    return json(route, 200, body)
  })
}
let deviceSignIn = false

const server = process.env.GH_URL ? undefined : await preview({ root, preview: { port: 4176, strictPort: false, open: false } })
const url = process.env.GH_URL ?? server.resolvedUrls?.local[0] ?? 'http://localhost:4176/'
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 900 } })
await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: new URL(url).origin })
await mockGitHub(context)
const page = await context.newPage()
// The GitHub tab device sign-in opens
context.on('page', (opened) => opened.close().catch(() => {}))
const errors = []
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 300)}`))

const screen = () => page.locator('.terminal-wrapper:visible .xterm-rows').first().innerText()
const PROMPT = /frc:\S*\$\s*$/

async function runCommand(title) {
  await page.evaluate(() => (document.activeElement instanceof HTMLElement ? document.activeElement.blur() : undefined))
  await page.keyboard.press('F1')
  await page.keyboard.type(`> ${title}`)
  const row = page.locator('.quick-input-list .monaco-list-row', { hasText: title }).first()
  await row.waitFor({ timeout: 10_000 })
  await page.waitForTimeout(300)
  await row.click()
}

// Types a command and returns what it printed, once the prompt is back
async function sh(command, timeout = 90_000) {
  await page.keyboard.type(command, { delay: 4 })
  await page.keyboard.press('Enter')
  const start = Date.now()
  for (;;) {
    const text = await screen()
    const echo = text.lastIndexOf(command)
    if (echo >= 0) {
      const output = text.slice(echo + command.length)
      if (PROMPT.test(output) && output.includes('\n')) return output.replace(PROMPT, '').trim()
    }
    if (Date.now() - start > timeout) throw new Error(`no prompt after "${command}": ${JSON.stringify(text.slice(-600))}`)
    await page.waitForTimeout(150)
  }
}

async function expectOutput(name, command, expected, timeout) {
  const started = Date.now()
  const output = await sh(command, timeout)
  const ok = expected instanceof RegExp ? expected.test(output) : output === expected
  check(name, ok, `${command} -> ${JSON.stringify(output.slice(0, 600))}`)
  console.log(`     ${Date.now() - started} ms`)
  return output
}

// Waits for the workbench and opens a maximized FRC shell
async function openTerminal() {
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
  for (let attempt = 0; ; attempt++) {
    try {
      await runCommand('Terminal: Create New Terminal')
      break
    } catch (error) {
      if (attempt >= 10) throw error
      await page.keyboard.press('Escape')
      await page.waitForTimeout(1500)
    }
  }
  await page.waitForFunction(
    () => [...document.querySelectorAll('.terminal-wrapper .xterm-rows')].some((r) => r.textContent?.includes('FRC Dev shell')),
    undefined,
    { timeout: 30_000 }
  )
  await runCommand('View: Toggle Maximized Panel')
  await page.waitForTimeout(500)
  await page.locator('.terminal-wrapper:visible .xterm-rows').first().click({ force: true })
  await page.waitForFunction(() => /frc:\S*\$/.test(document.querySelector('.terminal-wrapper:not([style*="display: none"]) .xterm-rows')?.textContent ?? ''))
}

try {
  await page.goto(url)
  await openTerminal()

  await expectOutput('gh --version', 'gh --version', /^gh version/)
  await expectOutput('gh api reads a public repository without a token', 'gh api repos/wpilibsuite/allwpilib', /"full_name":\s*"wpilibsuite\/allwpilib"/)
  await expectOutput('an HTTP error sets the exit status', 'gh api repos/wpilibsuite/missing; echo status=$?', /Not Found \(HTTP 404\)[\s\S]*status=1/)
  await expectOutput('an unsupported command says so', 'gh gist list', /not supported in this terminal/)

  // Runs a command that opens the token prompt and answers it. A null answer dismisses the prompt.
  async function withPrompt(command, answer, pattern) {
    await page.keyboard.type(command, { delay: 4 })
    await page.keyboard.press('Enter')
    await page.locator('.quick-input-widget input').waitFor({ timeout: 60_000 })
    if (answer === null) await page.keyboard.press('Escape')
    else {
      await page.keyboard.type(answer)
      await page.keyboard.press('Enter')
    }
    await page.waitForFunction((p) => new RegExp(p).test(document.querySelector('.terminal-wrapper:not([style*="display: none"]) .xterm-rows')?.textContent ?? ''), pattern, { timeout: 60_000 })
    await page.locator('.terminal-wrapper:visible .xterm-rows').first().click({ force: true })
    await page.waitForFunction(() => /frc:\S*\$\s*$/.test(document.querySelector('.terminal-wrapper:not([style*="display: none"]) .xterm-rows')?.textContent?.trimEnd() ?? ''))
  }

  // A command that needs a token asks for it, stores it, and retries
  await withPrompt('gh api user', TOKEN, '"login":\\s*"tester"')
  check('gh asks for a token when a request needs one and retries with it', true)
  await expectOutput('the stored token is used without asking again', 'gh api user', '{"login":"tester"}')
  check('requests carried the token', requests.some((r) => r.pathname === '/user' && r.authorization === `Bearer ${TOKEN}`))

  await expectOutput('gh auth logout forgets the token', 'gh auth logout', /Logged out of github\.com/)
  await expectOutput('logging out twice says so', 'gh auth logout', /not logged in/)
  await withPrompt('gh api user', null, 'Requires authentication')
  check('after logout a request asks for a token again', true)

  await withPrompt('gh auth login', TOKEN, 'Token saved')
  check('without a sign-in route on the proxy, gh auth login asks for a token', true)
  await expectOutput('the token from gh auth login is used', 'gh api user', '{"login":"tester"}')
  await sh('gh auth logout')
  deviceSignIn = true
  const saved = async () => (await screen()).includes('Token saved')
  await page.keyboard.type('clear\n', { delay: 4 })
  for (const start = Date.now(); await saved(); await page.waitForTimeout(250)) if (Date.now() - start > 10_000) throw new Error('clear did not clear')
  await page.keyboard.type('gh auth login', { delay: 4 })
  await page.keyboard.press('Enter')
  await page.locator('.monaco-dialog-box', { hasText: 'ABCD-1234' }).getByRole('button', { name: 'Copy Code and Open GitHub' }).click({ timeout: 60_000 })
  for (const start = Date.now(); !(await saved()); await page.waitForTimeout(250)) if (Date.now() - start > 60_000) throw new Error('gh auth login did not finish')
  await page.locator('.terminal-wrapper:visible .xterm-rows').first().click({ force: true })
  check('the device code is copied', (await page.evaluate(() => navigator.clipboard.readText())) === 'ABCD-1234')
  await expectOutput('gh auth login signs in with the device flow', 'gh api user', '{"login":"tester"}')
  deviceSignIn = false
  await sh('gh auth logout')
  await expectOutput('gh auth login --with-token reads the token from stdin', `echo ${TOKEN} | gh auth login --with-token && gh api user`, /Token saved[\s\S]*tester/)
  await page.reload()
  await openTerminal()
  await expectOutput('the sign-in is kept across a reload', 'gh api user', '{"login":"tester"}')

  // The default repository is the origin remote
  await expectOutput('a repository for the remote', 'git init -b main /workspace/robot && cd /workspace/robot', /Initialized empty Git repository/)
  await expectOutput('the origin remote is set', 'git remote add origin https://github.com/test/robot.git && git remote -v', /origin\s+https:\/\/github\.com\/test\/robot\.git \(fetch\)/)
  await expectOutput('repo view reads the origin remote', 'gh repo view', /name:\s+test\/robot[\s\S]*Test code/)
  await expectOutput('issue list prints a row per issue', 'gh issue list', /7\s+OPEN\s+Arm jams\s+bug/)
  await expectOutput('issue create prints the new issue URL', 'gh issue create -t "Broken arm" -b "It jams"', /github\.com\/test\/robot\/issues\/8/)
  check('issue create posted the title and body', bodies.some((b) => b.title === 'Broken arm' && b.body === 'It jams'))
  await expectOutput('pr list with no pull requests prints nothing', 'gh pr list; echo status=$?', 'status=0')
} catch (error) {
  check('the test ran to the end', false, error instanceof Error ? error.message : String(error))
  await page.screenshot({ path: join(root, 'test-results/gh-failure.png') }).catch(() => undefined)
} finally {
  const relevant = errors.filter((e) => !/favicon|WebGL|Failed to load resource/i.test(e))
  check('no page errors', relevant.length === 0, relevant.join(' | '))
  await browser.close()
  await server?.close()
}

if (failures.length > 0) {
  console.log(`\n${failures.length} failed`)
  process.exit(1)
}
console.log('\nall passed')
