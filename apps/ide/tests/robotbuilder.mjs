import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { buildCode, simulate } from './wpilib.mjs'
import { preview } from 'vite'
import { quickInputReady } from './quickInput.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`)
  if (!ok) failures.push(name)
}

// TEST_URL points the test at an already running server, for example the dev server
const server = process.env.TEST_URL
  ? undefined
  : await preview({ root, preview: { port: 4174, strictPort: false, open: false } })
const url = process.env.TEST_URL ?? server.resolvedUrls?.local[0] ?? 'http://localhost:4174/'
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 850 } })
const page = await context.newPage()
page.on('pageerror', (e) => console.log(`pageerror: ${e.message}`))

const shot = (name) => page.screenshot({ path: join(results, `robotbuilder-${name}.png`) })

async function runCommand(title) {
  // Keys go to the Swing display while it has focus, and Swing takes focus back for a moment
  // after a dialog closes. So click an empty part of the explorer until focus stays out.
  for (let i = 0; i < 20; i++) {
    await page.mouse.click(200, 600)
    await page.waitForTimeout(1000)
    if (await page.evaluate(() => document.activeElement?.tagName !== 'IFRAME')) break
  }
  await page.keyboard.press('F1')
  await page.keyboard.type(`> ${title}`)
  // Enter before the list filters would run the recently used command instead.
  await page.waitForSelector(`.quick-input-list .monaco-list-row.focused[aria-label^="${title}"]`, { timeout: 10_000 })
  await page.keyboard.press('Enter')
}

async function terminalText() {
  return page.locator('.terminal-wrapper.active .xterm-rows').first().innerText()
}

