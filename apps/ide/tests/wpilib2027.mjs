// Creates, builds and simulates WPILib 2027 projects (Timed, Command v2 and Command v3 robots) on java-web,
// with the WebAssembly Sim GUI. It needs a build (pnpm build), java-web built (pnpm build:java-web) and the
// 2027 runtime (fetch-deps, build:java and build:vscode-wpilib).
// It skips with a message when one of them is missing. ONLY=<names> runs some of the projects. SMOKE_URL=<site> runs it against a deployed site.
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { JAVA_WEB_YEARS, WPILIB_YEARS } from '../../../packages/runtime/src/years.ts'
import { buildCode, createProject, runCommand, simulate } from './wpilib.mjs'
import { preview } from 'vite'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

const YEAR = '2027'
// Output of fixtures/DrawCheck.java. The shapes lines are HotSpot's pixels. The font and text lines are java-web's
// own, from its bundled DejaVu Sans, and match what it prints under Node.
const DRAW_CHECK = `shapes INT_ARGB feec96c7
shapes INT_RGB feec96c7
shapes 3BYTE_BGR feec96c7
shapes BYTE_GRAY b3f6230c
shapes BYTE_INDEXED e3b5954b
font SansSerif.plain ascent 18.564453 descent 4.716797 width 116.640625
text 2960d921`
if (!WPILIB_YEARS.includes(YEAR) || !JAVA_WEB_YEARS.includes(YEAR)) {
  console.log(`skipped: ${YEAR} is not in WPILIB_YEARS (packages/runtime/src/years.ts)`)
  process.exit(0)
}
const needed = ['runtime/java-web/java-web.js', 'runtime/java-web/boot.pack', `runtime/${YEAR}/manifest.json`, `runtime/${YEAR}/vscode-wpilib/dist/browser/extension.js`]
const missing = process.env.SMOKE_URL ? [] : needed.filter((file) => !existsSync(join(root, 'dist', file)))
if (missing.length > 0) {
  console.log(`skipped: apps/ide/dist lacks ${missing.join(', ')}; run the builds in the header of this file, then pnpm build`)
  process.exit(0)
}

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`)
  if (!ok) failures.push(name)
}
const seconds = (since) => `${((Date.now() - since) / 1000).toFixed(1)} s`

const server = process.env.SMOKE_URL ? undefined : await preview({ root, preview: { port: 4176, strictPort: false, open: false } })
const url = process.env.SMOKE_URL ?? server.resolvedUrls?.local[0]
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 850 } })
await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: new URL(url).origin })
const page = await context.newPage()
const errors = []
const requested = []
page.on('request', (r) => requested.push({ url: r.url(), at: Date.now() }))
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 300)}`))

async function waitForWorkbench() {
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
}

const terminalText = () => page.locator('.terminal-wrapper.active .xterm-rows').first().innerText()

// Waits for `find` to return a value for the whole text of the robot's terminal. A busy page can draw more lines in
// one frame than the panel shows, so a line can scroll out of the visible rows before any poll sees it.
async function robotOutput(find, timeout) {
  for (const start = Date.now(); ; await page.waitForTimeout(3000)) {
    // The clipboard outlives the reload between projects, so a copy that has not landed must not read as the last one
    await page.evaluate(() => navigator.clipboard.writeText(''))
    await runCommand(page, 'Terminal: Select All')
    await page.keyboard.press('Control+Shift+C')
    const found = find(await page.evaluate(() => navigator.clipboard.readText()))
    if (found) return found
    if (Date.now() - start > timeout) throw new Error(`the robot's terminal did not show what was expected in ${timeout / 1000} s`)
  }
}
const robotLine = (pattern, timeout) => robotOutput((text) => text.match(pattern)?.[0], timeout)
const shellScreen = () => page.locator('.terminal-wrapper:visible .xterm-rows').first().innerText()

// Writes a project file straight into the origin private file system
const writeWorkspace = (path, text) =>
  page.evaluate(
    async ({ path, text }) => {
      const parts = path.split('/')
      const name = parts.pop()
      let dir = await (await navigator.storage.getDirectory()).getDirectoryHandle('frc-dev-wasm-files')
      for (const part of parts) dir = await dir.getDirectoryHandle(part, { create: true })
      const writable = await (await dir.getFileHandle(name, { create: true })).createWritable()
      await writable.write(text)
      await writable.close()
    },
    { path, text }
  )

