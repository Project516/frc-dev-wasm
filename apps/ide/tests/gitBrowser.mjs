// Browser section of the Git tests. Projects in the browser workspace use VS Code's Git extension on
// the real git, which clones from GitHub through the CORS proxy. Folders opened from disk use the
// IDE's own Source Control on isomorphic-git.
import { mkdirSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { chromium } from 'playwright-core'
import { unzipSync, zipSync } from 'fflate'
import { preview } from 'vite'
import { quickInputReady } from './quickInput.mjs'

const SMALL_REPO = 'octocat/Hello-World'
const BIG_REPO = process.env.GIT_TEST_BIG_REPO ?? 'Spectrum3847/2026-Spectrum'

export async function browserTests({ root, check }) {
  const results = join(root, 'test-results')
  mkdirSync(results, { recursive: true })
  const server = await preview({ root, preview: { port: 4174, strictPort: false, open: false } })
  const url = server.resolvedUrls?.local[0]
  const browser = await chromium.launch({ headless: true })
  const errors = []
  let lastPage
  // GIT_TEST_SECTION=clone|folder|unsupported|github|big runs one section
  const want = (name) => !process.env.GIT_TEST_SECTION || process.env.GIT_TEST_SECTION === name

  async function newPage(init, route) {
    const context = await browser.newContext({ viewport: { width: 1400, height: 850 }, acceptDownloads: true })
    if (init) await context.addInitScript(init)
    const page = await context.newPage()
    if (route) await page.route('https://api.github.com/**', route)
    lastPage = page
    const seen = { urls: [], api: [] }
    page.on('request', (r) => seen.urls.push(r.url()))
    page.on('response', (r) => {
      const u = new URL(r.url())
      if (u.host !== 'api.github.com') return
      seen.api.push(`${r.request().method()} ${u.pathname} ${r.status()}`)
    })
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
    page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 300)}`))
    await page.goto(url)
    await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
    await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
    return { context, page, seen }
  }

  const runCommand = async (page, title, timeout = 30_000) => {
    // Fuzzy matching can rank another command first, so pick the row by its title
    const row = page.locator('.quick-input-list .monaco-list-row', { hasText: title }).first()
    // The palette lists the commands registered when it opens, so reopen it until one that an extension
    // registers after a reload shows up
    for (const start = Date.now(); ; ) {
      await page.evaluate(() => (document.activeElement instanceof HTMLElement ? document.activeElement.blur() : undefined))
      await page.keyboard.press('F1')
      // F1 prefills the prompt with ">" once it opens, so set the whole value instead of typing
      await page.locator('.quick-input-widget input').fill(`> ${title}`)
      try {
        await row.waitFor({ timeout: 5_000 })
        break
      } catch (error) {
        if (Date.now() - start > timeout) throw error
        await page.keyboard.press('Escape')
      }
    }
    await page.waitForTimeout(300)
    await row.click()
  }
  const answer = async (page, text) => {
    await quickInputReady(page)
    await page.keyboard.type(text)
    await page.keyboard.press('Enter')
  }
  const explorerRow = (page, text) => page.locator('.explorer-viewlet .monaco-list-row', { hasText: text }).first()
  const toast = (page, text) => page.locator('.notification-toast', { hasText: text }).first()

  // Git: Clone, into /workspace, the folder its dialog starts in. The clone opens as the workspace
  // folder, which reloads the page.
  const clone = async (page, repo) => {
    // The Git extension registers its commands shortly after the workbench starts
    for (let attempt = 0; ; attempt++) {
      try {
        await runCommand(page, 'Git: Clone')
        break
      } catch (error) {
        if (attempt >= 5) throw error
        await page.keyboard.press('Escape')
        await page.waitForTimeout(2000)
      }
    }
    await answer(page, `https://github.com/${repo}`)
    const widget = page.locator('.quick-input-widget:not([style*="display: none"])')
    await widget.locator('.quick-input-box input').waitFor({ timeout: 15_000 })
    // The file dialog can still be listing the folder at the first Enter
    for (let i = 0; i < 5 && (await widget.count()); i++) {
      await page.waitForTimeout(800)
      await page.keyboard.press('Enter')
    }
  }
  // Like desktop git, the real git commits only with a name and email, from ~/.gitconfig
  const setIdentity = (page) => opfs(page, 'write', '/frc-dev-wasm-files/.gitconfig', '[user]\n\tname = Test User\n\temail = test@project516.dev\n')
  // The commit a ref points at, from a loose ref or packed-refs
  const refOf = async (page, repo, ref) =>
    (await readWorkspace(page, `/workspace/${repo}/.git/${ref}`))?.trim() ||
    (await readWorkspace(page, `/workspace/${repo}/.git/packed-refs`))?.split('\n').find((line) => line.endsWith(` ${ref}`))?.split(' ')[0]

  // Reads a workspace file from the origin private file system, or null when it is missing
  const readWorkspace = (page, path) =>
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

  const opfs = (page, op, path, data) =>
    page.evaluate(
      async ([operation, p, content]) => {
        const parts = p.split('/').filter(Boolean)
        const name = parts.pop()
        let dir = await navigator.storage.getDirectory()
        try {
          for (const part of parts) dir = await dir.getDirectoryHandle(part, { create: operation === 'write' })
          if (operation === 'write') {
            const writable = await (await dir.getFileHandle(name, { create: true })).createWritable()
            await writable.write(content)
            await writable.close()
            return true
          }
          if (operation === 'list') {
            const names = []
            for await (const [n] of (await dir.getDirectoryHandle(name))) names.push(n)
            return names
          }
          return await (await (await dir.getFileHandle(name)).getFile()).text()
        } catch {
          return null
        }
      },
      [op, path, data]
    )

  try {
    if (want('clone')) {
      const { context, page, seen } = await newPage()
      check('isomorphic-git is not loaded at startup', !seen.urls.some((u) => /isomorphic-git/.test(u)))
      await setIdentity(page)

      await clone(page, SMALL_REPO)
      await explorerRow(page, 'README').waitFor({ timeout: 180_000 })
      await page.waitForFunction(() => document.querySelector('.statusbar')?.textContent?.includes('master'), undefined, { timeout: 60_000 })
      check('the clone is the workspace folder', await page.locator('.explorer-viewlet .pane-header', { hasText: /Hello-World/i }).first().isVisible())
      const remoteHead = await refOf(page, 'Hello-World', 'refs/remotes/origin/master')
      check('the clone has the remote branch and checks it out', !!remoteHead && (await refOf(page, 'Hello-World', 'refs/heads/master')) === remoteHead, remoteHead)
      check('the clone has the whole history', ((await readWorkspace(page, '/workspace/Hello-World/.git/shallow')) ?? '') === '')
      check('isomorphic-git is not loaded', !seen.urls.some((u) => /isomorphic-git/.test(u)))
      check('no GitHub API requests were made', seen.api.length === 0, seen.api.join(', '))

      // Edit, see the change in Source Control, commit
      await explorerRow(page, 'README').dblclick()
      await page.waitForSelector('.editor-instance .monaco-editor')
      await page.keyboard.type('Edited by the test. ')
      await page.keyboard.press('Control+s')
      await page.keyboard.press('Control+Shift+G')
      // By label: commit messages in the graph mention the readme too
      const scmRow = page.locator('.scm-view .monaco-list-row[aria-label^="README,"]').first()
      await scmRow.waitFor({ timeout: 30_000 })
      const label = await scmRow.getAttribute('aria-label')
      check('Source Control lists the edited file as modified', /Modified/.test(label ?? ''), label)
      await page.locator('.monaco-editor [class*="dirty-diff"]').first().waitFor({ timeout: 15_000 }).catch(() => {})
      check('quick diff gutter marks the edited line', (await page.locator('.monaco-editor [class*="dirty-diff"]').count()) > 0)
      await page.screenshot({ path: join(results, 'git-scm.png') })

      await scmRow.click()
      await page.waitForSelector('.monaco-diff-editor', { timeout: 15_000 })
      check('clicking a change opens a diff editor', true)
      await page.screenshot({ path: join(results, 'git-diff.png') })

      await scmRow.hover()
      await scmRow.locator('a.action-label[aria-label^="Stage"]').click()
      await page.locator('.scm-view .monaco-list-row', { hasText: 'Staged Changes' }).first().waitFor({ timeout: 15_000 })
      check('staging moves the file to Staged Changes', true)

      await page.locator('.scm-editor .monaco-editor').first().click()
      await page.keyboard.type('Test edit of the readme')
      await page.keyboard.press('Control+Enter')
      await scmRow.waitFor({ state: 'detached', timeout: 30_000 })
      const commitId = await refOf(page, 'Hello-World', 'refs/heads/master')
      check('commit moves the branch and clears the changes', !!commitId && commitId !== remoteHead, commitId?.slice(0, 7))
      await page.waitForFunction(() => /master[\s\S]*1/.test(document.querySelector('.statusbar')?.textContent ?? ''), undefined, { timeout: 15_000 }).catch(() => {})
      const statusText = await page.locator('.statusbar').innerText()
      check('status bar shows the branch and one commit ahead', /master/.test(statusText) && /\b1\b/.test(statusText), statusText.replace(/\s+/g, ' ').slice(0, 80))
      const graphRow = page.locator('.scm-view .monaco-list-row, .monaco-list-row', { hasText: 'Test edit of the readme' }).first()
      await graphRow.waitFor({ timeout: 20_000 }).catch(() => {})
      check('the graph lists the new commit', await graphRow.isVisible())
      await page.screenshot({ path: join(results, 'git-committed.png') })

      // Branches
      await runCommand(page, 'Git: Create Branch...')
      await answer(page, 'feature/test')
      await page.waitForFunction(() => document.querySelector('.statusbar')?.textContent?.includes('feature/test'), undefined, { timeout: 30_000 })
      check('Create Branch switches the status bar branch', true)
      await runCommand(page, 'Git: Checkout to...')
      await page.waitForSelector('.quick-input-list .monaco-list-row')
      await page.waitForTimeout(2000)
      const branches = await page.locator('.quick-input-list .monaco-list-row').evaluateAll((els) => els.map((e) => e.textContent ?? ''))
      check('Checkout lists the branches', branches.some((b) => b.includes('master')) && branches.some((b) => b.includes('feature/test')), branches.join(' | '))
      await page.keyboard.press('Escape')

      // Zip export of the open project, then import
      const [download] = await Promise.all([page.waitForEvent('download', { timeout: 30_000 }), runCommand(page, 'WPILib: Export Project as Zip')])
      const zipPath = join(tmpdir(), `frc-test-${Date.now()}.zip`)
      await download.saveAs(zipPath)
      const names = Object.keys(unzipSync(new Uint8Array(readFileSync(zipPath))))
      check('exported zip holds the project files and its repository', names.includes('README') && names.includes('.git/HEAD'), names.slice(0, 5).join(', '))

      const chooserPromise = page.waitForEvent('filechooser', { timeout: 10_000 })
      await runCommand(page, 'WPILib: Import Project from Zip')
      const chooser = await chooserPromise
      const githubZip = join(tmpdir(), `Repo-main-${Date.now()}.zip`)
      const enc = (s) => new TextEncoder().encode(s)
      const { writeFileSync } = await import('node:fs')
      writeFileSync(githubZip, zipSync({ 'Repo-main/README.md': enc('# Repo'), 'Repo-main/src/A.java': enc('class A {}') }))
      await chooser.setFiles(githubZip)
      await quickInputReady(page)
      await page.keyboard.press('Enter')
      await explorerRow(page, 'README.md').waitFor({ timeout: 30_000 })
      check('the imported project is the workspace folder', await page.locator('.explorer-viewlet .pane-header', { hasText: /Repo-main/i }).first().isVisible())
      check('imported zip is unpacked into a new folder', (await readWorkspace(page, '/workspace/Repo-main/src/A.java')) === 'class A {}')
      check('single top-level folder is stripped', (await readWorkspace(page, '/workspace/Repo-main/Repo-main/README.md')) === null && (await readWorkspace(page, '/workspace/Repo-main/README.md')) === '# Repo')
      await context.close()
    }

    // An origin private file system folder stands in for the folder picker
    if (want('folder')) {
      const { context, page, seen } = await newPage(stubFolderPicker)
      await runCommand(page, 'File: Open Folder...')
      await page.waitForLoadState('load')
      await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
      await explorerRow(page, 'hello.txt').waitFor({ timeout: 30_000 })
      check('File: Open Folder opens a folder from disk', true)
      await explorerRow(page, 'hello.txt').dblclick()
      await page.waitForFunction(() => /from\sdisk/.test(document.querySelector('.editor-instance .view-lines')?.textContent ?? ''), undefined, { timeout: 15_000 })
      await page.keyboard.type('edited ')
      await page.keyboard.press('Control+s')
      await page.waitForTimeout(1500)
      check('saving writes through to the disk folder', ((await opfs(page, 'read', '/robot-folder/hello.txt')) ?? '').includes('edited'))

      await opfs(page, 'write', '/robot-folder/build.gradle', 'plugins { id "edu.wpi.first.GradleRIO" version "2026.2.1" }')
      await opfs(page, 'write', '/robot-folder/gradlew', '#!/bin/sh')
      // Zip export skips build output and keeps gradlew executable
      await opfs(page, 'write', '/robot-folder/build/classes/A.class', 'x')
      await opfs(page, 'write', '/robot-folder/.gradle/state', 'x')
      await opfs(page, 'write', '/robot-folder/bin/B.class', 'x')
      const [download] = await Promise.all([page.waitForEvent('download', { timeout: 30_000 }), runCommand(page, 'WPILib: Export Project as Zip')])
      const zipPath = join(tmpdir(), `frc-test-robot-${Date.now()}.zip`)
      await download.saveAs(zipPath)
      const bytes = new Uint8Array(readFileSync(zipPath))
      const names = Object.keys(unzipSync(bytes))
      check('export skips build, .gradle and bin', !names.some((n) => /^(build|\.gradle|bin)\//.test(n)) && names.includes('build.gradle'), `${names.length} files`)
      check('export keeps gradlew executable', gradlewIsExecutable(bytes))

      await runCommand(page, 'Git: Initialize Repository')
      await page.locator('.quick-input-list .monaco-list-row', { hasText: 'robot-folder' }).first().click()
      await page.waitForFunction(() => document.querySelector('.statusbar')?.textContent?.includes('main'), undefined, { timeout: 30_000 })
      check('Git works in a folder opened from disk', (await opfs(page, 'read', '/robot-folder/.git/HEAD'))?.includes('refs/heads/main') ?? false)
      await page.keyboard.press('Control+Shift+G')
      await page.locator('.scm-view .monaco-list-row[aria-label^="hello.txt,"]').first().waitFor({ timeout: 30_000 })
      check('disk folder files show as changes', true)
      await page.screenshot({ path: join(results, 'git-local-folder.png') })
      await setIdentity(page)
      await page.locator('.scm-editor .monaco-editor').first().click()
      await page.keyboard.type('First commit from disk')
      await page.keyboard.press('Control+Enter')
      // With nothing staged, VS Code offers to commit all changes
      await page.locator('.monaco-dialog-box').getByRole('button', { name: 'Yes' }).click({ timeout: 15_000 })
      await page.locator('.scm-view .monaco-list-row[aria-label^="hello.txt,"]').first().waitFor({ state: 'detached', timeout: 30_000 })
      check('a commit in a folder from disk is written to its .git', /^[0-9a-f]{40}$/.test((await opfs(page, 'read', '/robot-folder/.git/refs/heads/main'))?.trim() ?? ''))
      check('folders from disk use the real git', !seen.urls.some((u) => /isomorphic-git/.test(u)))

      await page.reload()
      await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
      await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
      await page.keyboard.press('Control+Shift+E')
      await explorerRow(page, 'hello.txt').waitFor({ timeout: 30_000 })
      check('the opened folder is restored after a reload', true)
      check('no GitHub requests were needed', seen.api.length === 0)

      await runCommand(page, 'File: Close Folder')
      await page.waitForFunction(() => localStorage.getItem('frc-dev-wasm.folder') === null, undefined, { timeout: 15_000 })
      await page.waitForLoadState('load')
      await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
      await page.keyboard.press('Control+Shift+E')
      await page.waitForTimeout(3000)
      check('Close Folder returns to the in-browser workspace', (await explorerRow(page, 'hello.txt').count()) === 0)
      await context.close()
    }

    // A browser without the File System Access API
    if (want('unsupported')) {
      const { context, page } = await newPage(() => {
        delete window.showDirectoryPicker
        delete Window.prototype.showDirectoryPicker
      })
      await runCommand(page, 'File: Open Folder...')
      await toast(page, 'cannot open folders from disk').waitFor({ timeout: 10_000 })
      check('unsupported browsers get a notification only when opening a folder', true)
      await context.close()
    }

    // VS Code's GitHub extension, against a stand-in for api.github.com
    if (want('github')) {
      const repo = { id: 1, name: 'robot', full_name: 'tester/robot', owner: { login: 'tester', type: 'User' }, clone_url: 'https://github.com/tester/robot.git', private: false, description: 'A robot', pushed_at: '2026-01-01T00:00:00Z', default_branch: 'main' }
      const { context, page } = await newPage(undefined, (route) => {
        const path = new URL(route.request().url()).pathname
        const body = path === '/user' ? { login: 'tester', id: 7 } : path === '/user/repos' ? [repo] : path.startsWith('/search/') ? { total_count: 1, items: [repo] } : {}
        return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(body) })
      })
      const cloneFromGitHub = async () => {
        for (let attempt = 0; ; attempt++) {
          try {
            await runCommand(page, 'Git: Clone')
            break
          } catch (error) {
            if (attempt >= 5) throw error
            await page.keyboard.press('Escape')
            await page.waitForTimeout(2000)
          }
        }
        await page.locator('.quick-input-list .monaco-list-row', { hasText: 'Clone from GitHub' }).first().click({ timeout: 15_000 })
      }
      // GitHub's device sign-in, through the proxy
      let polls = 0
      await page.route('https://frc-proxy.project516.dev/github/**', (route) => {
        const step = new URL(route.request().url()).pathname
        const body = step.endsWith('/device/code')
          ? { device_code: 'dc', user_code: 'ABCD-1234', verification_uri: 'https://github.com/login/device', expires_in: 900, interval: 1 }
          : ++polls < 2 ? { error: 'authorization_pending' } : { access_token: 'gho_test_token', token_type: 'bearer', scope: 'repo' }
        return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(body) })
      })
      context.on('page', (opened) => opened.close().catch(() => {}))
      await cloneFromGitHub()
      check('Clone offers to clone from GitHub', true)
      // As on the desktop, the extension asks before it signs in
      await page.locator('.monaco-dialog-box').getByRole('button', { name: 'Allow' }).click({ timeout: 15_000 })
      const codeDialog = page.locator('.monaco-dialog-box', { hasText: 'ABCD-1234' })
      await codeDialog.getByRole('button', { name: 'Copy Code and Open GitHub' }).click({ timeout: 15_000 })
      check('signing in shows the device code', true)
      const listed = page.locator('.quick-input-list .monaco-list-row', { hasText: 'tester/robot' }).first()
      await listed.waitFor({ timeout: 30_000 })
      check('the token from device sign-in is used', polls >= 2)
      check('signing in lists the account\'s repositories', true)
      await page.keyboard.press('Escape')
      await runCommand(page, 'Publish to GitHub')
      await page.keyboard.press('Escape')
      check('Publish to GitHub is a command', true)
      await context.close()
    }

    if (want('big')) {
      const { context, page } = await newPage()
      const name = BIG_REPO.split('/')[1]
      // GitHub refuses a token it does not accept even for a public repository, and desktop git sends
      // none until asked, so the clone works while a rejected token is saved
      for (let attempt = 0; ; attempt++) {
        try {
          await runCommand(page, 'Terminal: Create New Terminal')
          break
        } catch (error) {
          if (attempt >= 10) throw error
          await page.keyboard.press('Escape')
          await page.waitForTimeout(1500)
        }
      }
      const terminal = page.locator('.terminal-wrapper:visible .xterm-rows').first()
      await page.waitForFunction(() => /frc:\S*\$/.test(document.querySelector('.terminal-wrapper:not([style*="display: none"]) .xterm-rows')?.textContent ?? ''), undefined, { timeout: 60_000 })
      await terminal.click({ force: true })
      await page.keyboard.type('echo gho_rejected | gh auth login --with-token', { delay: 4 })
      await page.keyboard.press('Enter')
      await page.waitForFunction(() => document.querySelector('.terminal-wrapper:not([style*="display: none"]) .xterm-rows')?.textContent?.includes('Token saved'), undefined, { timeout: 30_000 })
      await clone(page, BIG_REPO)
      await explorerRow(page, 'build.gradle').waitFor({ timeout: 900_000 })
      await page.waitForFunction(() => document.querySelector('.statusbar')?.textContent?.includes('main'), undefined, { timeout: 120_000 })
      const remoteHead = await refOf(page, name, 'refs/remotes/origin/main')
      check(`${BIG_REPO} clone checks out the remote branch`, !!remoteHead && (await refOf(page, name, 'refs/heads/main')) === remoteHead, remoteHead)
      check(`${BIG_REPO} files appear in the explorer`, true)
      await page.keyboard.press('Control+Shift+G')
      await page.waitForTimeout(8000)
      const changed = await page.locator('.scm-view .monaco-list-row').evaluateAll((rows) => rows.filter((r) => /Modified|Untracked|Deleted|Added/.test(r.getAttribute('aria-label') ?? '')).length)
      check(`${BIG_REPO} working tree is clean after the clone`, changed === 0, `${changed} changed files`)
      await page.screenshot({ path: join(results, 'git-big-clone.png') })
      await runCommand(page, 'WPILib: Open Robot Project')
      await page.waitForSelector('.quick-input-list .monaco-list-row')
      const items = await page.locator('.quick-input-list .monaco-list-row').evaluateAll((els) => els.map((e) => e.textContent ?? ''))
      check('WPILib recognizes the cloned project', items.some((i) => i.includes(name) && i.includes('(open)')), items.join(' | '))
      await page.keyboard.press('Escape')
      await context.close()
    }
  } catch (error) {
    await lastPage?.screenshot({ path: join(results, 'git-failure.png') }).catch(() => {})
    check('browser test completed', false, error instanceof Error ? error.message.split('\n')[0] : String(error))
    console.error(error)
  } finally {
    if (errors.length > 0) console.log(`browser errors (${errors.length}):\n${errors.slice(0, 15).join('\n')}`)
    await browser.close()
    await server.close()
  }
}

