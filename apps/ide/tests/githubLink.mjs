// A link such as ?github=owner/repo asks to clone that repository, takes the parameter out of the address, and
// clones nothing when the user cancels. A link that names no repository says so. Nothing is fetched from GitHub.
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { preview } from 'vite'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`)
  if (!ok) failures.push(name)
}

const server = process.env.SMOKE_URL ? undefined : await preview({ root, preview: { port: 4177, strictPort: false, open: false } })
const url = process.env.SMOKE_URL ?? server.resolvedUrls?.local[0] ?? 'http://localhost:4177/'
const browser = await chromium.launch({ headless: true })
const page = await (await browser.newContext({ viewport: { width: 1400, height: 850 } })).newPage()
const github = []
page.on('request', (request) => {
  // GitHub directly, or through the proxy's git route
  if (/^https:\/\/([^/]+\.)?github\.com\/|^https:\/\/frc-proxy\.project516\.dev\/git\//.test(request.url())) github.push(request.url())
})

try {
  await page.goto(`${url}?github=octocat/Hello-World`)
  const dialog = page.locator('.monaco-dialog-box')
  await dialog.waitFor({ timeout: 120_000 })
  const message = await dialog.locator('.dialog-message-text').innerText()
  check('the link asks to clone the repository it names', message === 'Clone octocat/Hello-World from GitHub and open it?', message)
  check('the parameter leaves the address', !new URL(page.url()).searchParams.has('github'), page.url())
  await dialog.locator('.monaco-button', { hasText: 'Cancel' }).click()
  await page.waitForTimeout(4000)
  check('cancelling clones nothing', github.length === 0 && (await page.locator('.monaco-dialog-box').count()) === 0, github.join(' '))

  await page.goto(`${url}?github=not%20a%20repository`)
  const toast = page.locator('.notifications-toasts .notification-list-item', { hasText: 'not a GitHub repository' })
  check('a link that names no repository says so', await toast.waitFor({ timeout: 120_000 }).then(() => true, () => false))

  await page.goto(`${url}?github=${encodeURIComponent('[Continue](command:workbench.action.reloadWindow)')}`)
  const planted = page.locator('.notifications-toasts .notification-list-item', { hasText: 'not a GitHub repository' })
  await planted.waitFor({ timeout: 120_000 })
  check('a link value cannot plant a command link in the message', (await planted.locator('.notification-list-item-message a').count()) === 0)

  await page.goto(`${url}?github=owner/..`)
  const parent = page.locator('.notifications-toasts .notification-list-item', { hasText: 'not a GitHub repository' })
  check('a link to .. is no repository', await parent.waitFor({ timeout: 120_000 }).then(() => true, () => false))
} catch (error) {
  await page.screenshot({ path: join(results, 'githubLink.png') }).catch(() => {})
  check('the test ran', false, error instanceof Error ? error.message : String(error))
} finally {
  await browser.close()
  await server?.close()
}
if (failures.length) {
  console.log(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
