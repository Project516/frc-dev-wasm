// Opens the FRC shell terminal and runs commands in it. Needs pnpm fetch-deps, build:java and
// build:terminal, and a build of the app (pnpm build). TERMINAL_URL points at a running server.
import { existsSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { createProject, runCommand as runWpilibCommand } from './wpilib.mjs'
import { preview } from 'vite'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok || !detail ? '' : `: ${detail}`}`)
  if (!ok) failures.push(name)
}

if (!existsSync(join(root, 'dist/runtime/terminal/coreutils.wasm')) && !process.env.TERMINAL_URL) {
  console.error('dist/runtime/terminal/coreutils.wasm is missing: run pnpm build:terminal, then pnpm build')
  process.exit(1)
}

const server = process.env.TERMINAL_URL ? undefined : await preview({ root, preview: { port: 4175, strictPort: false, open: false } })
const url = process.env.TERMINAL_URL ?? server.resolvedUrls?.local[0] ?? 'http://localhost:4175/'
const browser = await chromium.launch({ headless: true })
const page = await (await browser.newContext({ viewport: { width: 1400, height: 900 } })).newPage()
const errors = []
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 300)}`))

const screen = () => page.locator('.terminal-wrapper:visible .xterm-rows').first().innerText()
const PROMPT = /frc:\S*\$\s*$/

const runCommand = (title) => runWpilibCommand(page, title)

const focusTerminal = () => page.locator('.terminal-wrapper:visible .xterm-rows').first().click({ force: true })

async function maximizePanel(want) {
  const maximized = !(await page.locator('.part.editor').isVisible())
  if (maximized !== want) await runCommand('View: Toggle Maximized Panel')
  await page.waitForTimeout(500)
}

// Types a command and returns what it printed, once the prompt is back
async function sh(command, timeout = 60_000) {
  await page.keyboard.type(command, { delay: 4 })
  await page.keyboard.press('Enter')
  const start = Date.now()
  for (;;) {
    const text = await screen()
    const echo = text.lastIndexOf(command)
    if (echo >= 0) {
      const output = text.slice(echo + command.length)
      if (PROMPT.test(output) && output.includes('\n')) return output.replace(PROMPT, '').trim()
    } else if (PROMPT.test(text) && Date.now() - start > 3000) {
      // Long output scrolled the command out of view
      return text.replace(PROMPT, '').trim()
    }
    if (Date.now() - start > timeout) throw new Error(`no prompt after "${command}": ${JSON.stringify(text.slice(-600))}`)
    await page.waitForTimeout(150)
  }
}

async function expectOutput(name, command, expected, timeout) {
  const output = await sh(command, timeout)
  const ok = expected instanceof RegExp ? expected.test(output) : output === expected
  check(name, ok, `${command} -> ${JSON.stringify(output.slice(0, 400))}`)
  return output
}

// Opens a new FRC shell, maximizes the panel and focuses it
async function openShell() {
  // The profile is listed shortly after the extension starts, and the command is hidden until then
  for (let attempt = 0; ; attempt++) {
    try {
      await runCommand('Terminal: Create New Terminal')
      break
    } catch (error) {
      if (attempt >= 10) throw error
      await page.keyboard.press('Escape')
      await page.waitForTimeout(1500)
    }
  }
  await page.waitForFunction(
    () => [...document.querySelectorAll('.terminal-wrapper .xterm-rows')].some((r) => r.textContent?.includes('FRC Dev shell')),
    undefined,
    { timeout: 30_000 }
  )
  await maximizePanel(true)
  await focusTerminal()
  await page.waitForFunction(() => /frc:\S*\$/.test(document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? ''))
}

const explorerRow = (name) => page.locator('.explorer-viewlet .monaco-list-row', { hasText: new RegExp(`^\\s*${name}\\s*$`) }).first()

