import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { buildCode, createProject as createWpilibProject } from './wpilib.mjs'
import { preview } from 'vite'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`)
  if (!ok) failures.push(name)
}

const server = process.env.SMOKE_URL
  ? undefined
  : await preview({ root, preview: { port: 4174, strictPort: false, open: false } })
const url = process.env.SMOKE_URL ?? server.resolvedUrls?.local[0] ?? 'http://localhost:4174/'
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 850 } })
const page = await context.newPage()

async function poll(read, timeout = 120_000) {
  for (const start = Date.now(); Date.now() - start < timeout; await page.waitForTimeout(500)) {
    if (await read()) return true
  }
  return false
}

async function runCommand(title) {
  await page.evaluate(() => (document.activeElement instanceof HTMLElement ? document.activeElement.blur() : undefined))
  await page.keyboard.press('F1')
  await page.keyboard.type(`> ${title}`)
  await page.waitForSelector('.quick-input-list .monaco-list-row', { timeout: 10_000 })
  await page.keyboard.press('Enter')
}

async function load() {
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
}

async function createProject() {
  await createWpilibProject(page, { name: 'MyRobot', base: 'Command Robot' })
  await load()
}

// Reads from or writes to an IndexedDB object store of the page. The function runs in the page.
const inDb = (name, fn, arg) =>
  page.evaluate(
    async ([dbName, source, argument]) => {
      const open = indexedDB.open(dbName)
      const db = await new Promise((resolve, reject) => {
        open.onsuccess = () => resolve(open.result)
        open.onerror = () => reject(open.error)
      })
      try {
        return await new Function('db', 'arg', `return (${source})(db, arg)`)(db, argument)
      } finally {
        db.close()
      }
    },
    [name, fn.toString(), arg]
  )

const CHEERPJ = 'cjFS_/files/'
const WORKSPACE = 'frc-dev-wasm-files'

const keysOf = (db) =>
  new Promise((resolve) => {
    const req = db.transaction('files').objectStore('files').getAllKeys()
    req.onsuccess = () => resolve(req.result.map(String))
  })
const cjKeys = () => inDb(CHEERPJ, keysOf)
// Paths of the workspace files in the origin private file system
const workspaceKeys = () =>
  page.evaluate(async (store) => {
    const keys = []
    const walk = async (dir, path) => {
      for await (const [name, handle] of dir.entries()) {
        if (handle.kind === 'file') keys.push(`${path}/${name}`)
        else await walk(handle, `${path}/${name}`)
      }
    }
    const root = await (await navigator.storage.getDirectory()).getDirectoryHandle(store).catch(() => undefined)
    if (root) await walk(root, '')
    return keys
  }, WORKSPACE)
const cjRecord = (key) =>
  inDb(
    CHEERPJ,
    (db, k) =>
      new Promise((resolve) => {
        const req = db.transaction('files').objectStore('files').get(k)
        req.onsuccess = () => resolve(req.result)
      }),
    key
  )

// Writes a file record and the directory records above it, in the shape CheerpJ uses
const seedFile = (path, ageDays) =>
  inDb(
    CHEERPJ,
    async (db, [filePath, age]) => {
      const tx = db.transaction('files', 'readwrite')
      const store = tx.objectStore('files')
      const get = (key) =>
        new Promise((resolve) => {
          const req = store.get(key)
          req.onsuccess = () => resolve(req.result)
        })
      const done = new Promise((resolve) => (tx.oncomplete = resolve))
      const lastModified = Math.floor(Date.now() / 1000 - age * 86_400)
      const parts = filePath.split('/').filter(Boolean)
      for (let i = 1; i <= parts.length; i++) {
        const key = `/${parts.slice(0, i).join('/')}`
        const parentKey = i === 1 ? '' : `/${parts.slice(0, i - 1).join('/')}`
        const parent = await get(parentKey)
        if (!parent.contents.includes(`/${parts[i - 1]}`)) {
          parent.contents.push(`/${parts[i - 1]}`)
          store.put(parent, parentKey)
        }
        const last = i === parts.length
        if (last || !(await get(key))) {
          store.put(
            {
              type: last ? 'file' : 'dir',
              contents: last ? new Uint8Array(100) : [],
              inodeId: 10_000 + Math.floor(Math.random() * 1e6),
              permType: last ? 33206 : 16895,
              uid: 0,
              gid: 0,
              lastModified
            },
            key
          )
        }
      }
      await done
    },
    [path, ageDays]
  )

const removeWorkspaceProject = () =>
  page.evaluate(async (store) => {
    const root = await (await navigator.storage.getDirectory()).getDirectoryHandle(store)
    await (await root.getDirectoryHandle('workspace')).removeEntry('MyRobot', { recursive: true })
  }, WORKSPACE)

try {
  await page.goto(url)
  await load()

  // Projects an earlier version kept in IndexedDB move to the origin private file system
  await page.evaluate(
    () =>
      new Promise((resolve, reject) => {
        const open = indexedDB.open('frc-dev-wasm-workspace', 1)
        open.onupgradeneeded = () => open.result.createObjectStore('files')
        open.onsuccess = () => {
          const tx = open.result.transaction('files', 'readwrite')
          tx.objectStore('files').put(new TextEncoder().encode('old project'), '/workspace/Legacy/notes.txt')
          tx.oncomplete = () => resolve(open.result.close())
          tx.onerror = () => reject(tx.error)
        }
        open.onerror = () => reject(open.error)
      })
  )
  await page.reload()
  await load()
  check('a project kept in IndexedDB moves to the origin private file system', (await workspaceKeys()).includes('/workspace/Legacy/notes.txt'))
  check('the old IndexedDB workspace is deleted after the move', !(await page.evaluate(async () => (await indexedDB.databases()).map((db) => db.name))).includes('frc-dev-wasm-workspace'))

  await createProject()
  await buildCode(page, 240_000)
  check('build wrote output to the CheerpJ file area', (await cjKeys()).includes('/frcdevwasm/build.jar'))

  // Background cleanup: a stale Swing folder goes. A recent one and the build output of an existing project stay.
  await seedFile('/frcdevwasm/swing/old-tool/notes.txt', 90)
  await seedFile('/frcdevwasm/swing/recent-tool/notes.txt', 1)
  await page.reload()
  await load()
  check('background cleanup removes a Swing folder unused for months', await poll(async () => !(await cjKeys()).some((k) => k.startsWith('/frcdevwasm/swing/old-tool'))))
  let keys = await cjKeys()
  check('recently used Swing folder survives cleanup', keys.includes('/frcdevwasm/swing/recent-tool/notes.txt'))
  check('build output of an existing project survives cleanup', keys.includes('/frcdevwasm/build.jar'))
  check('cleanup drops the removed folder from its parent directory', !(await cjRecord('/frcdevwasm/swing')).contents.includes('/old-tool'))
  const statuses = await page.evaluate(async () => {
    const base = new URL('runtime/2026/', location.href)
    const manifest = await (await fetch(new URL('manifest.json', base))).json()
    return Promise.all(['frcdevwasm-tools.jar', ...manifest.classpath.slice(0, 3)].map((f) => fetch(new URL(f, base), { method: 'HEAD' }).then((r) => r.status)))
  })
  check('current runtime files are still served', statuses.every((s) => s === 200), statuses.join(','))

  // Clear Caches keeps the workspace and credentials
  await page.evaluate(async () => {
    localStorage.setItem('frc-dev-wasm.test-credential', 'secret')
    await (await caches.open('stale-runtime')).put('/old-runtime.jar', new Response('old'))
  })
  const workspaceBefore = await workspaceKeys()
  await runCommand('WPILib: Clear Caches')
  const dialog = page.locator('.monaco-dialog-box')
  await dialog.waitFor({ timeout: 10_000 })
  await dialog.getByText('are not touched').waitFor({ timeout: 5000 })
  check('Clear Caches asks for confirmation in a modal', true)
  await dialog.getByRole('button', { name: 'Clear Caches' }).click()
  const notice = page.locator('.notification-list-item', { hasText: 'Caches cleared' })
  await notice.first().waitFor({ timeout: 30_000 })
  check('Clear Caches reports what was freed', /freed/.test(await notice.first().innerText()))
  keys = await cjKeys()
  check('Clear Caches removes build output and Swing folders', !keys.some((k) => k.startsWith('/frcdevwasm/')), keys.join(' '))
  check('Clear Caches empties Cache Storage', (await page.evaluate(() => caches.keys())).length === 0)
  const workspaceAfter = await workspaceKeys()
  check('Clear Caches keeps every workspace file', workspaceBefore.length > 0 && workspaceBefore.every((k) => workspaceAfter.includes(k)))
  check('Clear Caches keeps stored credentials', (await page.evaluate(() => localStorage.getItem('frc-dev-wasm.test-credential'))) === 'secret')
  await notice.first().getByRole('button', { name: 'Reload' }).click()
  await page.waitForTimeout(1000)
  await load()
  await page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'build.gradle' }).first().waitFor({ timeout: 30_000 })
  check('workspace project is still there after the reload', true)

  // With the project gone, the build output is stale
  await seedFile('/frcdevwasm/build.jar', 0)
  await seedFile('/frcdevwasm/robot/deploy/example.txt', 0)
  await removeWorkspaceProject()
  await page.reload()
  await load()
  check('background cleanup removes build output when no project exists', await poll(async () => !(await cjKeys()).some((k) => k.startsWith('/frcdevwasm/'))))
  check('cleanup drops build output from its parent directory', (await cjRecord('/frcdevwasm')).contents.length === 0)

  // Clear Site Data deletes what this app stores, and nothing another app on the origin stores
  await createProject()
  await seedFile('/frcdevwasm/build.jar', 0)
  await page.evaluate(async () => {
    localStorage.setItem('other-app.setting', 'keep')
    await (await caches.open('other-app')).put('/x', new Response('keep'))
    await (await caches.open('frc-vendor-jars-test')).put('/x', new Response('vendor'))
    await new Promise((resolve) => {
      const req = indexedDB.open('other-app-db')
      req.onsuccess = () => resolve(req.result.close())
    })
    await (await navigator.storage.getDirectory()).getDirectoryHandle('other-app-dir', { create: true })
  })
  const stored = () =>
    page.evaluate(async () => ({
      databases: (await indexedDB.databases()).map((db) => db.name),
      caches: await caches.keys(),
      keys: [...Object.keys(localStorage), ...Object.keys(sessionStorage)],
      opfs: await Array.fromAsync((await navigator.storage.getDirectory()).keys())
    }))
  const OWNED = {
    databases: ['frc-dev-wasm', 'vscode-', 'cjFS_'],
    caches: ['frc-', 'vscode-resource-cache-'],
    keys: ['frc-dev-wasm', 'DefaultOverridesCacheExists', 'monaco-parts-splash'],
    opfs: ['frc-dev-wasm']
  }
  const before = await stored()
  const unknown = Object.entries(before).flatMap(([kind, names]) =>
    names.filter((n) => !n.startsWith('other-app') && !OWNED[kind].some((p) => n.startsWith(p))).map((n) => `${kind} ${n}`)
  )
  check('Clear Site Data knows every name the app stores data under', unknown.length === 0, unknown.join(', '))

  await page.goto(`${url}?clear-site-data=from-a-link`)
  await load()
  check('a link with the clear parameter deletes nothing', (await workspaceKeys()).some((k) => k.endsWith('/build.gradle')))
  check('the clear parameter is dropped from the address', !page.url().includes('clear-site-data'))

  await runCommand('WPILib: Clear Site Data')
  const confirm = page.locator('.monaco-dialog-box')
  await confirm.waitFor({ timeout: 10_000 })
  await confirm.getByText('cannot be undone').waitFor({ timeout: 5000 })
  check('Clear Site Data asks for confirmation in a modal', true)
  const navigated = page.waitForNavigation({ timeout: 60_000 })
  await confirm.getByRole('button', { name: 'Delete Everything' }).click()
  await navigated
  await load()
  const after = await stored()
  check('Clear Site Data deletes the projects', !(await workspaceKeys()).some((k) => k.endsWith('/build.gradle')))
  check('Clear Site Data deletes build output', !after.databases.includes(CHEERPJ) || !(await cjKeys()).some((k) => k.startsWith('/frcdevwasm/')))
  check('Clear Site Data deletes vendor caches', !after.caches.some((n) => n.startsWith('frc-')), after.caches.join(', '))
  check('Clear Site Data forgets the open project', (await page.evaluate(() => localStorage.getItem('frc-dev-wasm.folder'))) === null)
  check(
    'Clear Site Data keeps what other apps on the origin stored',
    after.databases.includes('other-app-db') && after.caches.includes('other-app') && after.keys.includes('other-app.setting') && after.opfs.includes('other-app-dir')
  )
} catch (error) {
  failures.push('exception')
  console.error(error)
  await page.screenshot({ path: join(results, 'cache-failure.png') }).catch(() => {})
} finally {
  await browser.close()
  await server?.close()
}

if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