// Reads the central directory: the entry for gradlew must be from Unix (3) with mode bits 0o755
function gradlewIsExecutable(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  for (let i = bytes.length - 22; i >= 0; i--) {
    if (view.getUint32(i, true) !== 0x06054b50) continue
    let offset = view.getUint32(i + 16, true)
    for (let n = view.getUint16(i + 10, true); n > 0; n--) {
      const nameLength = view.getUint16(offset + 28, true)
      const extra = view.getUint16(offset + 30, true)
      const comment = view.getUint16(offset + 32, true)
      const name = new TextDecoder().decode(bytes.subarray(offset + 46, offset + 46 + nameLength))
      if (name === 'gradlew') return view.getUint8(offset + 5) === 3 && (view.getUint32(offset + 38, true) >>> 16) === 0o100755
      offset += 46 + nameLength + extra + comment
    }
  }
  return false
}

// Stands in for showDirectoryPicker with an origin private file system folder. Chromium's headless
// builds crash when such a handle is read back from IndexedDB, so a marker is stored in its place
// and swapped for the real handle when the workbench reads it.
function stubFolderPicker() {
  delete window.FileSystemObserver
  const handles = new Map()
  navigator.storage
    .getDirectory()
    .then((root) => root.getDirectoryHandle('robot-folder'))
    .then((handle) => handles.set('robot-folder', handle))
    .catch(() => {})
  const put = IDBObjectStore.prototype.put
  IDBObjectStore.prototype.put = function (value, key) {
    return put.call(this, value instanceof FileSystemHandle ? { opfsHandle: value.name } : value, key)
  }
  const get = IDBObjectStore.prototype.get
  const result = Object.getOwnPropertyDescriptor(IDBRequest.prototype, 'result').get
  IDBObjectStore.prototype.get = function (key) {
    const request = get.call(this, key)
    Object.defineProperty(request, 'result', {
      get() {
        const value = result.call(this)
        return value?.opfsHandle ? handles.get(value.opfsHandle) : value
      }
    })
    return request
  }
  window.showDirectoryPicker = async () => {
    const root = await navigator.storage.getDirectory()
    const dir = await root.getDirectoryHandle('robot-folder', { create: true })
    const file = await dir.getFileHandle('hello.txt', { create: true })
    const writable = await file.createWritable()
    await writable.write('from disk')
    await writable.close()
    handles.set('robot-folder', dir)
    return dir
  }
}