try {
  await page.goto(url)
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })

  await openShell()
  check('a new terminal opens the FRC shell by default', true)

  await expectOutput('pwd prints the workspace folder', 'pwd', '/workspace')
  await expectOutput('ls works on an empty workspace', 'ls', '')
  await expectOutput('mkdir, redirect and cat', 'mkdir -p a/b && echo hi > a/b/f.txt && cat a/b/f.txt', 'hi')
  await explorerRow('a').waitFor({ timeout: 15_000 })
  check('files made in the shell show in the explorer', true)
  await expectOutput('wc counts lines', 'wc -l a/b/f.txt', /^1 a\/b\/f\.txt$/)
  await expectOutput('a pipe feeds the next command', 'cat a/b/f.txt | wc -c', /^3$/)
  await expectOutput('cd changes the directory', 'cd a/b && pwd', '/workspace/a/b')
  const prompt = (await screen()).trimEnd().split('\n').pop()
  check('the prompt shows the path', prompt.includes('frc:~/a/b$'), JSON.stringify(prompt))
  await expectOutput('ls lists the directory', 'ls', 'f.txt')
  await expectOutput('ls with an absolute path', 'ls /workspace/a', 'b')
  await expectOutput('relative parent paths', 'cat ../b/f.txt', 'hi')
  await expectOutput('cp, mv and a glob', 'cp f.txt g.txt && mv g.txt h.txt && ls *.txt', /^f\.txt\s+h\.txt$/)
  await expectOutput('rm removes a file', 'rm h.txt && ls', 'f.txt')
  await expectOutput('appending with >>', 'echo more >> f.txt && cat f.txt', 'hi\nmore')
  await expectOutput('stdin redirect', 'wc -l < f.txt', /^2$/)
  await expectOutput('quoting and variables', `export X=1 && echo $X "a  b" 'c $X'`, /^1 a\s+b c \$X$/)
  await expectOutput('stderr goes to the terminal and && stops', 'cat nope && echo after', /No such file/)
  await expectOutput('|| runs after a failure', 'cat nope 2>/dev/null || echo fallback', 'fallback')
  await expectOutput('command substitution', 'echo $(pwd)', '/workspace/a/b')
  await expectOutput('sort and uniq', 'printf "b\\na\\nb\\n" | sort | uniq -c', /2 b[\s\S]*1 a|1 a[\s\S]*2 b/)
  await expectOutput('grep finds a line', 'grep -n more f.txt', '2:more')
  await expectOutput('grep -r searches below', 'cd /workspace && grep -r hi a', 'a/b/f.txt:hi')
  await expectOutput('find by name', 'find . -name "*.txt"', './a/b/f.txt')
  await expectOutput('cp -r copies a directory', 'cp -r a a2 && ls a2/b', 'f.txt')
  await expectOutput('ls -R without an operand lists below the working directory', 'cd a && ls -R', /f\.txt/)
  await sh('cd /workspace')
  await expectOutput('cp -r src/. merges into an existing directory', 'mkdir -p cpsrc cpdst && echo n > cpsrc/new && echo e > cpdst/extra && cp -r cpsrc/. cpdst && ls cpdst', /^extra\s+new$/)
  await expectOutput('mv moves a directory with everything in it', 'mkdir -p mvsrc/sub && echo m > mvsrc/sub/f && mv mvsrc mvdone && cat mvdone/sub/f && ls', /^m\n[\s\S]*mvdone/)
  await expectOutput('mv onto a non-empty directory is refused', 'mkdir mvdst && echo k > mvdst/keep && mv -T mvdone mvdst', /Directory not empty/)
  await expectOutput('the refused mv lost nothing', 'cat mvdst/keep && cat mvdone/sub/f', /^k\nm$/)
  await expectOutput('rm -rf removes a tree', 'rm -rf mvdone mvdst && ls', /^(?!.*mv(done|dst))/s)
  await expectOutput('rm -r refuses /', 'rm -rf /', /dangerous/)
  await expectOutput('find -delete removes only the matches', 'mkdir -p fd/sub && echo 1 > fd/x.txt && echo 2 > fd/keep.md && find fd -name "*.txt" -delete && ls fd', /^keep\.md\s+sub$/)
  await expectOutput('dirname keeps its text operand', 'dirname /a/b/c', '/a/b')
  await sh('echo keep > cap.txt')
  await expectOutput('a command cut off at the output limit says so', 'yes > cap.txt', /cut off/, 120_000)
  await expectOutput('and its output is not written', 'wc -c < cap.txt', '0')
  await expectOutput('du summarizes', 'du -s a', /^\d+\s+a$/)
  await expectOutput('an unknown command is reported', 'nosuchcommand', /command not found/)

  // history and completion
  await page.keyboard.press('ArrowUp')
  await page.waitForTimeout(150)
  check('the up arrow recalls the last command', (await screen()).trimEnd().split('\n').pop().includes('nosuchcommand'))
  await page.keyboard.press('Control+U')
  await page.keyboard.type('cat a/b/f.t')
  await page.keyboard.press('Tab')
  await page.waitForTimeout(500)
  check('Tab completes a path', (await screen()).trimEnd().split('\n').pop().includes('cat a/b/f.txt'), (await screen()).trimEnd().split('\n').pop())
  await page.keyboard.press('Control+U')

  // A paste with a blank line runs every line, in order
  await page.evaluate((text) => {
    const area = document.querySelector('.terminal-wrapper.active textarea.xterm-helper-textarea')
    const data = new DataTransfer()
    data.setData('text/plain', text)
    area.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }))
  }, 'printf "q%s\\n" 1\n\nprintf "q%s\\n" 2\n')
  await page.waitForFunction(() => /q1[\s\S]*q2[\s\S]*frc:\S*\$/.test(document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? ''), undefined, { timeout: 30_000 })
  check('a pasted blank line does not stop the lines after it', true)

  // Ctrl+C
  await page.keyboard.type('sleep 60')
  await page.keyboard.press('Enter')
  await page.waitForTimeout(800)
  await page.keyboard.press('Control+C')
  await page.waitForFunction(() => /\^C[\s\S]*frc:\S*\$/.test(document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? ''), undefined, { timeout: 10_000 })
  check('Ctrl+C interrupts a running command', true)
  await expectOutput('the shell works after an interrupt', 'echo alive', /alive/)

  // git, the real program in WebAssembly
  await sh('mkdir /workspace/repo && cd /workspace/repo')
  await expectOutput('git init', 'git init', /Initialized empty Git repository in \/workspace\/repo\/\.git\//)
  await sh('echo one > a.txt')
  await expectOutput('git status lists untracked files', 'git status', /On branch master[\s\S]*Untracked files:[\s\S]*a\.txt/)
  await sh('git config user.name Tester && git config user.email tester@example.com')
  await sh('git add .')
  await expectOutput('git status shows staged files', 'git status -s', 'A  a.txt')
  await expectOutput('git commit', 'git commit -m first', /\[master \(root-commit\) [0-9a-f]{7}\] first/)
  await expectOutput('git log', 'git log --oneline', /first$/)
  await sh('echo two >> a.txt')
  await expectOutput('git status shows modified files', 'git status -s', /^M a\.txt$/)
  await expectOutput('git diff prints a patch', 'git diff', /^@@ -1 \+1,2 @@\n one\n\+two$/m)
  await expectOutput('git commit -am', 'git commit -am second', /\[master [0-9a-f]{7}\] second/)
  await sh('git branch feature')
  await expectOutput('git checkout switches branch', 'git checkout feature', /Switched to branch 'feature'/)
  await expectOutput('git branch lists branches', 'git branch', /\* feature[\s\S]*master/)
  await expectOutput('git restore discards changes', 'echo junk >> a.txt && git restore a.txt && cat a.txt', 'one\ntwo')
  await expectOutput('restore uses the staged version, not HEAD', 'echo three >> a.txt && git add a.txt && echo four >> a.txt && git restore a.txt && cat a.txt', 'one\ntwo\nthree')
  await expectOutput('git commit --amend', 'git commit --amend -m amended', /\[feature [0-9a-f]{7}\] amended/)
  await expectOutput('git reset HEAD~1', 'git reset HEAD~1', /Unstaged changes after reset:\s+M\s+a\.txt/)
  await expectOutput('git stash', 'git stash && git stash list', /stash@\{0\}: WIP on feature/)
  await expectOutput('git stash pop restores the change', 'git stash pop >/dev/null && cat a.txt', 'one\ntwo\nthree')
  await expectOutput('git restore --source', 'git restore --source=HEAD a.txt && cat a.txt', 'one')
  await expectOutput('git -C runs in another directory', 'cd /workspace && git -C repo log --oneline -1 && cd repo', /first$/)
  await expectOutput('git push of a missing branch fails as git does', 'git push origin main', /error: src refspec main does not match any/)
  await sh('echo five >> a.txt && git commit -am third')
  await sh('git checkout master')
  await expectOutput('git branch -d refuses an unmerged branch', 'git branch -d feature', /not fully merged/)
  await expectOutput('git branch -D deletes it', 'git branch -D feature', /Deleted branch feature/)
  await sh('cd /workspace')

  // A new project becomes the workspace folder, which reloads the page and starts the shell in it.
  // The project creator opens in the editor area.
  await maximizePanel(false)
  await createProject(page, { name: 'MyRobot', base: 'Command Robot' })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
  await openShell()
  await expectOutput('the shell starts in the project folder', 'pwd', '/workspace/MyRobot')

  await maximizePanel(false)
  await explorerRow('build.gradle').click()
  const titled = await page.waitForFunction(() => document.title === 'FRC Dev: build.gradle', undefined, { timeout: 15_000 }).then(() => true, () => false)
  check('the window title is "FRC Dev: <editor>"', titled, await page.title())
  const shells = () => [...document.querySelectorAll('.terminal-wrapper .xterm-rows')].filter((r) => r.textContent?.includes('FRC Dev shell')).length
  const before = await page.evaluate(shells)
  await explorerRow('src').click({ button: 'right' })
  await page.locator('.context-view .action-item', { hasText: 'Open in Integrated Terminal' }).hover()
  await page.keyboard.press('Enter')
  await page.waitForFunction(`(${shells})() > ${before}`, undefined, { timeout: 30_000 })
  await maximizePanel(true)
  await focusTerminal()
  await expectOutput('Open in Integrated Terminal starts the shell in that folder', 'pwd', '/workspace/MyRobot/src')
  await page.keyboard.type('exit')
  await page.keyboard.press('Enter')
  await page.waitForFunction(`(${shells})() === ${before}`, undefined, { timeout: 10_000 })

  await maximizePanel(false)
  await runCommand('Source Control: Focus on Changes View')
  const initButton = page.locator('.welcome-view-content a, .welcome-view-content .monaco-button', { hasText: 'Initialize Repository' }).first()
  // Shown once the Git extension has finished its first scan
  await initButton.waitFor({ timeout: 60_000 })
  check('Source Control offers to initialize a repository in a project without one', true)
  await initButton.click()
  await page.locator('.scm-view .scm-editor, .scm-view .monaco-list-row', { hasText: /MyRobot|Changes|Message/ }).first().waitFor({ timeout: 20_000 })
  check('Initialize Repository registers the project with Source Control', await page.locator('.welcome-view-content', { hasText: 'Initialize Repository' }).count() === 0)
  await runCommand('View: Show Explorer')
  await maximizePanel(true)
  await focusTerminal()
  await expectOutput('gradlew with no task lists the tasks', './gradlew', /Supported tasks:[\s\S]*simulateJava/)
  await expectOutput('an unsupported task is explained', './gradlew deploy', /Task 'deploy' is not supported in the browser/)
  await expectOutput('gradlew build compiles the project', './gradlew build', /> Task :compileJava[\s\S]*BUILD SUCCESSFUL/, 240_000)
  await expectOutput('a build with nothing changed is up to date', './gradlew build', /> Task :compileJava[\s\S]*UP-TO-DATE[\s\S]*BUILD SUCCESSFUL/, 120_000)
  const compiles = /^(?![\s\S]*UP-TO-DATE)[\s\S]*> Task :compileJava[\s\S]*BUILD SUCCESSFUL/
  await expectOutput('an edited source compiles again', 'echo "// edited" >> $(find src -name Robot.java) && ./gradlew build', compiles, 240_000)
  await expectOutput('clean makes the next build compile again', './gradlew clean build', compiles, 240_000)
  await page.keyboard.type('echo "// again" >> $(find src -name Robot.java) && ./gradlew build', { delay: 4 })
  await page.keyboard.press('Enter')
  await page.waitForFunction(() => /again[\s\S]*> Task :compileJava/.test(document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? ''), undefined, { timeout: 60_000 })
  await openShell()
  await expectOutput('a build started while the same sources compile waits for them and is up to date', 'cd /workspace/MyRobot && ./gradlew build', /> Task :compileJava[\s\S]*UP-TO-DATE[\s\S]*BUILD SUCCESSFUL/, 240_000)
  await page.keyboard.type('exit')
  await page.keyboard.press('Enter')
  await page.waitForFunction(() => /BUILD SUCCESSFUL[^]*frc:\S*\$\s*$/.test(document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? ''), undefined, { timeout: 240_000 })

  await maximizePanel(false)
  await focusTerminal()
  await page.keyboard.type('./gradlew simulateJava')
  await page.keyboard.press('Enter')
  await page.waitForSelector('iframe[title="Simulation GUI"]', { timeout: 300_000 })
  await maximizePanel(true)
  await page.waitForFunction(
    () => (document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? '').includes('Robot program startup complete'),
    undefined,
    { timeout: 300_000 }
  )
  check('gradlew simulateJava streams the robot output into the shell', true)
  await page.screenshot({ path: join(results, 'terminal.png') })
  await focusTerminal()
  await page.keyboard.press('Control+C')
  await page.waitForFunction(() => /\^C[\s\S]*frc:\S*\$\s*$/.test(document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? ''), undefined, { timeout: 60_000 })
  check('Ctrl+C stops the simulation and returns the prompt', true)

  await focusTerminal()
  await page.keyboard.type('./gradlew build')
  await page.keyboard.press('Enter')
  await page.waitForTimeout(800)
  await page.keyboard.press('Control+C')
  await page.waitForFunction(() => /\^C[\s\S]*frc:\S*\$\s*$/.test(document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? ''), undefined, { timeout: 10_000 })
  await page.waitForTimeout(45_000)
  const afterInterrupt = (await screen()).split('^C').pop()
  check('output stops after Ctrl+C gave the prompt back', !afterInterrupt.includes('BUILD') && PROMPT.test(afterInterrupt), JSON.stringify(afterInterrupt.slice(-200)))

  await sh('cd /workspace')
  await expectOutput('files from the template are visible to ls', 'ls MyRobot', /build\.gradle/)

  await page.keyboard.type('exit')
  await page.keyboard.press('Enter')
  await page.waitForFunction(() => ![...document.querySelectorAll('.terminal-wrapper .xterm-rows')].some((r) => r.textContent?.includes('FRC Dev shell')), undefined, { timeout: 10_000 })
  check('exit closes the terminal', true)
} catch (error) {
  failures.push('exception')
  console.error(error)
  await page.screenshot({ path: join(results, 'terminal-failure.png') }).catch(() => {})
} finally {
  if (errors.length > 0) console.log(`browser errors (${errors.length}):\n${errors.slice(0, 15).join('\n')}`)
  await browser.close()
  await server?.close()
}

if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