try {
  await page.goto(url)
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })

  const started = Date.now()
  await runCommand('WPILib: Start Tool')
  await page.waitForSelector('.quick-input-list .monaco-list-row')
  await quickInputReady(page)
  await page.keyboard.type('RobotBuilder')
  await page.waitForSelector('.quick-input-list .monaco-list-row.focused[aria-label^="RobotBuilder"]')
  await page.keyboard.press('Enter')
  const handle = await page.waitForSelector('iframe[title="RobotBuilder"]', { timeout: 30_000 })
  const rb = await handle.contentFrame()
  // Swing draws most text on canvases, so find windows by their boxes and click at fixed offsets.
  const win = () => rb.locator('.cjWindow:visible').last()
  const titled = (frame, title) => frame.locator('.cjWindow:visible .cjTitleBarText', { hasText: title }).first()
  await win().waitFor({ timeout: 300_000 })
  console.log(`RobotBuilder window after ${((Date.now() - started) / 1000).toFixed(1)}s`)
  await page.waitForTimeout(3000)
  await shot('1-start')
  check('RobotBuilder opens in an editor tab', true)

  await win().click({ position: { x: 268, y: 61 } }) // OK on the 2027 notice
  await titled(rb, /New Project Settings/).waitFor({ timeout: 60_000 })
  await page.waitForTimeout(1000)
  await shot('2-new-project')
  await win().click({ position: { x: 284, y: 47 } })
  await page.keyboard.type('MyRobot')
  await win().click({ position: { x: 284, y: 73 } })
  await page.keyboard.press('Control+a')
  await page.keyboard.type('1234')
  await win().click({ position: { x: 285, y: 98 } })
  await titled(rb, /^FRC RobotBuilder/).waitFor({ timeout: 60_000 })
  await page.waitForTimeout(3000)
  await shot('3-main')
  // Drag a subsystem from the palette onto the tree. The empty robot's export does not compile without one.
  // Swing sometimes misses the drop on a busy machine, so drag until the tree changes.
  const main = await win().boundingBox()
  const at = (x, y) => [main.x + x, main.y + y]
  const tree = { x: main.x + 182, y: main.y + 75, width: 115, height: 110 }
  const emptyTree = await page.screenshot({ clip: tree })
  for (let i = 0; i < 3 && (await page.screenshot({ clip: tree })).equals(emptyTree); i++) {
    await page.mouse.move(...at(39, 121))
    await page.mouse.down()
    await page.waitForTimeout(500)
    await page.mouse.move(...at(52, 125), { steps: 5 })
    await page.waitForTimeout(500)
    await page.mouse.move(...at(232, 102), { steps: 20 })
    await page.waitForTimeout(500)
    await page.mouse.up()
    await page.waitForTimeout(5000)
  }
  // The drop and the dialog are slow on a busy machine, so click Save until the dialog shows.
  await page.waitForTimeout(10_000)
  for (let i = 0; i < 12 && !(await titled(rb, /^Save$/).isVisible()); i++) {
    await win().click({ position: { x: 59, y: 54 } }) // Save
    await page.waitForTimeout(5000)
  }
  await titled(rb, /^Save$/).waitFor({ timeout: 10_000 })
  await page.waitForTimeout(1000)
  await page.keyboard.press('Enter') // file name is MyRobot
  const explorerRow = (name) => page.locator('.explorer-viewlet .monaco-list-row', { hasText: name }).first()
  await explorerRow('MyRobot.yaml').waitFor({ timeout: 30_000 })
  check('saved .yaml appears in the workspace', true)
  await shot('5-saved')

  await win().click({ position: { x: 272, y: 54 } }) // Java export
  await titled(rb, /New Project Exported/).waitFor({ timeout: 60_000 })
  await explorerRow('FRCProjects').waitFor({ timeout: 60_000 })
  await shot('6-exported')
  check('exported project appears in the workspace', true)

  await win().click({ position: { x: 266, y: 85 } }) // OK on the export notice
  // The Sim GUI tab takes over the editor group
  await runCommand('WPILib: Open Simulation GUI')
  await page.waitForSelector('iframe[title="Simulation GUI"]', { timeout: 60_000 })
  // Back to RobotBuilder: its JVM must have kept running.
  await page.locator('.tab', { hasText: 'RobotBuilder' }).first().click()
  await page.waitForTimeout(3000)
  await shot('8-back')
  const box = await handle.boundingBox()
  check('RobotBuilder is still there after switching tabs', box !== null && box.width > 400 && (await win().isVisible()))

  await page.locator('.tab', { hasText: 'RobotBuilder' }).first().locator('.codicon-close, .action-label').first().click()
  await page.waitForFunction(() => !document.querySelector('iframe[title="RobotBuilder"]'), undefined, { timeout: 15_000 })
  check('closing the tab removes the JVM', true)

  // A new start copies the workspace again, so the saved robot is there to open.
  await runCommand('WPILib: Start Tool')
  await page.waitForSelector('.quick-input-list .monaco-list-row')
  await quickInputReady(page)
  await page.keyboard.type('RobotBuilder')
  await page.waitForSelector('.quick-input-list .monaco-list-row.focused[aria-label^="RobotBuilder"]')
  await page.keyboard.press('Enter')
  const again = await (await page.waitForSelector('iframe[title="RobotBuilder"]', { timeout: 30_000 })).contentFrame()
  const againWin = () => again.locator('.cjWindow:visible').last()
  await againWin().waitFor({ timeout: 300_000 })
  await page.waitForTimeout(3000)
  await againWin().click({ position: { x: 268, y: 61 } }) // OK on the 2027 notice
  await titled(again, /New Project Settings/).waitFor({ timeout: 60_000 })
  await page.waitForTimeout(1000)
  await againWin().click({ position: { x: 78, y: 98 } }) // Open Existing Project
  await titled(again, /^Open$/).waitFor({ timeout: 60_000 })
  await page.waitForTimeout(2000)
  await shot('9-reopened')
  await againWin().dblclick({ position: { x: 74, y: 126 } }) // MyRobot.yaml
  await again.locator('.cjTitleBarText', { hasText: 'MyRobot.yaml' }).first().waitFor({ timeout: 60_000 })
  check('reopened tool opens the saved .yaml', true)

  // Keys go to the Swing display while it has focus, and F1 must still reach the workbench.
  await againWin().click({ position: { x: 400, y: 300 } })
  await page.waitForFunction(() => document.activeElement?.tagName === 'IFRAME')
  await page.keyboard.press('F1')
  const quickInput = page.locator('.quick-input-widget:not([style*="display: none"]) .quick-input-box input')
  await quickInput.waitFor({ timeout: 10_000 })
  check('F1 opens the command palette while RobotBuilder has focus', await quickInput.evaluate((el) => el === document.activeElement))
  await page.keyboard.press('Escape')

  // Build and simulate work on the open folder, so open the exported project. That reloads the page.
  await page.locator('.tab', { hasText: 'RobotBuilder' }).first().locator('.codicon-close, .action-label').first().click()
  await page.waitForFunction(() => !document.querySelector('iframe[title="RobotBuilder"]'), undefined, { timeout: 15_000 })
  await runCommand('WPILib: Open Robot Project')
  await page.waitForSelector('.quick-input-list .monaco-list-row', { timeout: 10_000 })
  await page.keyboard.type('MyRobot')
  await page.waitForTimeout(500)
  await page.keyboard.press('Enter')
  await explorerRow('build.gradle').waitFor({ timeout: 60_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
  check('Open Robot Project opens the exported project', true)

  await buildCode(page, 300_000)
  const text = await terminalText()
  check('exported project builds', text.includes('BUILD SUCCESSFUL'), JSON.stringify(text.slice(-400)))
  await shot('7-built')

  await simulate(page)
  const simFrame = await (await page.waitForSelector('iframe[title="Simulation GUI"]', { timeout: 300_000 })).contentFrame()
  await simFrame.waitForFunction(() => Module.frcSim?.host?.status() === 'running', undefined, { timeout: 300_000 })
  check('exported project runs in simulation', true)
  await runCommand('WPILib: Stop Robot Simulation')
  await page.waitForSelector('iframe[title="Simulation GUI"]', { state: 'detached', timeout: 30_000 })
} catch (error) {
  failures.push('exception')
  console.error(error)
  await shot('failure').catch(() => {})
} finally {
  await browser.close()
  await server?.close()
}

if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
