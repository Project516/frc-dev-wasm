// Creates a project of each season from the Timed Robot template, adds a passing and a failing JUnit test, and
// runs `./gradlew test` in the FRC Shell and Test Robot Code from the command palette: Gradle's console output and
// the exit status. It needs a build (pnpm build) and the runtimes of the seasons (fetch-deps, build:java,
// build:vscode-wpilib, and build:java-web for 2027). YEARS=2026 limits it to one season.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { JAVA_WEB_YEARS, WPILIB_YEARS } from '../../../packages/runtime/src/years.ts'
import { createProject, runCommand, waitForTerminal } from './wpilib.mjs'
import { preview } from 'vite'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

const years = (process.env.YEARS?.split(',') ?? WPILIB_YEARS).filter((year) => WPILIB_YEARS.includes(year))
const needed = (year) => [
  `runtime/${year}/manifest.json`,
  `runtime/${year}/frcdevwasm-junit.jar`,
  `runtime/${year}/vscode-wpilib/dist/browser/extension.js`,
  ...(JAVA_WEB_YEARS.includes(year) ? ['runtime/java-web/java-web.js', 'runtime/java-web/boot.pack'] : [])
]
const missing = years.flatMap((year) => needed(year).filter((file) => !existsSync(join(root, 'dist', file))))
if (missing.length > 0) {
  console.log(`skipped: apps/ide/dist lacks ${missing.join(', ')}; run the builds in the header of this file, then pnpm build`)
  process.exit(0)
}

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail && !ok ? `: ${detail}` : ''}`)
  if (!ok) failures.push(name)
}
const seconds = (since) => `${((Date.now() - since) / 1000).toFixed(1)} s`

const server = await preview({ root, preview: { port: 4177, strictPort: false, open: false } })
const url = server.resolvedUrls?.local[0]
const browser = await chromium.launch({ headless: true })
const errors = []
let context
let page

// A season starts in a fresh browser profile, as a new user would: another season's creator reloads the page into
// that season, which an open project of this one would stop
async function openPage() {
  await context?.close()
  context = await browser.newContext({ viewport: { width: 1400, height: 850 } })
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: new URL(url).origin })
  page = await context.newPage()
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 300)}`))
  await page.goto(url)
  await waitForWorkbench()
}

async function waitForWorkbench() {
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
}

// The terminal on show, with its whole scrollback, through select all and copy
async function terminalText() {
  await page.locator('.terminal-wrapper.active .xterm-rows').first().waitFor({ timeout: 10_000 })
  await runCommand(page, 'Terminal: Select All')
  await page.keyboard.press('Control+Shift+C')
  return page.evaluate(() => navigator.clipboard.readText())
}

// Types a command into a new FRC Shell and waits for `status=<exit code>` that the command ends with
async function shell(command, timeout = 60_000) {
  await page.evaluate(() => document.querySelectorAll('.terminal-wrapper').forEach((wrapper) => (wrapper.dataset.earlier = 'true')))
  await runCommand(page, 'Terminal: Create New Terminal')
  await page.waitForFunction(
    () => /FRC Dev shell[\s\S]*frc:\S*\$/.test(document.querySelector('.terminal-wrapper.active:not([data-earlier]) .xterm-rows')?.textContent ?? ''),
    undefined,
    { timeout: 60_000 }
  )
  await page.locator('.terminal-wrapper:visible .xterm-rows').first().click({ force: true })
  await page.keyboard.type(`${command}; echo status=$?`, { delay: 2 })
  await page.keyboard.press('Enter')
  await waitForTerminal(page, /status=\d+/, timeout)
  const text = await terminalText()
  return { text, status: Number(/^status=(\d+)$/m.exec(text)?.[1]) }
}

const TEST = (assertion) => [
  'package frc.robot;',
  '',
  'import static org.junit.jupiter.api.Assertions.*;',
  '',
  'import org.junit.jupiter.api.Test;',
  '',
  'class ExampleTest {',
  '  @Test',
  '  void passes() {',
  '    assertEquals(4, 2 + 2);',
  '  }',
  '',
  '  @Test',
  '  void fails() {',
  `    ${assertion}`,
  '  }',
  '}'
]
const FAILING = 'assertEquals(5, 2 + 2, "two and two");'

