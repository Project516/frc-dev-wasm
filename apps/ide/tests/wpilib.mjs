// Drives the WPILib extension's UI in browser tests: the command palette, the project creator
// webview and the simulation extension picker.
import { DEFAULT_YEAR, WPILIB_YEARS } from '../../../packages/runtime/src/years.ts'

// Extensions register their commands once the workbench has restored, so a command can take a
// moment to show up in the palette
export async function runCommand(page, title, timeout = 60_000) {
  const row = page.locator('.quick-input-list .monaco-list-row', { hasText: title }).first()
  for (const start = Date.now(); ; ) {
    await declineSubfolderProjects(page)
    // Take focus back from tool iframes so F1 reaches the workbench
    await page.evaluate(() => (document.activeElement instanceof HTMLElement ? document.activeElement.blur() : undefined))
    await page.keyboard.press('F1')
    await page.keyboard.type(`> ${title}`)
    try {
      // Fuzzy matching can rank another command first, so pick the row by its title
      await row.waitFor({ timeout: 5_000 })
      await page.waitForTimeout(300)
      // A webview or task terminal that opens meanwhile can take the focus and close the palette
      await row.click({ timeout: 5_000 })
      // A busy page can drop the click, leaving the palette open and the command not run
      await row.waitFor({ state: 'hidden', timeout: 15_000 })
      return
    } catch (error) {
      if (Date.now() - start > timeout) throw error
      await page.keyboard.press('Escape')
    }
  }
}

// Opening a folder that holds WPILib projects asks, in a modal, whether to open one instead
const SUBFOLDER_PROJECTS = /Incorrect folder opened for WPILib project/

async function declineSubfolderProjects(page) {
  const dialog = page.locator('.monaco-dialog-box', { hasText: SUBFOLDER_PROJECTS })
  if (await dialog.isVisible()) await dialog.locator('.monaco-button', { hasText: /^No$/ }).click()
}

// What the quick input holds, for failures: every widget with its visibility, title and placeholder
export async function quickInputState(page) {
  return page.evaluate(() => ({
    widgets: [...document.querySelectorAll('.quick-input-widget')].map((w) => ({
      display: w.style.display,
      class: w.className,
      visible: w.offsetParent !== null,
      title: w.querySelector('.quick-input-title')?.textContent,
      placeholder: w.querySelector('input')?.placeholder,
      value: w.querySelector('input')?.value,
    })),
    active: document.activeElement?.outerHTML.slice(0, 200),
  }))
}

async function pick(page, label) {
  const row = page.locator('.quick-input-list .monaco-list-row', { hasText: label }).first()
  await row.waitFor({ timeout: 15_000 })
  await page.waitForTimeout(300)
  await row.click()
}

// Opens the project creator. The subfolder question can come up meanwhile and keep it closed. With
// `year`, answers the season question (asked when the site serves more than one); another season's
// extension loads after a reload.
async function openCreator(page, year) {
  for (const start = Date.now(); Date.now() - start < 90_000; ) {
    await runCommand(page, 'WPILib: Create a new project')
    if (year) await pick(page, `WPILib ${year}`)
    for (const opened = Date.now(); Date.now() - opened < (year ? 120_000 : 15_000); await page.waitForTimeout(250)) {
      await declineSubfolderProjects(page)
      for (const frame of page.frames()) if (await frame.locator('#projectType, #language-select').count().catch(() => 0)) return frame
    }
  }
  throw new Error('the project creator did not open')
}

// Creates a project in /workspace with the WPILib project creator and opens it, which reloads the page.
// `base` is a template (Command Robot) or, with `example`, an example. `year` picks the season (the default
// one when the site serves more than one). `onOpened` is called when the new project is open, before its first build.
export async function createProject(page, { name, base, team = 1234, example = false, year = WPILIB_YEARS.length > 1 ? DEFAULT_YEAR : undefined, onOpened }) {
  const creator = await openCreator(page, year)
  // VS Code rewrites a loaded iframe with document.write, so Playwright's load state can
  // describe the empty page. Wait for this document, whose load event attaches the handlers.
  await creator.waitForFunction(() => document.readyState === 'complete')
  // 2027's creator is a four step wizard with selects, 2026's asks through quick picks
  const wizard = (await creator.locator('#language-select').count()) > 0
  if (wizard) {
    // On a busy page a click can still land before the handlers are attached and do nothing, so the card is clicked
    // until it enables Next
    const card = creator.locator(`.selection-card[data-value="${example ? 'Example' : 'Template'}"]`)
    for (let attempt = 0; ; attempt++) {
      await card.click()
      try {
        await creator.waitForFunction(() => document.getElementById('next-to-step-2')?.disabled === false, undefined, { timeout: 5_000 })
        break
      } catch (error) {
        if (attempt >= 11) throw error
      }
    }
    await creator.locator('#next-to-step-2').click()
    await creator.locator('#language-select').selectOption('java')
    await creator.locator('#base-select').selectOption(base)
    await creator.locator('#next-to-step-3').click()
  } else {
    await creator.locator('#projectType').click()
    // Each choice asks for the next one right away
    await pick(page, example ? 'Example' : 'Template')
    await pick(page, 'java')
    await page.locator('.quick-input-widget input').fill(base)
    await pick(page, base)
  }
  await creator.locator('#projectSelectButton').click()
  // The folder dialog starts in the browser workspace, not in the open project
  await page.waitForFunction(() => document.querySelector('.quick-input-widget input')?.value === '/workspace/', undefined, { timeout: 15_000 })
  await page.locator('.quick-input-widget .monaco-button', { hasText: 'Select Folder' }).click()
  await creator.locator('#projectName').fill(name)
  await creator.locator('#teamNumber').fill(String(team))
  await creator.locator('#desktopCB').check({ force: wizard })
  if (wizard) await creator.locator('#next-to-step-4').click()
  await creator.locator('#generateProject').click()
  await page.locator('.monaco-dialog-box .monaco-button', { hasText: 'Yes (Current Window)' }).click({ timeout: 60_000 })
  await page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'build.gradle' }).first().waitFor({ timeout: 120_000 })
  await page.locator('.explorer-viewlet .pane-header', { hasText: new RegExp(`^${name}$`, 'i') }).first().waitFor({ timeout: 10_000 })
  onOpened?.()
  // A new project builds once when it opens, as on the desktop. A build that cannot start ends
  // without a BUILD line.
  await waitForTerminal(page, /BUILD (SUCCESSFUL|FAILED)|terminated with exit code/, 300_000)
}