// Runs a command in a new FRC Shell and returns once the prompt is back
async function shell(command) {
  await runCommand(page, 'Terminal: Create New Terminal')
  await page.waitForFunction(() => [...document.querySelectorAll('.terminal-wrapper .xterm-rows')].some((r) => r.textContent?.includes('FRC Dev shell')), undefined, { timeout: 30_000 })
  await page.waitForFunction(() => /frc:\S*\$\s*$/.test([...document.querySelectorAll('.terminal-wrapper')].find((w) => w.offsetParent)?.querySelector('.xterm-rows')?.innerText ?? ''), undefined, { timeout: 30_000 })
  // Keys typed before the terminal has focus are lost. A click can miss it while a task terminal is still
  // being hidden, so focus the shown terminal's input directly.
  await page.waitForFunction(() => {
    const input = [...document.querySelectorAll('.terminal-wrapper')].find((w) => w.offsetParent)?.querySelector('.xterm-helper-textarea')
    input?.focus()
    return !!input && document.activeElement === input
  }, undefined, { timeout: 10_000 })
  await page.keyboard.type(command, { delay: 4 })
  await page.keyboard.press('Enter')
  for (const start = Date.now(); Date.now() - start < 60_000; await page.waitForTimeout(200)) {
    const text = await shellScreen()
    // A long command wraps, so it is found by its start
    const start = text.lastIndexOf(command.slice(0, 30))
    const output = start >= 0 ? text.slice(start + command.length) : ''
    if (/frc:\S*\$\s*$/.test(text) && start >= 0) return output.replace(/frc:\S*\$\s*$/, '').trim()
  }
  throw new Error(`no prompt after ${command}`)
}

// Positions in the Robot State window of the Sim GUI, relative to its iframe, with the layout set in project().
// The rows are 20 px apart: Teleoperated, Utility, the OpMode combo, Disable and Enable. An entry of the
// combo's list opens below it.
const GUI = { teleop: [41, 91], opMode: [41, 131], firstOpMode: [41, 153], enable: [27, 171] }