// WPILib: Test Robot Code, until its task terminal (a new one) has finished
async function testCode(timeout) {
  await page.evaluate(() => document.querySelectorAll('.terminal-wrapper').forEach((wrapper) => (wrapper.dataset.earlier = 'true')))
  await runCommand(page, 'WPILib: Test Robot Code')
  await page.waitForFunction(
    () => {
      const wrapper = document.querySelector('.terminal-wrapper.active')
      return wrapper && !wrapper.dataset.earlier && /BUILD (SUCCESSFUL|FAILED)|terminated with exit code/.test(wrapper.querySelector('.xterm-rows')?.textContent ?? '')
    },
    undefined,
    { timeout }
  )
  return terminalText()
}

async function season(year) {
  const name = `JunitBot${year}`
  await createProject(page, { name, base: 'Timed Robot', year })
  await waitForWorkbench()
  const quoted = TEST(FAILING).map((line) => `'${line}'`).join(' ')
  const written = await shell(`mkdir -p src/test/java/frc/robot && printf '%s\\n' ${quoted} > src/test/java/frc/robot/ExampleTest.java`)
  check(`${year}: the test file is written`, written.status === 0, written.text.slice(-300))

  let started = Date.now()
  const failing = await shell('./gradlew test', 900_000)
  writeFileSync(join(results, `junit-${year}-failing.log`), failing.text)
  console.log(`time ${year}: first ./gradlew test ${seconds(started)}`)
  const text = failing.text
  check(`${year}: a passing test is reported`, /ExampleTest > passes\(\) PASSED/.test(text), text.slice(-1500))
  check(`${year}: a failing test is reported with its exception and place`, /ExampleTest > fails\(\) FAILED\n\s+org\.opentest4j\.AssertionFailedError at ExampleTest\.(java:\d+|fails)\n\s+two and two ==> expected: <5> but was: <4>/.test(text), text.slice(-1500))
  check(`${year}: the summary counts the tests`, /2 tests completed, 1 failed/.test(text))
  check(`${year}: Gradle's failure and BUILD FAILED follow`, /> Task :test FAILED[\s\S]*Execution failed for task ':test'\.\n> There were failing tests\.[\s\S]*BUILD FAILED/.test(text))
  check(`${year}: the exit status is 1`, failing.status === 1, String(failing.status))
  check(`${year}: the test run reports no JVM errors`, !/UnsatisfiedLinkError|NoClassDefFoundError|ClassNotFoundException|Exception in thread/.test(text))

  started = Date.now()
  const filtered = await shell('./gradlew test --tests ExampleTest.passes', 900_000)
  console.log(`time ${year}: filtered ./gradlew test ${seconds(started)}`)
  check(`${year}: --tests runs only the matching test`, /ExampleTest > passes\(\) PASSED/.test(filtered.text) && !/fails\(\)/.test(filtered.text) && filtered.status === 0, filtered.text.slice(-800))

  const fixed = await shell("sed -i 's/assertEquals(5,/assertEquals(4,/' src/test/java/frc/robot/ExampleTest.java")
  check(`${year}: the test is fixed`, fixed.status === 0, fixed.text.slice(-300))
  started = Date.now()
  const passing = await testCode(900_000)
  writeFileSync(join(results, `junit-${year}-passing.log`), passing)
  console.log(`time ${year}: Test Robot Code ${seconds(started)}`)
  check(`${year}: Test Robot Code runs both tests`, /ExampleTest > passes\(\) PASSED/.test(passing) && /ExampleTest > fails\(\) PASSED/.test(passing), passing.slice(-1500))
  check(`${year}: Test Robot Code ends with BUILD SUCCESSFUL`, /BUILD SUCCESSFUL/.test(passing) && !/FAILED/.test(passing), passing.slice(-600))
}

try {
  for (const year of years) {
    await openPage()
    await season(year)
  }
} catch (error) {
  failures.push('exception')
  console.error(error)
  await page.screenshot({ path: join(results, 'junit-failure.png') }).catch(() => {})
  console.log(`terminal: ${JSON.stringify((await terminalText().catch(() => '')).slice(-1500))}`)
} finally {
  if (errors.length > 0) console.log(`browser errors (${errors.length}):\n${errors.slice(0, 20).join('\n')}`)
  await context?.close().catch(() => {})
  await browser.close()
  await server.close()
}

if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