// Waits until the terminal on show matches. Gradle tasks show their task terminal, the robot program
// the WPILib terminal.
// Returns the matched text, read from the visible rows, which later output can scroll away
export async function waitForTerminal(page, pattern, timeout = 120_000) {
  const match = await page.waitForFunction(
    (source) => document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent?.match(new RegExp(source))?.[0],
    pattern.source,
    { timeout }
  )
  return match.jsonValue()
}

// WPILib: Simulate Robot Code. The extension picker starts with what build.gradle enables; `gui` ticks
// or unticks the Sim GUI. `pick: false` is for a project whose settings skip the picker
// (wpilib.skipSelectSimulateExtension).
export async function simulate(page, { gui, pickerTimeout = 300_000, pick = true } = {}) {
  await runCommand(page, 'WPILib: Simulate Robot Code')
  if (!pick) return
  const picker = page.locator('.quick-input-widget input[placeholder="Pick extensions to run"]')
  await picker.waitFor({ timeout: pickerTimeout })
  await page.waitForTimeout(300)
  if (gui !== undefined) {
    const row = page.locator('.quick-input-list .monaco-list-row', { hasText: 'Sim GUI' }).first()
    if ((await row.getAttribute('aria-checked')) !== String(gui)) await row.click()
    await page.waitForFunction(
      (want) => [...document.querySelectorAll('.quick-input-list .monaco-list-row')].find((r) => r.textContent.includes('Sim GUI'))?.getAttribute('aria-checked') === want,
      String(gui),
      { timeout: 5_000 }
    )
  }
  await page.keyboard.press('Enter')
}

// Selects Teleoperated in the 2026 Sim GUI's Robot State window, by clicking down its entries until the
// mode changes to it. The GUI draws smaller when the browser's screen is smaller than its default window,
// so the row's position depends on the browser.
export async function enableTeleoperated(page, frameHandle, gui) {
  const box = await frameHandle.boundingBox()
  const mode = async () => {
    const s = await gui.evaluate(() => JSON.parse(Module.ccall('frc_sim_state', 'string', [], [])))
    return { ...s, key: [s.ds, s.enabled, s.autonomous, s.test].join() }
  }
  let state = await mode()
  for (let y = 64; y <= 124; y += 8) {
    const before = state.key
    await page.mouse.click(box.x + 48, box.y + y)
    // A click on the row already selected changes nothing, so this ends by timeout there
    for (const start = Date.now(); Date.now() - start < 5_000 && state.key === before; ) {
      await page.waitForTimeout(100)
      state = await mode()
    }
    if (state.ds && state.enabled && !state.autonomous && !state.test) break
  }
  return state
}

// WPILib: Build Robot Code, until its task terminal (a new one, not an earlier build's) has finished
export async function buildCode(page, timeout = 300_000) {
  await page.evaluate(() => document.querySelectorAll('.terminal-wrapper').forEach((wrapper) => (wrapper.dataset.earlier = 'true')))
  await runCommand(page, 'WPILib: Build Robot Code')
  await page.waitForFunction(
    () => {
      const wrapper = document.querySelector('.terminal-wrapper.active')
      return wrapper && !wrapper.dataset.earlier && /BUILD (SUCCESSFUL|FAILED)|terminated with exit code/.test(wrapper.querySelector('.xterm-rows')?.textContent ?? '')
    },
    undefined,
    { timeout }
  )
}

const shellScreen = (page) =>
  page.locator('.terminal-wrapper:visible .xterm-rows').first().innerText({ timeout: 5000 }).catch(() => '')

// Runs a command in a new FRC Shell and returns its output once the prompt is back
export async function shell(page, command, timeout = 240_000) {
  await runCommand(page, 'Terminal: Create New Terminal')
  // A task terminal that opens meanwhile can take the focus
  await page.locator('.terminal-tabs-entry', { hasText: 'FRC Shell' }).last().click({ timeout: 5000 }).catch(() => {})
  await page.waitForFunction(() => /frc:\S*\$\s*$/.test([...document.querySelectorAll('.terminal-wrapper')].find((w) => w.offsetParent)?.querySelector('.xterm-rows')?.innerText ?? ''), undefined, { timeout: 120_000 })
  await page.waitForFunction(() => {
    const input = [...document.querySelectorAll('.terminal-wrapper')].find((w) => w.offsetParent)?.querySelector('.xterm-helper-textarea')
    input?.focus()
    return !!input && document.activeElement === input
  }, undefined, { timeout: 10_000 })
  await page.keyboard.type(command, { delay: 4 })
  await page.keyboard.press('Enter')
  for (const start = Date.now(); Date.now() - start < timeout; await page.waitForTimeout(200)) {
    const text = await shellScreen(page)
    const output = text.slice(text.lastIndexOf(command) + command.length)
    if (/frc:\S*\$\s*$/.test(output) && output.includes('\n')) return output.replace(/frc:\S*\$\s*$/, '').trim()
  }
  throw new Error(`no prompt after ${command}`)
}
