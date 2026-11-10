// Acceptance test: clone a real team project, build it, and simulate it. It needs a build (pnpm build)
// and spends 3 of the 60 unauthenticated GitHub API requests per hour on the clone.
// SPECTRUM_HAR=<file> records the network traffic of the clone the first time and replays it afterwards.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { buildCode, runCommand as runWpilibCommand, simulate } from './wpilib.mjs'
import { preview } from 'vite'
import { quickInputReady } from './quickInput.mjs'

const REPO = process.env.SPECTRUM_REPO ?? 'https://github.com/Spectrum3847/2026-Spectrum'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

// Limit for the median scheduler run. It takes about 150 ms on a slow computer (the robot asks for 20 ms);
// twice that leaves room for a slower machine and still catches a shim change that doubles it.
const LOOP_LIMIT_MS = Number(process.env.SPECTRUM_LOOP_LIMIT_MS ?? 300)

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`)
  if (!ok) failures.push(name)
}

const server = await preview({ root, preview: { port: 4175, strictPort: false, open: false } })
const url = server.resolvedUrls?.local[0]
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1400, height: 850 } })
await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: new URL(url).origin })
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 300)}`))

const har = process.env.SPECTRUM_HAR
if (har) {
  const replay = existsSync(har)
  await page.routeFromHAR(har, {
    update: !replay,
    notFound: 'fallback',
    url: /^https:\/\/(api\.github\.com|raw\.githubusercontent\.com|codeload\.github\.com)\//
  })
  console.log(`${replay ? 'replaying' : 'recording'} ${har}`)
}

const runCommand = (title) => runWpilibCommand(page, title)

// The WPILib terminal's whole scrollback, through select all and copy. A failed build leaves the Problems
// panel in front, so switch back to the terminal first.
async function terminalText() {
  const tab = page.locator('.panel .composite-bar [role="tab"]', { hasText: 'Terminal' }).first()
  if (await tab.count()) await tab.click()
  await page.locator('.terminal-wrapper.active .xterm-rows').first().waitFor({ timeout: 10_000 })
  await runCommand('Terminal: Select All')
  await page.keyboard.press('Control+Shift+C')
  return page.evaluate(() => navigator.clipboard.readText())
}

async function dumpTerminal(label) {
  const text = await terminalText().catch((e) => `(terminal text unavailable: ${e.message})`)
  writeFileSync(join(results, `spectrum-${label}.log`), text)
  return text
}

const problems = () => page.locator('.markers-panel .monaco-list-row').evaluateAll((rows) => rows.map((r) => r.getAttribute('aria-label') ?? r.textContent ?? ''))

