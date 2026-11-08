// Opens DataLogTool in the real IDE and checks the whole file path: a log in the
// IndexedDB workspace is opened through VS Code's file dialog, and the CSV export
// is saved back into the workspace. Needs `pnpm build:datalogtool`, `pnpm fetch-deps`
// and `pnpm build` first.
import { mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { zipSync } from 'fflate'
import { preview } from 'vite'
import { WpiLog } from '../../../packages/wpigui-tools/test/wpilog.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`)
  if (!ok) failures.push(name)
}

const log = new WpiLog()
const speed = log.start('/Test/Speed', 'double')
log.double(speed, 1_000_000, 1.5)
log.double(speed, 2_000_000, 2.5)

const server = await preview({ root, preview: { port: 4175, strictPort: false, open: false } })
const url = server.resolvedUrls?.local[0]
const browser = await chromium.launch({ headless: true, args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] })
const page = await (await browser.newContext({ viewport: { width: 1400, height: 850 } })).newPage()
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

// Reads a workspace file from the origin private file system, or null when it is missing
const readWorkspace = (path) =>
  page.evaluate(async (p) => {
    const parts = p.split('/').filter(Boolean)
    const name = parts.pop()
    try {
      let dir = await (await navigator.storage.getDirectory()).getDirectoryHandle('frc-dev-wasm-files')
      for (const part of parts) dir = await dir.getDirectoryHandle(part)
      return await (await (await dir.getFileHandle(name)).getFile()).text()
    } catch {
      return null
    }
  }, path)

// Enters a path in the file dialog and presses Enter until the dialog closes. The dialog resets its
// input when it finishes listing a folder, so the path is entered again until it stays. The first
// Enter can still be navigating.
async function answerDialog(path) {
  const widget = page.locator('.quick-input-widget:not([style*="display: none"])')
  const input = widget.locator('.quick-input-box input')
  await input.waitFor({ timeout: 15_000 })
  await widget.locator('.quick-input-list .monaco-list-row').first().waitFor({ timeout: 15_000 }).catch(() => {})
  for (let i = 0; i < 5; i++) {
    await input.fill(path)
    await page.waitForTimeout(700)
    if ((await input.inputValue()) === path) break
  }
  for (let i = 0; i < 5 && (await widget.count()); i++) {
    await page.waitForTimeout(800)
    await page.keyboard.press('Enter')
  }
  await page.waitForTimeout(500)
}

try {
  await page.goto(url)
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })

  const zip = join(tmpdir(), `frc-tools-${Date.now()}.zip`)
  writeFileSync(zip, zipSync({ 'Logs/sample.wpilog': log.toBytes() }))
  const chooser = page.waitForEvent('filechooser', { timeout: 10_000 })
  await runCommand('WPILib: Import Project from Zip')
  await (await chooser).setFiles(zip)
  await page.waitForSelector('.quick-input-box input')
  await page.keyboard.press('Enter')
  // The import opens as the workspace folder, which reloads the page
  await page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'sample.wpilog' }).first().waitFor({ timeout: 30_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
  check('a log is in the workspace', true)

  await runCommand('WPILib: Start Tool')
  await page.waitForSelector('.quick-input-list .monaco-list-row', { timeout: 10_000 })
  // Keys typed before the pick's input has focus are lost
  await page.waitForSelector('.quick-input-box input:focus', { timeout: 10_000 })
  await page.keyboard.type('DataLogTool')
  await page.waitForSelector('.quick-input-list .monaco-list-row.focused[aria-label^="DataLogTool"]')
  await page.keyboard.press('Enter')
  const handle = await page.waitForSelector('iframe[title="DataLogTool"]', { timeout: 30_000 })
  const tool = await handle.contentFrame()
  const canvas = tool.locator('canvas#canvas')
  await canvas.waitFor({ timeout: 60_000 })
  await page.waitForTimeout(2000)
  const click = (x, y) => canvas.click({ position: { x, y } })
  await page.screenshot({ path: join(results, 'tools-1-start.png') })

  await click(58, 56)
  await answerDialog('/workspace/Logs/sample.wpilog')
  await page.waitForTimeout(1500)
  await page.screenshot({ path: join(results, 'tools-2-opened.png') })
  check('the file dialog hands the log to the tool', await tool.evaluate(() => Module.FS.analyzePath('/ws/workspace/Logs/sample.wpilog').exists))

  await click(464, 426)
  await answerDialog('/workspace/Logs')
  await page.waitForTimeout(1000)
  await click(423, 489)
  for (let i = 0; i < 20 && !(await readWorkspace('/workspace/Logs/sample.csv')); i++) await page.waitForTimeout(500)
  const text = await readWorkspace('/workspace/Logs/sample.csv')
  await page.screenshot({ path: join(results, 'tools-3-exported.png') })
  check('the CSV export is saved in the workspace', !!text?.includes('"/Test/Speed",2.5'), JSON.stringify(text))

  // No tool here has a save dialog, so ask for one the way the portable-file-dialogs replacement does.
  await tool.evaluate(() => {
    window.saveId = Module.frcFiles.start('save', 'Save notes', '/ws/workspace/Logs/notes.txt', 'Text files\x1f*.txt\x1f', false)
  })
  await answerDialog('/workspace/Logs/notes.txt')
  const saved = await tool.evaluate(async () => {
    for (let i = 0; i < 40 && !Module.frcFiles.done(window.saveId); i++) await new Promise((resolve) => setTimeout(resolve, 100))
    const path = Module.frcFiles.take(window.saveId).trim()
    if (path) Module.FS.writeFile(path, 'saved by a tool')
    return path
  })
  check('a save dialog returns a path under /ws', saved === '/ws/workspace/Logs/notes.txt', saved)
  for (let i = 0; i < 20 && !(await readWorkspace('/workspace/Logs/notes.txt')); i++) await page.waitForTimeout(500)
  check('a file written to that path is saved in the workspace', (await readWorkspace('/workspace/Logs/notes.txt')) === 'saved by a tool')
} catch (error) {
  failures.push('exception')
  console.error(error)
  await page.screenshot({ path: join(results, 'tools-failure.png') }).catch(() => {})
} finally {
  if (errors.length) console.log(`browser errors (${errors.length}):\n${errors.slice(0, 10).join('\n')}`)
  await browser.close()
  await server.close()
}
process.exit(failures.length ? 1 : 0)
