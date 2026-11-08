// Chats with GitHub Copilot in the Chat view against a mocked api.githubcopilot.com. Needs a build of
// the app (pnpm build).
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

if (!existsSync(join(root, 'dist/index.html'))) {
  console.error('dist is missing: run pnpm build')
  process.exit(1)
}

const TOKEN = 'gho_testtoken'
const cors = { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*' }
const json = (route, status, body) => route.fulfill({ status, headers: { ...cors, 'content-type': 'application/json' }, body: JSON.stringify(body) })
const chats = []
let rejectToken = false

const model = (id, name, extra = {}) => ({
  id,
  name,
  version: id,
  capabilities: { type: 'chat', family: id, limits: { max_prompt_tokens: 64000, max_output_tokens: 4096 }, supports: { tool_calls: true } },
  ...extra
})
const MODELS = [model('gpt-4o', 'GPT-4o'), model('gpt-4o-mini', 'GPT-4o mini')]
const ENTITLEMENTS = {
  login: 'tester',
  access_type_sku: 'free_educational_quota',
  copilot_plan: 'individual',
  chat_enabled: true,
  can_signup_for_limited: false,
  organization_login_list: [],
  quota_reset_date_utc: '2026-11-01T00:00:00.000Z',
  quota_snapshots: {
    chat: { entitlement: 0, remaining: 0, percent_remaining: 100, unlimited: true, overage_permitted: false, overage_count: 0, quota_id: 'chat' },
    completions: { entitlement: 0, remaining: 0, percent_remaining: 100, unlimited: true, overage_permitted: false, overage_count: 0, quota_id: 'completions' },
    premium_interactions: { entitlement: 300, remaining: 150, percent_remaining: 50, unlimited: false, overage_permitted: false, overage_count: 0, quota_id: 'premium_interactions' }
  }
}

const server = await preview({ root, preview: { port: 4177, strictPort: false, open: false } })
const url = server.resolvedUrls?.local[0] ?? 'http://localhost:4177/'
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 900 } })
// Without a sign-in route on the proxy, signing in asks for a token
await context.route('https://frc-proxy.project516.dev/github/**', (route) => route.fulfill({ status: 404, headers: cors, body: 'Not Found' }))
await context.route('https://api.github.com/**', (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
  if (request.headers().authorization !== `token ${TOKEN}`) return json(route, 401, { message: 'Bad credentials' })
  const { pathname } = new URL(request.url())
  if (pathname === '/copilot_internal/user') return json(route, 200, ENTITLEMENTS)
  if (pathname === '/user') return json(route, 200, { login: 'tester' })
  return json(route, 404, { message: 'Not Found' })
})
await context.route('https://api.githubcopilot.com/**', (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
  if (rejectToken || request.headers().authorization !== `Bearer ${TOKEN}`) return json(route, 401, { message: 'Bad credentials' })
  const { pathname } = new URL(request.url())
  if (pathname === '/models') return json(route, 200, { data: MODELS })
  const body = request.postDataJSON()
  chats.push({ headers: request.headers(), body })
  const event = (delta) => `data: ${JSON.stringify({ choices: [{ index: 0, delta }] })}\n\n`
  const call = (name, args) => event({ tool_calls: [{ index: 0, id: `call_${chats.length}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] })
  const last = body.messages.at(-1)
  let reply
  if (last.role === 'user' && last.content.startsWith('Create')) reply = event({ content: 'Creating it.' }) + call('create_file', { filePath: 'src/Hello.java', content: 'class Hello {}\n' })
  else if (last.role === 'user' && last.content.startsWith('Look up')) reply = call('wpilib_api', { name: 'TimedRobot.addPeriodic' })
  else if (last.role === 'tool' && last.content.includes('Adds a periodic callback')) reply = call('wpilib_docs', { query: 'command scheduler' })
  else if (last.role === 'tool' && last.content.includes('The Command Scheduler')) reply = event({ content: 'Looked up.' })
  else if (last.role === 'tool' && last.content.startsWith('Created')) reply = call('run_in_terminal', { command: 'ls src', explanation: 'List the sources' })
  else if (last.role === 'tool') reply = event({ content: 'Done.' })
  else reply = event({ role: 'assistant', content: 'Extend ' }) + event({ content: '`TimedRobot`.' })
  return route.fulfill({ status: 200, headers: { ...cors, 'content-type': 'text/event-stream' }, body: reply + 'data: [DONE]\n\n' })
})
// The WPILib javadoc and docs, as small files
const JAVADOC = 'https://github.wpilib.org/allwpilib/docs/beta/java/'
const DOCS = 'https://docs.wpilib.org/en/latest/'
const SITE = {
  [`${JAVADOC}type-search-index.js`]: 'typeSearchIndex = [{"p":"org.wpilib.framework","l":"TimedRobot"}];updateSearchResults();',
  [`${JAVADOC}package-search-index.js`]: 'packageSearchIndex = [{"m":"wpilib.core","l":"org.wpilib.framework"}];updateSearchResults();',
  [`${JAVADOC}member-search-index.js`]: 'memberSearchIndex = [{"p":"org.wpilib.framework","c":"TimedRobot","l":"addPeriodic(Runnable, double)","u":"addPeriodic(java.lang.Runnable,double)"}];updateSearchResults();',
  [`${JAVADOC}wpilib.core/org/wpilib/framework/TimedRobot.html`]:
    '<html><body><nav>Skip</nav><main role="main"><h1>Class TimedRobot</h1><section class="detail" id="addPeriodic(java.lang.Runnable,double)"><h3>addPeriodic</h3><p>Adds a periodic callback.</p></section></main></body></html>',
  [`${DOCS}searchindex.js`]:
    'Search.setIndex({"docnames":["docs/scheduler"],"titles":["The Command Scheduler"],"alltitles":{"The Command Scheduler":[[0,null]]},"terms":{"command":0},"titleterms":{"command":0,"schedul":0}})'
}
await context.route(/^https:\/\/(github|docs)\.wpilib\.org\//, (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
  const body = SITE[request.url()]
  return route.fulfill({ status: body ? 200 : 404, headers: { ...cors, 'content-type': 'text/html' }, body: body ?? 'Not Found' })
})
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 300)}`))

async function runCommand(title) {
  await page.evaluate(() => (document.activeElement instanceof HTMLElement ? document.activeElement.blur() : undefined))
  await page.keyboard.press('F1')
  await page.keyboard.type(`> ${title}`)
  const row = page.locator('.quick-input-list .monaco-list-row', { hasText: title }).first()
  await row.waitFor({ timeout: 10_000 })
  await page.waitForTimeout(300)
  await row.click()
}

const status = page.locator('.statusbar-item', { has: page.locator('.codicon-copilot') }).first()
const responses = page.locator('.interactive-response .rendered-markdown')

async function ask(question) {
  await page.locator('.interactive-input-part .monaco-editor').first().click()
  await page.keyboard.type(question)
  await page.keyboard.press('Enter')
}

try {
  await page.goto(url)
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })

  await status.waitFor({ timeout: 30_000 })
  check('chat is off until turned on', (await page.locator('.statusbar-item', { hasText: 'Sign In' }).count()) === 0)
  await status.click()
  await page.locator('.monaco-dialog-box').getByRole('button', { name: 'Turn On' }).click({ timeout: 30_000 })
  await page.waitForEvent('load', { timeout: 60_000 })
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'Sign In' }).first().waitFor({ timeout: 60_000 })
  check('signed out, the Copilot status offers to sign in', true)
  await status.click()
  await page.locator('.quick-input-widget input').waitFor({ timeout: 30_000 })
  check("VS Code's Copilot setup is replaced by the GitHub sign-in", (await page.locator('.monaco-dialog-box', { hasText: 'Continue with Google' }).count()) === 0)
  await page.keyboard.type(TOKEN)
  await page.keyboard.press('Enter')
  await page.waitForFunction(() => {
    const item = [...document.querySelectorAll('.statusbar-item')].find((e) => e.querySelector('.codicon-copilot'))
    return item && !item.textContent?.includes('Sign In')
  }, undefined, { timeout: 30_000 })
  check('signed in, the Copilot status shows the account', true)

  await status.click()
  const dashboard = page.locator('.chat-status-bar-entry-tooltip')
  await dashboard.waitFor({ timeout: 10_000 })
  const usage = await dashboard.innerText()
  check('the status shows premium request usage', /Premium requests/i.test(usage) && /50%/.test(usage), usage.slice(0, 300))
  await page.screenshot({ path: join(root, 'test-results/copilot-status.png') })
  await page.keyboard.press('Escape')

  await runCommand('Chat: Open Chat (Ask)')
  const picker = page.locator('.interactive-input-part .action-label:visible', { hasText: 'GPT-4o' }).first()
  await picker.waitFor({ timeout: 30_000 })
  // Which models it lists is covered by tests/copilotApi.mjs
  check('the model picker shows a Copilot model', true)

  await ask('How do I start a robot program?')
  await responses.filter({ hasText: 'Extend TimedRobot.' }).first().waitFor({ timeout: 30_000 })
  check('the answer streams into the Chat view', true)
  const first = chats[0]
  check('the request carries the GitHub token', first?.headers.authorization === `Bearer ${TOKEN}`)
  check('the request uses the picked model', first?.body.model === 'gpt-4o', first?.body.model)
  check('the request starts with the FRC instructions', first?.body.messages[0].role === 'system' && /WPILib/.test(first.body.messages[0].content))
  check('the question is the last message', first?.body.messages.at(-1).content === 'How do I start a robot program?')
  const askTools = first?.body.tools?.map((t) => t.function.name).sort().join(',')
  check('Ask mode offers only the tools that read', askTools === 'file_search,get_errors,grep_search,list_dir,read_file,wpilib_api,wpilib_docs', askTools)
  check('without a robot project the instructions name no WPILib season', !/WPILib 20\d\d/.test(first?.body.messages[0].content ?? ''), first?.body.messages[0].content)

  // The open folder becomes a 2027 project, so the next request names that season
  await page.evaluate(async () => {
    const dir = await (await (await navigator.storage.getDirectory()).getDirectoryHandle('frc-dev-wasm-files')).getDirectoryHandle('workspace')
    const writable = await (await dir.getFileHandle('build.gradle', { create: true })).createWritable()
    await writable.write('plugins {\n    id "java"\n    id "org.wpilib.GradleRIO" version "2027.0.0-alpha-8"\n}\n')
    await writable.close()
  })
  await ask('And the next step?')
  await page.waitForFunction(() => document.querySelectorAll('.interactive-response').length >= 2, undefined, { timeout: 30_000 })
  await page.waitForTimeout(1000)
  const history = chats[1]?.body.messages.map((m) => `${m.role}:${m.content}`) ?? []
  check('a follow-up sends the earlier turn', history.includes('user:How do I start a robot program?') && history.includes('assistant:Extend `TimedRobot`.'), JSON.stringify(history))
  const system = chats[1]?.body.messages[0].content ?? ''
  check("the instructions name the open project's season and its packages", /WPILib 2027/.test(system) && /org\.wpilib/.test(system), system)

  const beforeLookup = chats.length
  await ask('Look up TimedRobot.addPeriodic and the command scheduler')
  await responses.filter({ hasText: 'Looked up.' }).first().waitFor({ timeout: 60_000 })
  const lookups = chats.slice(beforeLookup).map((c) => c.body.messages.at(-1)).filter((m) => m.role === 'tool').map((m) => m.content)
  check('the javadoc tool returns the member of the 2027 class page', /^org\.wpilib\.framework\.TimedRobot \(WPILib 2027 \(alpha\) javadoc, .*wpilib\.core\/[\s\S]*Adds a periodic callback/.test(lookups[0] ?? ''), lookups[0])
  check('the docs tool returns matching pages of the 2027 docs', /The Command Scheduler - https:\/\/docs\.wpilib\.org\/en\/latest\/docs\/scheduler\.html/.test(lookups[1] ?? ''), lookups[1])

  await runCommand('Chat: Open Chat (Agent)')
  await page.waitForTimeout(1000)
  const before = chats.length
  await ask('Create a Hello class and list the sources')
  await page.getByRole('button', { name: /^Allow/ }).first().click({ timeout: 60_000 })
  await responses.filter({ hasText: 'Done.' }).first().waitFor({ timeout: 60_000 })
  const agent = chats.slice(before)
  const names = agent[0]?.body.tools?.map((t) => t.function.name) ?? []
  check('Agent mode offers the file and terminal tools', ['read_file', 'create_file', 'replace_string_in_file', 'delete_file', 'run_in_terminal'].every((n) => names.includes(n)), names.join(','))
  const results = agent.map((c) => c.body.messages.at(-1)).filter((m) => m.role === 'tool').map((m) => m.content)
  check('the agent creates a file', results[0] === 'Created src/Hello.java.', results[0])
  check('the agent runs a command after it is allowed', /^Exit code: 0\n[\s\S]*Hello\.java/.test(results[1] ?? ''), results[1])
  check('tool results do not count as user requests', agent.slice(1).every((c) => c.headers['x-initiator'] === 'agent') && agent[0].headers['x-initiator'] === 'user')
  check('the command shows in the Copilot terminal', (await page.locator('.terminal-tab, .single-terminal-tab, .terminal-tabs-entry', { hasText: 'Copilot' }).count()) > 0 || (await page.locator('.xterm-rows', { hasText: 'Hello.java' }).count()) > 0)
  await page.screenshot({ path: join(root, 'test-results/copilot-agent.png') })

  rejectToken = true
  await ask('Are you there?')
  const signIn = page.locator('.interactive-response .monaco-button', { hasText: 'Sign in to GitHub' }).last()
  await signIn.waitFor({ timeout: 30_000 })
  check('a rejected token offers to sign in again', true)
  await page.screenshot({ path: join(root, 'test-results/copilot-chat.png') })
} catch (error) {
  check('the test ran to the end', false, error instanceof Error ? error.message : String(error))
  await page.screenshot({ path: join(root, 'test-results/copilot-failure.png') }).catch(() => undefined)
} finally {
  const relevant = errors.filter((e) => !/favicon|WebGL|Failed to load resource/i.test(e))
  check('no page errors', relevant.length === 0, relevant.join(' | '))
  await browser.close()
  await server.close()
}

if (failures.length > 0) {
  console.log(`\n${failures.length} failed`)
  process.exit(1)
}
console.log('\nall passed')