try {
  await page.goto(url)
  await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })

  await runCommand('Git: Clone')
  await quickInputReady(page)
  await page.keyboard.type(REPO)
  await page.keyboard.press('Enter')
  // The folder to clone into: /workspace, where its dialog starts. It can still be listing the
  // folder at the first Enter.
  const dialog = page.locator('.quick-input-widget:not([style*="display: none"])')
  await page.waitForTimeout(1500)
  for (let i = 0; i < 5 && (await dialog.count()); i++) {
    await page.keyboard.press('Enter')
    await page.waitForTimeout(800)
  }
  // The clone opens as the workspace folder, which reloads the page
  const buildGradleRow = page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'build.gradle' }).first()
  const projectRoot = page.locator('.explorer-viewlet .monaco-list-row[aria-expanded="false"]', { hasText: new RegExp(`^${REPO.split("/").pop()}$`) }).first()
  await Promise.race([buildGradleRow.waitFor({ timeout: 600_000 }), projectRoot.waitFor({ timeout: 600_000 })])
  if (!(await buildGradleRow.isVisible())) await projectRoot.click()
  await buildGradleRow.waitFor({ timeout: 60_000 })
  await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
  await page.waitForFunction(() => document.querySelector('.statusbar')?.textContent?.includes('main'), undefined, { timeout: 120_000 })
  check('project clones through Git: Clone', true)

  await buildCode(page, 600_000)
  const buildLog = await dumpTerminal('build')
  const built = buildLog.includes('BUILD SUCCESSFUL')
  check('project builds', built, built ? '' : `${buildLog.slice(-1500)}\nProblems: ${(await problems()).slice(0, 10).join(' | ')}`)
  check('build reports no warnings', !/warning:/.test(buildLog), buildLog.split('\n').filter((l) => /warning:/.test(l)).join(' | '))
  if (!built) throw new Error('build failed')

  // Watch the robot's NetworkTables mirror: Spectrum times its scheduler run with DogLog, which publishes
  // the seconds every loop, and DogLog raises an alert when its log queue overflows.
  await page.evaluate(() => {
    const nt = window.frcDevWasmNt4
    const topicNames = new Map()
    const probe = { loops: [], alerts: [], minQueueRoom: Infinity, physics: [] }
    const handle = nt.handleRobotMessage.bind(nt)
    nt.handleRobotMessage = (message) => {
      const { id, name, value } = message.data
      if (message.type === 'nt.announce') topicNames.set(id, name)
      else if (message.type === 'nt.value') {
        const topic = topicNames.get(id) ?? ''
        if (topic.endsWith('/Scheduler/robotPeriodic')) probe.loops.push([performance.now(), value * 1000])
        else if (topic.endsWith('/Alerts/errors') && Array.isArray(value)) probe.alerts.push(...value)
        else if (topic.endsWith('/DogLog/QueueRemainingCapacity')) probe.minQueueRoom = Math.min(probe.minQueueRoom, value)
        else if (topic.endsWith('Sim/Fuel/Stats/PhysicsMs')) probe.physics.push(value)
      }
      handle(message)
    }
    window.spectrumProbe = probe
  })
  await simulate(page, { gui: true })
  const frameHandle = await page.waitForSelector('iframe[title="Simulation GUI"]', { timeout: 300_000 })
  const gui = await frameHandle.contentFrame()
  await gui.locator('canvas#canvas').waitFor({ timeout: 120_000 })
  const hostStatus = () => gui.evaluate(() => Module.frcSim?.host?.status())
  await gui.waitForFunction(() => Module.frcSim?.host?.status() === 'running', undefined, { timeout: 300_000 })
  check('robot program starts', true)
  await gui.evaluate(() => {
    window.joystickSent = []
    window.dsSent = []
    const send = Module.frcSim.host.send.bind(Module.frcSim.host)
    Module.frcSim.host.send = (m) => {
      if (m.type === 'Joystick') window.joystickSent.push(JSON.stringify(m.data))
      else if (m.type === 'DriverStation') window.dsSent.push(`${Math.round(performance.now())} ${JSON.stringify(m.data)}`)
      send(m)
    }
    window.dsLog = []
    Module.frcSim.host.listen((messages) => messages.filter((m) => m.type === 'DriverStation').forEach((m) => window.dsLog.push(`${Math.round(performance.now())} ${JSON.stringify(m.data)}`)))
  })
  const simState = () => gui.evaluate(() => JSON.parse(Module.ccall('frc_sim_state', 'string', [], [])))
  // The team's simgui.json, which the Sim GUI reads from the project like halsim_gui, places the windows,
  // so enable and drive through the SimHost, as the GUI's own messages would. The Sim GUI test covers its widgets.
  const sendToRobot = (message) => page.evaluate((m) => window.frcDevWasmSim.send(m), message)
  // Enable as soon as the robot's topics show, as a person would. The Spectrum robot does not
  // drive when it is enabled after its disabled warm-up has finished, with either Sim GUI.
  await page.waitForFunction(() => window.frcDevWasmNt4.getTopics().some((t) => /FMSInfo/.test(t.name)), undefined, { timeout: 300_000 })
  await sendToRobot({ type: 'DriverStation', device: '', data: { '>ds': true, '>enabled': true, '>autonomous': false, '>test': false, '>station': 'red1', '>new_data': true } })
  await page.waitForFunction(() => (document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? '').includes('Robot program startup complete'), undefined, { timeout: 300_000 })
  // The robot keeps looping: an exited program flips the status back
  await page.waitForFunction(
    () => (document.querySelector('.terminal-wrapper.active .xterm-rows')?.textContent ?? '').includes('Robot program startup complete'),
    undefined,
    { timeout: 180_000 }
  ).catch(() => {})
  const soakStart = await page.evaluate(() => performance.now())
  await page.waitForTimeout(Number(process.env.SPECTRUM_SOAK_MS ?? 45_000))
  const probe = await page.evaluate(() => window.spectrumProbe)
  const running = (await hostStatus()) === 'running'
  const simLog = await dumpTerminal('sim')
  const state = await simState()
  check('robot reaches its main loop and keeps running', running && simLog.includes('Robot program startup complete') && !/Robot program exited/.test(simLog), simLog.slice(-1500))
  check('the robot sees the driver station enabled and the Sim GUI reads NetworkTables', (await page.evaluate(() => window.frcDevWasmNt4.getTopics().find((t) => t.name === '/FMSInfo/FMSControlData')?.value)) === 33 && state.nt, JSON.stringify({ ...state, known: undefined }))
  check('robot output has no native link errors or uncaught exceptions', !/UnsatisfiedLinkError|Exception in thread|NoClassDefFoundError|ClassNotFoundException/.test(simLog), simLog.split('\n').filter((l) => /Error|Exception/.test(l)).slice(0, 8).join(' | '))
  // Skip the first part of the soak, where CheerpJ is still compiling the program.
  const warm = probe.loops.filter(([at]) => at - soakStart > Number(process.env.SPECTRUM_SOAK_MS ?? 45_000) * 0.4)
  const settled = warm.map(([, ms]) => ms).sort((a, b) => a - b)
  const median = settled[Math.floor(settled.length / 2)] ?? Infinity
  console.log(`robotPeriodic: ${settled.length} loops after warmup, median ${median.toFixed(1)} ms, p90 ${(settled[Math.floor(settled.length * 0.9)] ?? Infinity).toFixed(1)} ms`)
  const physics = [...probe.physics].sort((a, b) => a - b)
  console.log(`Spectrum's FuelPhysicsSim: median ${(physics[Math.floor(physics.length / 2)] ?? 0).toFixed(1)} ms of each loop`)
  const gaps = warm.slice(1).map(([at], i) => at - warm[i][0]).sort((a, b) => a - b)
  console.log(`loop period: median ${(gaps[Math.floor(gaps.length / 2)] ?? 0).toFixed(0)} ms, p90 ${(gaps[Math.floor(gaps.length * 0.9)] ?? 0).toFixed(0)} ms`)
  console.log(`DogLog queue: at least ${probe.minQueueRoom} of 1000 slots free`)
  check('DogLog never drops a log entry', !probe.alerts.some((a) => a.includes('MAX_QUEUED_LOGS')) && !simLog.includes('MAX_QUEUED_LOGS'), probe.alerts.filter((a) => a.includes('DogLog')).join(' | '))
  check('robotPeriodic stays near its period', settled.length > 20 && median < LOOP_LIMIT_MS, `median ${median} ms, limit ${LOOP_LIMIT_MS} ms`)
  const names = await page.evaluate(() => window.frcDevWasmNt4.getTopics().map((t) => t.name))
  check('robot NetworkTables topics reach the in-page server', names.some((n) => /SmartDashboard/.test(n)) && names.length > 3, names.join(', '))
  // Push the keyboard joystick forward: the swerve drivetrain should carry the robot across the field.
  const robotPoses = () =>
    page.evaluate(() =>
      Object.fromEntries(
        window.frcDevWasmNt4
          .getTopics()
          .filter((t) => /Pose|\/Robot$/.test(t.name) && Array.isArray(t.value) && t.value.length === 3)
          .map((t) => [t.name, [t.value[0], t.value[1]]])
      )
    )
  // The robot enters teleop on its next loop, which can take a while: it warms up path planning first.
  const driving = await page.waitForFunction(() => window.frcDevWasmNt4.getTopics().some((t) => t.name === '/Robot/Swerve/SystemState' && t.value === 'TELEOP_DRIVE'), undefined, { timeout: 240_000 }).then(() => true, () => false)
  const before = await robotPoses()
  await page.evaluate(() => {
    window.driveTimer = setInterval(() => {
      window.frcDevWasmSim.send({ type: 'Joystick', device: '0', data: { '>axes': [0, -1, 0], '>buttons': [], '>povs': [] } })
      window.frcDevWasmSim.send({ type: 'DriverStation', device: '', data: { '>new_data': true } })
    }, 50)
  })
  // Stop after a short drive: about 1.15 m ahead the robot hits a field element, where its wheels keep
  // turning, so CTRE's odometry runs ahead of the simulated pose as it would on a real robot
  // A timeout is not a failure here: the check below reports it with how far the robot moved
  const reached = await page
    .waitForFunction(() => {
      const pose = window.frcDevWasmNt4.getTopics().find((t) => t.name === '/SmartDashboard/Field2d/Robot')?.value
      if (!Array.isArray(pose)) return false
      window.driveStart ??= pose
      return Math.hypot(pose[0] - window.driveStart[0], pose[1] - window.driveStart[1]) > 0.25
    }, undefined, { timeout: 60_000, polling: 100 })
    .then(() => true, () => false)
  const during = await robotPoses()
  await page.evaluate(() => {
    clearInterval(window.driveTimer)
    window.frcDevWasmSim.send({ type: 'Joystick', device: '0', data: { '>axes': [0, 0, 0], '>buttons': [], '>povs': [] } })
    window.frcDevWasmSim.send({ type: 'DriverStation', device: '', data: { '>new_data': true } })
  })
  // Whichever pose topic moved the most: teams name them differently.
  const moves = Object.keys(during).map((name) => [name, before[name] ? Math.hypot(during[name][0] - before[name][0], during[name][1] - before[name][1]) : 0])
  const [movedTopic, moved] = moves.sort((x, y) => y[1] - x[1])[0] ?? ['none', 0]
  const diagnostics = moved > 0.05 ? '' : await page.evaluate(async () => `${JSON.stringify(window.frcDevWasmNt4.getTopics().filter((t) => /SuperStructure|SystemState|WantedState|FMSControlData|Pilot|Alliance/i.test(t.name) && !Array.isArray(t.value)).slice(0, 40).map((t) => `${t.name}=${t.value}`))}`)
  const sent = await gui.evaluate(() => window.joystickSent.slice(-3))
  check('driving with the joystick moves the robot pose', moved > 0.05, `swerve in teleop drive: ${driving}. Field2d pose moved 0.25 m within 60 s: ${reached}. ${movedTopic} moved ${moved.toFixed(2)} m. before ${JSON.stringify(before)}, after ${JSON.stringify(during)}. Joystick messages to the robot: ${sent.join(' ')}. GUI state: ${JSON.stringify({ ...(await simState()), known: undefined })}. new data announcements sent: ${await gui.evaluate(() => window.dsSent.filter((m) => /\{"\>new_data":true\}$/.test(m)).length)}. DriverStation messages to the robot: ${(await gui.evaluate(() => window.dsSent.filter((m) => !/^\d+ \{">new_data":true\}$/.test(m)).slice(0, 30))).join(' | ')}. ${diagnostics}`)
  // CTRE's odometry follows maple-sim's pose, as on the desktop: maple-sim sets the encoders and the gyro
  await page.waitForTimeout(2000)
  const poses = await page.evaluate(() => {
    const read = (name) => window.frcDevWasmNt4.getTopics().find((t) => t.name === name)?.value
    const raw = read('/Robot/Swerve/State/Pose')
    const field = read('/SmartDashboard/Field2d/Robot')
    if (!(raw instanceof Uint8Array) || raw.length < 24 || !Array.isArray(field)) return undefined
    const view = new DataView(raw.buffer, raw.byteOffset, raw.byteLength)
    return { odometry: [view.getFloat64(0, true), view.getFloat64(8, true), (view.getFloat64(16, true) * 180) / Math.PI], field }
  })
  const gap = poses ? Math.hypot(poses.odometry[0] - poses.field[0], poses.odometry[1] - poses.field[1]) : Infinity
  check('CTRE odometry follows the simulated pose', gap < 0.3, `${gap.toFixed(2)} m apart: ${JSON.stringify(poses)}`)
  await page.screenshot({ path: join(results, 'spectrum.png') })
  await runCommand('WPILib: Stop Robot Simulation')
  await page.waitForSelector('iframe[title="Simulation GUI"]', { state: 'detached', timeout: 30_000 })
} catch (error) {
  failures.push('exception')
  console.error(error)
  await page.screenshot({ path: join(results, 'spectrum-failure.png') }).catch(() => {})
  await dumpTerminal('failure').catch(() => {})
} finally {
  if (errors.length > 0) console.log(`browser errors (${errors.length}):\n${errors.slice(0, 15).join('\n')}`)
  await context.close().catch(() => {}) // writes the HAR file
  await browser.close()
  await server.close()
}

if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