// Creates a project of this season, builds it again, makes the teleop code print, simulates it and drives the
// Sim GUI. A timed or command robot prints from teleopInit. An OpModeRobot has no teleopInit: its teleop is the
// ExampleTeleop op mode, which the Sim GUI selects before it enables the robot.
async function project(name, base, { opModeRobot = false, epilogue = false, drawCheck = false } = {}) {
  const started = Date.now()
  let opened = 0
  await createProject(page, { name, base, year: YEAR, onOpened: () => (opened = Date.now()) })
  await waitForWorkbench()
  const firstBuild = Date.now() - opened
  let text = await terminalText()
  check(`${name}: created from ${base}, opened and built the first time`, text.includes('BUILD SUCCESSFUL'), JSON.stringify(text.slice(-400)))
  console.log(`time ${name}: create and open ${((opened - started) / 1000).toFixed(1)} s, first build ${(firstBuild / 1000).toFixed(1)} s`)
  const afterOpen = requested.filter((r) => r.at >= started)
  check(`${name}: the JVM is java-web, not CheerpJ`, afterOpen.some((r) => r.url.endsWith('/runtime/java-web/java-web.js')) && !afterOpen.some((r) => r.url.includes('cjrtnc.leaningtech.com')))

  let build = Date.now()
  await buildCode(page, 300_000)
  text = await terminalText()
  check(`${name}: a second build succeeds`, text.includes('BUILD SUCCESSFUL'), JSON.stringify(text.slice(-400)))
  console.log(`time ${name}: warm build ${seconds(build)}`)

  // The layout is a saved window size. A layout saved in a window larger than the panel is drawn scaled
  // down, this one is smaller, so the Sim GUI keeps its default positions.
  const layout = '{"MainWindow":{"GLOBAL":{"width":"1000","height":"400","maximized":"0","userScale":"2","style":"0","fps":"60"}}}'
  // java.awt.geom is in the image (java.desktop), so code that uses it builds with the edit below
  const geom = `d=$(dirname $(find src -name Robot.java)) && printf 'package frc.robot;\\nimport java.awt.geom.Line2D;\\nimport java.awt.geom.Point2D;\\nclass Geom { static double length() { return new Line2D.Double(0, 0, 3, 4).getP2().distance(new Point2D.Double(0, 0)); } }\\n' > $d/Geom.java`
  if (opModeRobot) {
    const edited = await shell(`echo '${layout}' > simgui-window.json && ${geom} && f=$(find src -name ExampleTeleop.java) && sed -i 's/public ExampleTeleop(Robot robot) {/int n; @Override public void periodic() { if (n++ % 50 == 0) System.out.println("op mode periodic"); } public ExampleTeleop(Robot robot) {/' $f && grep -c "op mode periodic" $f`)
    check(`${name}: ExampleTeleop prints from periodic`, edited.split('\n').at(-1) === '1', JSON.stringify(edited))
  } else {
    const edited = await shell(`echo '${layout}' > simgui-window.json && ${geom} && f=$(find src -name Robot.java) && sed -i 's/public void teleopInit() {}/public void teleopInit() { System.out.println("teleop enabled"); }/' $f && grep -c "teleop enabled" $f && grep -c annotationProcessor build.gradle`)
    const [printed, processors] = edited.split('\n').slice(-2)
    check(`${name}: Robot.java prints from teleopInit`, printed === '1', JSON.stringify(edited))
    if (epilogue) {
      // @Logged makes the annotation processor write META-INF/services, where Epilogue.update looks for the logger.
      // The leading space is for the first character, which a new terminal drops.
      const logged = await shell(` f=$(find src -name Robot.java) && sed -i -e 's/^public class Robot extends TimedRobot {/@org.wpilib.epilogue.Logged(strategy = org.wpilib.epilogue.Logged.Strategy.OPT_IN) public class Robot extends TimedRobot { int printed;/' -e 's/robotPeriodic() {}/robotPeriodic() { boolean ok = org.wpilib.epilogue.Epilogue.update(this); if (printed++ % 50 == 0) { System.out.println("epilogue updated=" + ok + " services=" + java.util.ServiceLoader.load(org.wpilib.epilogue.EpilogueService.class).stream().count() + " " + Robot.class.getClassLoader().getResource("META-INF\\/services\\/org.wpilib.epilogue.EpilogueService")); } }/' $f && grep -c "epilogue updated" $f`)
      check(`${name}: Robot.java is @Logged and updates Epilogue`, logged.split('\n').at(-1) === '1', JSON.stringify(logged))
    }
    if (drawCheck) {
      // The fixture has no package, so it gets the one of Robot.java
      await writeWorkspace(`workspace/${name}/DrawCheck.java`, readFileSync(join(root, 'tests/fixtures/DrawCheck.java'), 'utf8'))
      const added = await shell(` f=$(find src -name Robot.java) && (grep -m1 "^package" $f && cat DrawCheck.java) > $(dirname $f)/DrawCheck.java && rm DrawCheck.java && sed -i 's/public Robot() {/public Robot() { DrawCheck.main(new String[0]);/' $f && grep -c "DrawCheck.main" $f && grep -c "class DrawCheck" $(dirname $f)/DrawCheck.java`)
      check(`${name}: Robot runs DrawCheck from its constructor`, added.split('\n').slice(-2).join() === '1,1', JSON.stringify(added))
    }
    check(`${name}: build.gradle lists the annotation processors that the builds ran`, Number(processors) > 0, JSON.stringify(edited))
  }

  const run = Date.now()
  await simulate(page)
  const handle = await page.waitForSelector('iframe[title="Simulation GUI"]', { timeout: 600_000 })
  check(`${name}: the WebAssembly Sim GUI opens`, (await handle.getAttribute('src')).includes('/runtime/2027/tools/simgui/'), await handle.getAttribute('src'))
  await robotLine(/Robot program starting/, 300_000)
  console.log(`time ${name}: Enter on the extension picker to "Robot program starting" ${seconds(run)} (includes the build of the edit)`)
  check(`${name}: the robot reaches "Robot program starting", built with code that uses java.awt.geom`, true)
  if (drawCheck) {
    const draw = await robotOutput((text) => {
      const lines = text.split('\n').map((l) => l.trimEnd())
      const first = lines.findIndex((l) => l.startsWith('shapes INT_ARGB '))
      const last = lines.findIndex((l, i) => i > first && l.startsWith('text '))
      return first >= 0 && last > first && lines.slice(first, last + 1).join('\n')
    }, 300_000)
    check(`${name}: Graphics2D and text drawing on BufferedImages give the expected pixels and font metrics`, draw === DRAW_CHECK, draw)
  }
  await robotLine(/Robot program startup complete/, 120_000)
  check(`${name}: robot startup completes`, true)
  if (epilogue) {
    const probe = await robotLine(/epilogue updated=\w+ services=\d+ \S*/, 60_000)
    check(`${name}: Epilogue finds the generated logger through ServiceLoader`, probe?.startsWith('epilogue updated=true services=1 ') && !probe.endsWith(' null'), probe)
  }

  const gui = await handle.contentFrame()
  await gui.locator('canvas#canvas').waitFor({ timeout: 120_000 })
  await gui.waitForFunction(() => Module.frcSim?.host?.status() === 'running', undefined, { timeout: 60_000 })
  check(`${name}: the Sim GUI connects to the robot`, true)
  const box = await handle.boundingBox()
  const simState = () => gui.evaluate(() => JSON.parse(Module.ccall('frc_sim_state', 'string', [], [])))
  const click = async ([x, y]) => {
    await page.mouse.click(box.x + x, box.y + y)
    await page.waitForTimeout(500)
  }
  await click(GUI.teleop)
  if (opModeRobot) {
    const until = async (predicate) => {
      for (let i = 0; i < 120; i++) {
        const state = await simState()
        if (predicate(state)) return state
        await page.waitForTimeout(500)
      }
      return simState()
    }
    let state = await until((s) => s.opModes > 0)
    check(`${name}: the Sim GUI lists the op modes the robot publishes`, state.opModes === 2, JSON.stringify(state))
    await click(GUI.opMode)
    await page.screenshot({ path: join(results, `wpilib2027-${name}-opmodes.png`) })
    await click(GUI.firstOpMode)
    // Robot mode in bits 57-56 of the id, 2 for teleoperated
    const teleopId = (id) => id !== '0' && ((BigInt(id) >> 56n) & 3n) === 2n
    state = await until((s) => teleopId(s.opMode))
    check(`${name}: the OpMode combo selects the teleop op mode`, teleopId(state.opMode), JSON.stringify(state))
    // The robot reports the op mode it runs, so the GUI shows GOOD and allows Enable
    const mask = (1n << 56n) - 1n + (3n << 56n)
    state = await until((s) => BigInt(s.programState) !== 0n && (BigInt(s.programState) & mask) === BigInt(s.opMode))
    check(`${name}: the robot program reports the selected op mode`, (BigInt(state.programState) & mask) === BigInt(state.opMode) && state.opMode !== '0', JSON.stringify(state))
    await click(GUI.enable)
    await robotLine(/op mode periodic/, 60_000)
    state = await simState()
    check(`${name}: Enable runs the selected op mode on the robot`, state.ds && state.enabled && state.robotMode === 2, JSON.stringify(state))
  } else {
    await click(GUI.enable)
    await robotLine(/teleop enabled/, 60_000)
    const state = await simState()
    check(`${name}: Teleoperated and Enable in the Sim GUI enable the robot`, state.ds && state.enabled && state.robotMode === 2, JSON.stringify(state))
  }
  await page.screenshot({ path: join(results, `wpilib2027-${name}.png`) })

  await runCommand(page, 'WPILib: Stop Robot Simulation')
  const closed = await page.waitForSelector('iframe[title="Simulation GUI"]', { state: 'detached', timeout: 60_000 }).then(() => true, () => false)
  check(`${name}: stopping the robot closes the Sim GUI`, closed)
}

try {
  await page.goto(url)
  await waitForWorkbench()
  // ONLY=CommandV3Bot runs the named projects, separated by commas
  const only = process.env.ONLY?.split(',')
  if (!only || only.includes('TimedBot')) await project('TimedBot', 'Timed Robot', { epilogue: true, drawCheck: true })
  if (!only || only.includes('CommandBot')) await project('CommandBot', 'Command v2 Robot')
  if (!only || only.includes('CommandV3Bot')) await project('CommandV3Bot', 'Command v3 Robot', { opModeRobot: true })
} catch (error) {
  failures.push('exception')
  console.error(error)
  await page.screenshot({ path: join(results, 'wpilib2027-failure.png') }).catch(() => {})
  console.log(`terminal: ${JSON.stringify((await terminalText().catch(() => '')).slice(-1500))}`)
} finally {
  console.log(`java-web image files requested: ${[...new Set(requested.map((r) => r.url).filter((u) => u.includes('/runtime/java-web/jdk/')).map((u) => u.split('/').pop()))].join(', ')}`)
  if (errors.length > 0) console.log(`browser errors (${errors.length}):\n${errors.slice(0, 20).join('\n')}`)
  await browser.close()
  await server?.close()
}

if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
