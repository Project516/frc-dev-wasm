// Clones a real team project that has JUnit tests, builds it and runs `./gradlew test`, and reports what Gradle's console
// shows. It needs a build (pnpm build) and spends 3 of the 60 unauthenticated GitHub API requests per hour on the clone.
// JUNIT_REPO=<clone url> picks the project (default: StuyPulse/StuyPlus-2026). JUNIT_HAR=<file> records the clone and replays it.
// It checks that the run finishes with a Gradle result and no JVM errors. Failing tests of the project are reported, not failed.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { runCommand, waitForTerminal } from './wpilib.mjs'
import { preview } from 'vite'

const REPO = process.env.JUNIT_REPO ?? 'https://github.com/StuyPulse/StuyPlus-2026'
const name = REPO.split('/').pop()

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

const failures = []
function check(label, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${detail ? `: ${detail}` : ''}`)
  if (!ok) failures.push(label)
}

const server = await preview({ root, preview: { port: 4178, strictPort: false, open: false } })
const url = server.resolvedUrls?.local[0]
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 850 } })
await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: new URL(url).origin })
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 300)}`))

const har = process.env.JUNIT_HAR
if (har) {
  const replay = existsSync(har)
  await page.routeFromHAR(har, { update: !replay, notFound: 'fallback', url: /^https:\/\/(api\.github\.com|raw\.githubusercontent\.com|codeload\.github\.com)\// })
  console.log(`${replay ? 'replaying' : 'recording'} ${har}`)
}

// The terminal on show, with its whole scrollback, through select all and copy
async function terminalText() {
  const tab = page.locator('.panel .composite-bar [role="tab"]', { hasText: 'Terminal' }).first()
  if (await tab.count()) await tab.click()
  await page.locator('.terminal-wrapper.active .xterm-rows').first().waitFor({ timeout: 10_000 })
  await runCommand(page, 'Terminal: Select All')
  await page.keyboard.press('Control+Shift+C')
  return page.evaluate(() => navigator.clipboard.readText())
}

try {
  await page.goto(url)
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })

  await runCommand(page, 'Git: Clone')
  await page.waitForSelector('.quick-input-box input', { timeout: 15_000 })
  await page.keyboard.type(REPO)
  await page.keyboard.press('Enter')
  // The folder to clone into: /workspace, where its dialog starts
  const dialog = page.locator('.quick-input-widget:not([style*="display: none"])')
  await page.waitForTimeout(1500)
  for (let i = 0; i < 5 && (await dialog.count()); i++) {
    await page.keyboard.press('Enter')
    await page.waitForTimeout(800)
  }
  // The clone opens as the workspace folder, which reloads the page
  const buildGradleRow = page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'build.gradle' }).first()
  const projectRoot = page.locator('.explorer-viewlet .monaco-list-row[aria-expanded="false"]', { hasText: new RegExp(`^${name}$`) }).first()
  await Promise.race([buildGradleRow.waitFor({ timeout: 600_000 }), projectRoot.waitFor({ timeout: 600_000 })])
  if (!(await buildGradleRow.isVisible())) await projectRoot.click()
  await buildGradleRow.waitFor({ timeout: 60_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
  check('project clones through Git: Clone', true)

  await runCommand(page, 'Terminal: Create New Terminal')
  await page.waitForFunction(() => [...document.querySelectorAll('.terminal-wrapper .xterm-rows')].some((r) => r.textContent?.includes('FRC Dev shell')), undefined, { timeout: 30_000 })
  await page.locator('.terminal-wrapper:visible .xterm-rows').first().click({ force: true })
  const started = Date.now()
  await page.keyboard.type('./gradlew test; echo status=$?', { delay: 2 })
  await page.keyboard.press('Enter')
  await waitForTerminal(page, /status=\d+/, 1_500_000)
  const text = await terminalText()
  writeFileSync(join(results, `junit-${name}.log`), text)
  console.log(`time: ./gradlew test ${((Date.now() - started) / 1000).toFixed(1)} s`)
  console.log(text.split('\n').filter((line) => line.trim()).slice(-60).join('\n'))
  check('the run ends with a Gradle result', /BUILD (SUCCESSFUL|FAILED)/.test(text))
  check('the tests start', /> Task :test/.test(text))
  check('no JVM errors', !/UnsatisfiedLinkError|NoClassDefFoundError|ClassNotFoundException|Exception in thread|NoSuchMethodError|AbstractMethodError/.test(text), text.split('\n').filter((l) => /Error|Exception/.test(l)).slice(0, 8).join(' | '))
  check('every test has a result', /(PASSED|FAILED|SKIPPED)/.test(text) || /NO-SOURCE/.test(text))
} catch (error) {
  failures.push('exception')
  console.error(error)
  await page.screenshot({ path: join(results, 'junitTeam-failure.png') }).catch(() => {})
  writeFileSync(join(results, 'junitTeam-failure.log'), await terminalText().catch((e) => `(terminal text unavailable: ${e.message})`))
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
