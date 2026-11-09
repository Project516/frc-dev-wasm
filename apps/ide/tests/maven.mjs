// Acceptance test: clone a team project that declares maven dependencies in build.gradle (by default one that uses
// AdvantageKit's @AutoLog processor), build it, and start the simulation. It needs a build (pnpm build) and spends
// 3 of the 60 unauthenticated GitHub API requests per hour on the clone.
// MAVEN_REPO=<clone url> picks the project. MAVEN_HAR=<file> records the clone traffic and replays it afterwards.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { buildCode, runCommand as runWpilibCommand, simulate } from './wpilib.mjs'
import { preview } from 'vite'
import { quickInputReady } from './quickInput.mjs'

const REPO = process.env.MAVEN_REPO ?? 'https://github.com/hammerheads5000/2026Rebuilt'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`)
  if (!ok) failures.push(name)
}

const server = await preview({ root, preview: { port: 4176, strictPort: false, open: false } })
const url = server.resolvedUrls?.local[0]
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 850 } })
await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: new URL(url).origin })
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 300)}`))

const har = process.env.MAVEN_HAR
if (har) {
  const replay = existsSync(har)
  await page.routeFromHAR(har, {
    update: !replay,
    notFound: 'fallback',
    url: /^https:\/\/(api\.github\.com|raw\.githubusercontent\.com|codeload\.github\.com)\//
  })
  console.log(`${replay ? 'replaying' : 'recording'} ${har}`)
}

const runCommand = (title) => runWpilibCommand(page, title)

// The terminal's whole scrollback, through select all and copy
async function terminalText() {
  const tab = page.locator('.panel .composite-bar [role="tab"]', { hasText: 'Terminal' }).first()
  if (await tab.count()) await tab.click()
  await page.locator('.terminal-wrapper.active .xterm-rows').first().waitFor({ timeout: 10_000 })
  await runCommand('Terminal: Select All')
  await page.keyboard.press('Control+Shift+C')
  return page.evaluate(() => navigator.clipboard.readText())
}

const problems = () => page.locator('.markers-panel .monaco-list-row').evaluateAll((rows) => rows.map((r) => r.getAttribute('aria-label') ?? r.textContent ?? ''))

try {
  await page.goto(url)
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })

  await runCommand('Git: Clone')
  await quickInputReady(page)
  await page.keyboard.type(REPO)
  await page.keyboard.press('Enter')
  // The folder to clone into: /workspace, where its dialog starts. It can still be listing the
  // folder at the first Enter.
  const dialog = page.locator('.quick-input-widget:not([style*="display: none"])')
  await page.waitForTimeout(1500)
  for (let i = 0; i < 5 && (await dialog.count()); i++) {
    await page.keyboard.press('Enter')
    await page.waitForTimeout(800)
  }
  // The clone opens as the workspace folder, which reloads the page
  const buildGradleRow = page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'build.gradle' }).first()
  const projectRoot = page.locator('.explorer-viewlet .monaco-list-row[aria-expanded="false"]', { hasText: new RegExp(`^${REPO.split('/').pop()}$`) }).first()
  await Promise.race([buildGradleRow.waitFor({ timeout: 600_000 }), projectRoot.waitFor({ timeout: 600_000 })])
  if (!(await buildGradleRow.isVisible())) await projectRoot.click()
  await buildGradleRow.waitFor({ timeout: 60_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
  check('project clones through Git: Clone', true)

  await buildCode(page, 900_000)
  const buildLog = await terminalText()
  writeFileSync(join(results, 'maven-build.log'), buildLog)
  const built = buildLog.includes('BUILD SUCCESSFUL')
  check('project builds', built, built ? '' : `${buildLog.slice(-1500)}\nProblems: ${(await problems()).slice(0, 10).join(' | ')}`)
  check('build reports no warnings', !/warning:/.test(buildLog), buildLog.split('\n').filter((l) => /warning:/.test(l)).join(' | '))
  if (!built) throw new Error('build failed')

  await simulate(page, { gui: true })
  const frame = await (await page.waitForSelector('iframe[title="Simulation GUI"]', { timeout: 300_000 })).contentFrame()
  await frame.waitForFunction(() => Module.frcSim?.host?.status() === 'running', undefined, { timeout: 300_000 })
  await page.waitForFunction(() => (document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? '').includes('Robot program startup complete'), undefined, { timeout: 300_000 })
  const simLog = await terminalText()
  check('robot program starts', true)
  check('robot output has no class loading errors', !/UnsatisfiedLinkError|Exception in thread|NoClassDefFoundError|ClassNotFoundException/.test(simLog), simLog.split('\n').filter((l) => /Error|Exception/.test(l)).slice(0, 8).join(' | '))
  // AdvantageKit reads the HAL through its conduit buffer on every loop
  await page.waitForTimeout(10_000)
  const loopLog = await terminalText()
  const status = await frame.evaluate(() => Module.frcSim.host.status())
  check('robot keeps running its loop', status === 'running' && !/Unhandled exception|Exception in thread|quit unexpectedly/.test(loopLog), `${status} ${loopLog.split('\n').filter((l) => /xception|unexpectedly/.test(l)).slice(0, 6).join(' | ')}`)
  await runCommand('WPILib: Stop Robot Simulation')
} catch (error) {
  failures.push('exception')
  console.error(error)
  await page.screenshot({ path: join(results, 'maven-failure.png') }).catch(() => {})
  writeFileSync(join(results, 'maven-failure.log'), await terminalText().catch((e) => `(terminal text unavailable: ${e.message})`))
} finally {
  if (errors.length > 0) console.log(`browser errors (${errors.length}):\n${errors.slice(0, 15).join('\n')}`)
  await context.close().catch(() => {})
  await browser.close()
  await server.close()
}

if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
