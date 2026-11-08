// Runs a simulated robot, opens SmartDashboard from "WPILib: Start Tool" and checks that it
// connects to the NetworkTables server in the page and shows what the robot publishes. Needs
// `pnpm fetch-deps`, `pnpm build:java` and `pnpm build` first.
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { createProject, simulate } from './wpilib.mjs'
import { preview } from 'vite'

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
  : await preview({ root, preview: { port: 4176, strictPort: false, open: false } })
const url = process.env.TEST_URL ?? server.resolvedUrls?.local[0] ?? 'http://localhost:4176/'
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 850 } })
const page = await context.newPage()
page.on('pageerror', (e) => console.log(`pageerror: ${e.message}`))

const shot = (name) => page.screenshot({ path: join(results, `smartdashboard-${name}.png`) })

async function runCommand(title) {
  await page.keyboard.press('F1')
  await page.keyboard.type(`> ${title}`)
  await page.waitForSelector(`.quick-input-list .monaco-list-row.focused[aria-label^="${title}"]`, { timeout: 10_000 })
  await page.keyboard.press('Enter')
}

try {
  await page.goto(url)
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })

  // The Timed Robot template puts a chooser on the SmartDashboard table.
  // The new project opens as the workspace folder, which reloads the page
  await createProject(page, { name: 'MyRobot', base: 'Timed Robot' })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })

  await simulate(page)
  const simFrame = await (await page.waitForSelector('iframe[title="Simulation GUI"]', { timeout: 300_000 })).contentFrame()
  await simFrame.waitForFunction(() => Module.frcSim?.host?.status() === 'running', undefined, { timeout: 300_000 })
  await page.waitForFunction(
    () => (document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? '').includes('Robot program startup complete'),
    undefined,
    { timeout: 120_000 }
  )
  check('robot runs in simulation', true)

  const started = Date.now()
  await runCommand('WPILib: Start Tool')
  await page.waitForSelector('.quick-input-list .monaco-list-row')
  await page.keyboard.type('SmartDashboard')
  await page.waitForSelector('.quick-input-list .monaco-list-row.focused[aria-label^="SmartDashboard"]')
  await page.keyboard.press('Enter')
  const handle = await page.waitForSelector('iframe[title="SmartDashboard"]', { timeout: 30_000 })
  const sd = await handle.contentFrame()
  const titles = sd.locator('.cjWindow:visible .cjTitleBarText')
  await titles.first().waitFor({ timeout: 300_000 })
  console.log(`SmartDashboard window after ${((Date.now() - started) / 1000).toFixed(1)}s`)
  await page.waitForTimeout(3000)
  await shot('1-start')
  check('SmartDashboard opens in an editor tab', true)

  // The title follows the connection, so this is NetworkTables reaching the server in the page.
  await sd.locator('.cjWindow:visible .cjTitleBarText', { hasText: /^SmartDashboard \| Connected/ }).first().waitFor({ timeout: 60_000 })
  check('SmartDashboard connects to the NetworkTables server', true)

  // The Timed Robot chooser shows up as radio buttons in the top left corner of the dashboard.
  const dashboard = await sd.locator('.cjWindow:visible', { has: sd.locator('.cjTitleBarText', { hasText: /^SmartDashboard/ }) }).first().boundingBox()
  const chooser = { x: dashboard.x + 22, y: dashboard.y + 60, width: 200, height: 60 }
  // The chooser's text rows: for each run of rows with dark pixels, its middle and where it starts.
  // SmartDashboard lays the chooser out a little differently from run to run.
  const chooserRows = async () => {
    const png = await page.screenshot({ clip: chooser })
    return page.evaluate(async (base64) => {
      const image = new Image()
      image.src = `data:image/png;base64,${base64}`
      await image.decode()
      const canvas = document.createElement('canvas')
      canvas.width = image.width
      canvas.height = image.height
      const context = canvas.getContext('2d')
      context.drawImage(image, 0, 0)
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data
      const rows = []
      let run
      for (let y = 0; y < canvas.height; y++) {
        let first = -1
        let dark = 0
        for (let x = 0; x < canvas.width; x++) {
          const i = (y * canvas.width + x) * 4
          if (pixels[i] < 100 && pixels[i + 1] < 100 && pixels[i + 2] < 100) {
            dark++
            if (first < 0) first = x
          }
        }
        if (dark && run) Object.assign(run, { bottom: y, left: Math.min(run.left, first), dark: run.dark + dark })
        else if (dark) rows.push((run = { top: y, bottom: y, left: first, dark }))
        else run = undefined
      }
      return rows.map((r) => ({ y: (r.top + r.bottom) / 2, x: r.left, dark: r.dark }))
    }, png.toString('base64'))
  }
  const darkPixels = async () => (await chooserRows()).reduce((sum, row) => sum + row.dark, 0)
  for (let i = 0; i < 60 && (await darkPixels()) < 50; i++) await page.waitForTimeout(500)
  await shot('2-connected')
  check('a value the robot publishes reaches SmartDashboard', (await darkPixels()) >= 50)

  // Choosing "My Auto" makes SmartDashboard publish the chooser's selection to the server.
  const selected = () =>
    page.evaluate(() => window.frcDevWasmNt4.getTopics().find((t) => t.name === '/SmartDashboard/Auto choices/selected')?.value)
  // The second row of the chooser
  const myAuto = (await chooserRows())[1]
  await page.mouse.click(chooser.x + myAuto.x + 6, chooser.y + myAuto.y)
  for (let i = 0; i < 20 && (await selected()) !== 'My Auto'; i++) await page.waitForTimeout(500)
  await shot('3-chosen')
  check('a value chosen in SmartDashboard reaches the server', (await selected()) === 'My Auto', `${await selected()}, dashboard window at ${dashboard.x},${dashboard.y}`)

  // Keys go to the Swing display while it has focus, and F1 must still reach the workbench.
  await sd.locator('.cjWindow:visible').last().click({ position: { x: 300, y: 300 } })
  await page.waitForFunction(() => document.activeElement?.tagName === 'IFRAME')
  await page.keyboard.press('F1')
  const quickInput = page.locator('.quick-input-widget:not([style*="display: none"]) .quick-input-box input')
  await quickInput.waitFor({ timeout: 10_000 })
  check('F1 opens the command palette while SmartDashboard has focus', await quickInput.evaluate((el) => el === document.activeElement))
  await page.keyboard.press('Escape')

  // The choice reaches the robot program, which reads it when autonomous starts.
  await page.locator('.tab', { hasText: 'Simulation GUI' }).first().click()
  // The Autonomous entry of the Robot State window, in the Sim GUI's default layout.
  await page.waitForTimeout(500)
  const simBox = await page.locator('iframe[title="Simulation GUI"]').boundingBox()
  await page.mouse.click(simBox.x + 45, simBox.y + 87)
  await page.waitForFunction(
    () => (document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? '').includes('Auto selected: My Auto'),
    undefined,
    { timeout: 30_000 }
  )
  check('the robot program reads the choice made in SmartDashboard', true)

  await runCommand('WPILib: Stop Robot Simulation')
  await page.waitForFunction(
    () => (document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? '').includes('Robot program exited'),
    undefined,
    { timeout: 30_000 }
  )
  await page.locator('.tab', { hasText: 'SmartDashboard' }).first().click()
  await page.waitForTimeout(3000)
  const box = await handle.boundingBox()
  check('SmartDashboard is still there after the robot stops', box !== null && box.width > 400)
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
