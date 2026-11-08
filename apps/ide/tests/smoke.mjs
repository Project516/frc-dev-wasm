import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { buildCode, createProject as createWpilibProject, enableTeleoperated, quickInputState, runCommand as runWpilibCommand, simulate } from './wpilib.mjs'
import { preview } from 'vite'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`)
  if (!ok) failures.push(name)
}

// SMOKE_URL points the test at an already running server, for example the dev server
const server = process.env.SMOKE_URL
  ? undefined
  : await preview({ root, preview: { port: 4173, strictPort: false, open: false } })
const url = process.env.SMOKE_URL ?? server.resolvedUrls?.local[0] ?? 'http://localhost:4173/'
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 850 } })
// Writes as Safari does: the whole buffer behind a typed array, not just the view
await context.addInitScript(() => {
  const write = FileSystemWritableFileStream.prototype.write
  FileSystemWritableFileStream.prototype.write = function (data) {
    return write.call(this, ArrayBuffer.isView(data) ? new Uint8Array(data.buffer) : data)
  }
})
const page = await context.newPage()
// SMOKE_CPU_THROTTLE=<n> slows the page n times, as a low-end computer does, which brings out races
// between the workbench, webviews and the extension host
if (process.env.SMOKE_CPU_THROTTLE) {
  await (await context.newCDPSession(page)).send('Emulation.setCPUThrottlingRate', { rate: Number(process.env.SMOKE_CPU_THROTTLE) })
}
const errors = []
const requested = new Set()
page.on('request', (r) => requested.add(new URL(r.url()).pathname))
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
page.on('console', (m) => {
  if (m.type() === 'error') errors.push(`console: ${m.text()}`)
})

async function expectPoll(predicate, timeout = 30_000) {
  for (const start = Date.now(); Date.now() - start < timeout; await page.waitForTimeout(250)) {
    if (predicate()) return
  }
  throw new Error('condition not met in time')
}

const runCommand = (title) => runWpilibCommand(page, title)

async function waitForWorkbench() {
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
}

// Creating a project reloads the page into the new project folder
async function createProject(name) {
  await createWpilibProject(page, { name, base: 'Command Robot' })
  await waitForWorkbench()
}

async function terminalText() {
  return page.locator('.terminal-wrapper.active .xterm-rows').first().innerText()
}

try {
  await page.goto(url)
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  const status = page.locator('.statusbar-item', { hasText: 'WPILib' })
  await status.first().waitFor({ timeout: 30_000 })
  check('workbench renders with WPILib status bar item', true)

  const version = JSON.parse(readFileSync(join(root, '../../package.json'), 'utf8')).version
  let commit = 'unknown'
  try {
    commit = execFileSync('git', ['rev-parse', '--short=7', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim()
  } catch {}
  await runWpilibCommand(page, 'Help: About')
  const about = page.locator('.monaco-dialog-box')
  await about.waitFor({ timeout: 10_000 })
  const aboutText = await about.innerText()
  check('Help: About names the release and its commit', !process.env.SMOKE_URL ? aboutText.includes(`FRC Dev ${version} (${commit})`) : new RegExp(`FRC Dev ${version.replaceAll('.', '\\.')}(-nightly)? \\(`).test(aboutText), JSON.stringify(aboutText.slice(0, 200)))
  await page.keyboard.press('Escape')
  await about.waitFor({ state: 'hidden', timeout: 10_000 })

  if (existsSync(join(root, 'dist/runtime/2026/manifest.json'))) {
    await expectPoll(() => [...requested].some((p) => p.endsWith('/runtime/2026/wpilib/templates.zip')))
    check('runtime files are prefetched in the background', true)
  }

  await createProject('MyRobot')
  check('project created from the Command Robot template opens as the workspace folder', true)

  await buildCode(page, 240_000)
  let text = await terminalText()
  check('robot code builds with javac in the browser', text.includes('BUILD SUCCESSFUL'), JSON.stringify(text.slice(-300)))
  // Only the project's season downloads; the other's manifest and transform version are small reads
  check('a 2026 window downloads nothing else of 2027', ![...requested].some((p) => /\/runtime\/(java-web\/|2027\/(?!manifest\.json|vendor\/transform\.json))/.test(p)))

  await simulate(page)
  const frameHandle = await page.waitForSelector('iframe[title="Simulation GUI"]', { timeout: 60_000 })
  const gui = await frameHandle.contentFrame()
  await gui.locator('canvas#canvas').waitFor({ timeout: 120_000 })
  check('the Sim GUI (halsim_gui) opens', true)
  await gui.waitForFunction(() => Module.frcSim?.host?.status() === 'running', undefined, { timeout: 240_000 })
  check('robot program runs in simulation', true)
  await page.waitForFunction(
    () => (document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? '').includes('Robot program startup complete'),
    undefined,
    { timeout: 120_000 }
  )
  check('robot console output reaches the WPILib terminal', true)
  const state = await enableTeleoperated(page, frameHandle, gui)
  check('Teleoperated in the Sim GUI enables the robot', state.ds && state.enabled && !state.autonomous && !state.test, JSON.stringify(state))
  await gui.waitForFunction(() => JSON.parse(Module.ccall('frc_sim_state', 'string', [], [])).nt, undefined, { timeout: 30_000 })
  check('the Sim GUI connects to the robot NetworkTables', true)
  await page.waitForTimeout(3000)
  await page.screenshot({ path: join(results, 'smoke.png') })

  await runCommand('WPILib: Stop Robot Simulation')
  const closed = await page.waitForSelector('iframe[title="Simulation GUI"]', { state: 'detached', timeout: 30_000 }).then(() => true, () => false)
  check('stopping the robot closes the Sim GUI, as on the desktop', closed)

  await runCommand('WPILib: Start Tool')
  await page.waitForSelector('.quick-input-list .monaco-list-row', { timeout: 10_000 })
  await page.keyboard.type('Glass')
  await page.waitForTimeout(500)
  await page.keyboard.press('Enter')
  const glassHandle = await page.waitForSelector('iframe[title="Glass"]', { timeout: 30_000 })
  const glassFrame = await glassHandle.contentFrame()
  await glassFrame.waitForFunction(() => document.title.includes('Glass'), undefined, { timeout: 60_000 })
  await glassFrame.locator('canvas#canvas').waitFor({ timeout: 30_000 })
  check('Glass opens from WPILib: Start Tool', true, await glassFrame.title())
  await glassFrame.evaluate(() => {
    window.keptPage = true
  })
  await page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'build.gradle' }).first().click()
  await page.locator('.tabs-container .tab', { hasText: 'build.gradle' }).waitFor({ timeout: 10_000 })
  await page.locator('.tabs-container .tab', { hasText: 'Glass' }).click()
  check('a tool keeps its page while another tab is shown', await glassFrame.evaluate(() => window.keptPage === true))

  const quickInput = page.locator('.quick-input-widget:not([style*="display: none"]) .quick-input-box input')
  const focusTool = async () => {
    await glassFrame.locator('canvas#canvas').click()
    await page.waitForFunction(() => document.activeElement?.tagName === 'IFRAME')
  }
  await focusTool()
  await page.keyboard.press('F1')
  await quickInput.waitFor({ timeout: 10_000 })
  check('F1 opens the command palette while a tool has focus', await quickInput.evaluate((el) => el === document.activeElement))
  await page.keyboard.press('Escape')
  await quickInput.waitFor({ state: 'hidden', timeout: 10_000 })
  await focusTool()
  await page.keyboard.press('Control+Shift+P')
  await quickInput.waitFor({ timeout: 10_000 })
  check('Ctrl+Shift+P opens the command palette with commands listed', (await quickInput.inputValue()).startsWith('>'))
  const rowOnTop = await page.locator('.quick-input-list .monaco-list-row').nth(3).evaluate((row) => {
    const r = row.getBoundingClientRect()
    return row.contains(document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2))
  })
  check('the command palette list draws over the tool', rowOnTop)
  await page.keyboard.press('Escape')
  await quickInput.waitFor({ state: 'hidden', timeout: 10_000 })
  await focusTool()
  for (const key of ['p', 'Shift+P', 'Control+a', 'Control+c', 'Control+v', 'Control+z']) await page.keyboard.press(key)
  await page.waitForTimeout(500)
  check('typing and editing keys in a tool stay in the tool', !(await quickInput.isVisible()))

  await page.locator('.explorer-viewlet .pane-header').first().hover()
  await page.locator('.explorer-viewlet a.action-label[aria-label^="New File"]').first().click()
  await page.keyboard.type('Hello.java')
  await page.keyboard.press('Enter')
  await page.waitForSelector('.editor-instance .monaco-editor')
  await page.keyboard.type('public class Hello {}')
  await page.waitForTimeout(2500)
  await page.reload()
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  const row = page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'Hello.java' })
  await row.first().waitFor({ timeout: 30_000 })
  check('file created in the workspace survives a reload', true)
  await row.first().dblclick()
  await page.waitForFunction(() =>
    /class\s+Hello/.test(document.querySelector('.editor-instance .view-lines')?.textContent ?? '')
  )
  check('saved file content survives a reload', true)

  await page.locator('.activitybar a[aria-label^="Search"]').click()
  await page.locator('.search-view .search-widget textarea').first().fill('Hello')
  await page.keyboard.press('Enter')
  await page.locator('.search-view .monaco-list-row', { hasText: 'Hello.java' }).first().waitFor({ timeout: 15_000 })
  check('search finds text in the IndexedDB workspace', true)

  await createProject('SecondRobot')
  check('a second project replaces the first as the workspace folder', (await page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'Hello.java' }).count()) === 0)
  await buildCode(page, 240_000)
  text = await terminalText()
  check('build builds the second project', text.includes('(SecondRobot)') && !text.includes('(MyRobot)') && text.includes('BUILD SUCCESSFUL'), JSON.stringify(text.slice(-300)))

  await runCommand('WPILib: Open Robot Project')
  await page.waitForSelector('.quick-input-list .monaco-list-row', { timeout: 10_000 })
  await page.keyboard.type('MyRobot')
  await page.waitForTimeout(500)
  await page.keyboard.press('Enter')
  await page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'Hello.java' }).first().waitFor({ timeout: 60_000 })
  await waitForWorkbench()
  check('Open Robot Project switches back to the first project', true)

  await runCommand('File: Close Folder')
  await page.getByText(/holds 2 robot projects/).first().waitFor({ timeout: 60_000 })
  check('the bare workspace offers to open one of the stored projects', true)
} catch (error) {
  failures.push('exception')
  console.error(error)
  await page.screenshot({ path: join(results, 'smoke-failure.png') }).catch(() => {})
  console.error(JSON.stringify(await quickInputState(page).catch(() => undefined), null, 1))
} finally {
  if (errors.length > 0) console.log(`browser errors (${errors.length}):\n${errors.slice(0, 15).join('\n')}`)
  await browser.close()
  await server?.close()
}

if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
