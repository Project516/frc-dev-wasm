// Progress tracker for real team projects of one season (node tests/teams.mjs <year>, 2026 or 2027): clone a
// pinned commit, download and transform the vendor libraries, build, start the simulation and watch the robot
// loop, in the browser. 2026 runs on CheerpJ, 2027 on java-web.
// Each stage is reported on its own. A stage that fails for a known gap (`gaps` of each repo) prints as an
// expected failure with its first error and does not fail the run; TEAMS_STRICT=1 makes every failure real.
//
// It needs the season's builds (fetch-deps, build:java, build:vscode-wpilib, build:wpigui-tools, pnpm build;
// for 2027 also build:java-web, as in wpilib2027.mjs). SMOKE_URL=<site> tests a deployed site instead of a
// local build. TEAMS_REPOS=<names> runs the repos with those names, separated by commas. TEAMS_HAR=<folder> records the network
// traffic of each repo (vendor downloads and the like; git requests always go out, since a clone makes several POSTs to one URL) to <folder>/<year>-<repo>.zip the first time and replays it after.
// TEAMS_SOAK_MS sets how long the robot runs enabled (default 20000). TEAMS_BUILD_MS sets the build limit
// (default 20 minutes). TEAMS_UNTIL=<stage> stops after that stage.
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { strFromU8, unzipSync } from 'fflate'
import { dirname, join } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { JAVA_WEB_YEARS, WPILIB_YEARS } from '../../../packages/runtime/src/years.ts'
import { runCommand, shell as runShell, simulate } from './wpilib.mjs'
import { preview } from 'vite'
import { quickInputReady } from './quickInput.mjs'

class Stop extends Error {}
const YEAR = process.argv[2]
const STRICT = process.env.TEAMS_STRICT === '1'
const SOAK_MS = Number(process.env.TEAMS_SOAK_MS ?? 20_000)
const BUILD_MS = Number(process.env.TEAMS_BUILD_MS ?? 1_200_000)

// Pinned commits. Move one when a project changes the gaps. A failed stage is an expected failure when one of
// the repo's gaps names the stage and its pattern matches the first error. gap: { name, stage, match }
const SEASONS = {
  2026: [
    {
      name: 'MechanicalAdvantage',
      url: 'https://github.com/Mechanical-Advantage/RobotCode2026Public',
      sha: 'c4583bec3783480ee3ac48ef866e91607aa7cfd7',
      // 6328. Commands v2, AdvantageKit, Phoenix 6, REVLib, Choreo, Grapple, gversion, lombok, guava
      gaps: [
        // As on the desktop: without the SIMBOT environment variable Constants.getMode() gives REPLAY in simulation,
        // and AdvantageKit asks for a log path on standard input
        { name: 'AdvantageKit replay prompt', stage: 'simulate start', match: /Enter path to file/ }
      ]
    },
    {
      name: '2910',
      url: 'https://github.com/FRCTeam2910/2026CompetitionRobot-Public',
      sha: '921c85bf725fbd9c488eb7d557e0e065640da095',
      // Commands v2, AdvantageKit, Phoenix 6, PathPlanner, BLine, maple-sim, gversion, errorprone plugin
      gaps: []
    },
    {
      name: '1678',
      url: 'https://github.com/frc1678/C2026-Public',
      sha: '0f403dd8f3ad1f7d0dbe0c1bd23b24c210f41677',
      // Commands v2, Phoenix 6, Choreo, PhotonLib
      gaps: []
    },
    {
      name: '1814',
      url: 'https://github.com/StuyPulse/StuyPlus-2026',
      sha: '6958900cf28dc499a226c90f9485cd8ea89ebe81',
      // Commands v2, AdvantageKit, DogLog, Phoenix 6, PathPlanner, maple-sim
      gaps: []
    },
    {
      name: '111',
      url: 'https://github.com/wildstang/2026_111_robot_software',
      sha: '46a0005b4ce78c6ab520e16e490d0697ac4c3a1b',
      // Commands v2, AdvantageKit, Phoenix 6, REVLib, Choreo, URCL, Grapple, PhotonLib
      gaps: []
    },
    {
      name: '3061',
      url: 'https://github.com/HuskieRobotics/frc-software-2026',
      sha: '4046f42af0c46182f1231b002b6ee5afb1e37a28',
      // Commands v2, AdvantageKit, Phoenix 6, REVLib, PathPlanner, PhotonLib, XRP, gversion, lombok
      gaps: [
        // As on the desktop: Constants.getMode() gives REPLAY for a simulated competition robot, and AdvantageKit's
        // LogFileUtil.findReplayLog asks for a path on standard input unless AKIT_LOG_PATH is set
        { name: 'AdvantageKit replay prompt', stage: 'simulate start', match: /Enter path to file/ },
        // The team's own race: LEDs() starts a 20 ms Notifier that writes LEDsRIO's buffer, which LEDsRIO sets only
        // after that constructor returns. When the constructor takes longer than 20 ms, the first callback finds null.
        { name: 'LED buffer race', stage: 'simulate start', match: /LEDsRIO\.setLEDBuffer.*NullPointerException/ }
      ]
    },
    {
      name: '4915',
      url: 'https://github.com/Spartronics4915/2026-Rebuilt',
      sha: '4bd7492f345ffed8a4a6dc6b3498b4c684c79ff3',
      // Commands v2, YAGSL, Phoenix 5 and 6 (replay), REVLib, ReduxLib, ThriftyLib, PathPlanner, BLine, Grapple, PhotonLib, gversion
      gaps: []
    },
    {
      name: '1155',
      url: 'https://github.com/SciBorgs/Rebuilt-2026',
      sha: '6d0fcdad1d2cce6ac82c7771d7be8e539f9e4c50',
      // Commands v2, YAMS, Phoenix 6, REVLib, ReduxLib, Studica, URCL, Choreo, PathPlanner, PhotonLib, gson
      gaps: []
    },
    {
      name: '1540',
      url: 'https://github.com/flamingchickens1540/robot2026',
      sha: 'f59a598093d7da52bc0823b3542102b32ea6cbba',
      // Commands v2, AdvantageKit, Phoenix 5 and 6, REVLib, Studica, URCL, Choreo, PathPlanner, Grapple, maple-sim, PhotonLib, gversion, jama
      gaps: []
    },
    {
      name: '167',
      url: 'https://github.com/icrobotics-team167/2026_Rebuilt',
      sha: '8e2daf371f695ae9d29806505ca90f67d511fff3',
      // Commands v2, AdvantageKit, Phoenix 6, REVLib, Choreo, maple-sim, PhotonLib, gversion
      gaps: []
    }
  ],
  2027: [
    {
      name: '488',
      url: 'https://github.com/Team488/FRCRobotTemplate',
      sha: '521e6102326856da47e0d6ab90fbae77c61e9620',
      // GradleRIO 2027.0.0-alpha-7, Commands v2, AdvantageKit, Phoenix 6, REVLib, PathPlanner, PhotonLib
      gaps: []
    },
    {
      name: 'Zixi',
      url: 'https://github.com/StuyPulse/Zixi',
      sha: 'f2cb51dbc49fd9cae461f29842f1283ad66cd66d',
      // Commands v3, AdvantageKit, Phoenix 6, PathPlanner
      gaps: []
    },
    {
      name: 'FM-SystemCore',
      url: 'https://github.com/Spectrum3847/2026-FM-SystemCore',
      sha: '8679621fb2e5cabf98b95d4644f8889bf9f4ecd3',
      // GradleRIO 2027.0.0-alpha-6 (the site runs alpha-8), Commands v2, AdvantageKit, Phoenix 6 26.50.0-alpha-1 (JNI),
      // PathPlanner, PhotonLib, gversion, lombok
      gaps: [
        {
          // Compiles against the alpha-6 jars with javac 25, so desktop GradleRIO builds it. Alpha-7 moved Alert to
          // org.wpilib.util and Rectangle2d to org.wpilib.math.shape, and dropped SmartDashboard, SendableChooser,
          // AprilTagFieldLayout, Pose2d.kZero (and Pose3d and Rotation2d), Gamepad.Button.*_FACE,
          // RobotController.get/setBrownoutVoltage and RobotBase.startRobot(Class). GenericHID(int) is not public.
          name: 'WPILib 2027 alpha-6 API',
          stage: 'build',
          match: /missing symbols: .*\b(Alert|SmartDashboard|kZero)\b/
        }
      ]
    }
  ]
}
const REPOS = SEASONS[YEAR]
if (!REPOS) {
  console.error(`usage: node tests/teams.mjs <${Object.keys(SEASONS).join('|')}>`)
  process.exit(2)
}

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const results = join(root, 'test-results')
mkdirSync(results, { recursive: true })

if (!WPILIB_YEARS.includes(YEAR)) {
  console.log(`skipped: ${YEAR} is not in WPILIB_YEARS (packages/runtime/src/years.ts)`)
  process.exit(0)
}
const javaWeb = JAVA_WEB_YEARS.includes(YEAR)
const needed = [
  ...(javaWeb ? ['runtime/java-web/java-web.js', 'runtime/java-web/boot.pack'] : []),
  `runtime/${YEAR}/manifest.json`,
  `runtime/${YEAR}/vscode-wpilib/dist/browser/extension.js`,
  `runtime/${YEAR}/tools/simgui/index.wasm`
]
const missing = process.env.SMOKE_URL ? [] : needed.filter((file) => !existsSync(join(root, 'dist', file)))
if (missing.length > 0) {
  console.log(`skipped: apps/ide/dist lacks ${missing.join(', ')}; run the builds in the header of this file, then pnpm build`)
  process.exit(0)
}

const wanted = process.env.TEAMS_REPOS?.toLowerCase().split(',')
const selected = REPOS.filter((repo) => !wanted || wanted.includes(repo.name.toLowerCase()))
const server = process.env.SMOKE_URL ? undefined : await preview({ root, preview: { port: 4177, strictPort: false, open: false } })
const url = process.env.SMOKE_URL ?? server.resolvedUrls?.local[0]

const outcomes = []
const seconds = (since) => `${((Date.now() - since) / 1000).toFixed(1)} s`
const firstLine = (text) => String(text).split('\n').find((l) => l.trim()) ?? ''

// Ends a stage the way run() records it: ok, a named expected failure, a failure or skipped
function record(repo, stage, outcome, detail = '', seconds = '') {
  const gap = outcome === 'fail' ? repo.gaps.find((g) => g.stage === stage && (!g.match || g.match.test(detail))) : undefined
  const status = gap && !STRICT ? 'gap' : outcome
  outcomes.push({ repo: repo.name, stage, status, gap: gap?.name, detail: detail.slice(0, 600), seconds })
  const label = { ok: 'ok  ', fail: 'FAIL', gap: 'GAP ', skipped: 'skip' }[status]
  console.log(`${label} ${repo.name}: ${stage}${gap ? ` [${gap.name}]` : ''}${seconds ? ` (${seconds})` : ''}${detail ? `: ${firstLine(detail).slice(0, 300)}` : ''}`)
}

// The robot's output, read for the first thing that went wrong
const ROBOT_ERROR = /Unhandled exception|UnsatisfiedLinkError|Exception in thread|NoClassDefFoundError|ClassNotFoundException|IllegalAccessException|Robot program exited|Enter path to file|Error: /m
const errorLine = (text) => text.split('\n').find((l) => ROBOT_ERROR.test(l))?.trim() ?? ''

async function runRepo(repo) {
  // An on-disk profile, as a user's browser has. A default context keeps site storage in memory, and there
  // a large project vanished from the origin private file system after its long build.
  const profile = mkdtempSync(join(tmpdir(), 'frc-teams-'))
  const context = await chromium.launchPersistentContext(profile, { headless: true, viewport: { width: 1400, height: 850 } })
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: new URL(url).origin })
  const page = context.pages()[0] ?? (await context.newPage())
  const errors = []
  const external = new Map()
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 300)}`))
  const pending = new Set()
  page.on('request', (r) => {
    const host = new URL(r.url()).host
    if (host !== new URL(url).host) {
      external.set(host, (external.get(host) ?? 0) + 1)
      pending.add(r.url())
    }
  })
  for (const event of ['requestfinished', 'requestfailed']) page.on(event, (r) => pending.delete(r.url()))

  const har = process.env.TEAMS_HAR ? join(process.env.TEAMS_HAR, `${YEAR}-${repo.name}.zip`) : undefined
  if (har) {
    mkdirSync(dirname(har), { recursive: true })
    const replay = existsSync(har)
    await page.routeFromHAR(har, { update: !replay, notFound: 'fallback', url: /^https:\/\/(?!cjrtnc\.leaningtech\.com|[^/]+\/git\/)/ })
    // A request the browser blocked while recording (no CORS headers) is recorded as failed. Replayed, it
    // would never answer, so it is aborted as the browser did.
    if (replay) {
      const entries = JSON.parse(strFromU8(unzipSync(readFileSync(har), { filter: (file) => file.name === 'har.har' })['har.har'])).log.entries
      // A URL that failed once and answered later (a retry) is served
      const served = new Set(entries.filter((e) => e.response.status > 0).map((e) => e.request.url))
      const blocked = new Set(entries.filter((e) => e.response.status <= 0 && !served.has(e.request.url)).map((e) => e.request.url))
      await page.route((target) => blocked.has(target.href), (route) => route.abort())
    }
    console.log(`${repo.name}: ${replay ? 'replaying' : 'recording'} ${har}`)
  }

  // The extension of the site's season asks to import a project of another alpha, as it does on the desktop.
  // The modal takes the keyboard, so it is answered when the workbench is up.
  const compatibility = page.locator('.monaco-dialog-box', { hasText: 'not compatible with this version of the extension' })
  const waitForWorkbench = async () => {
    await page.waitForSelector('.monaco-workbench .statusbar', { timeout: 120_000 })
    await page.locator('.statusbar-item', { hasText: 'WPILib' }).first().waitFor({ timeout: 30_000 })
    if (YEAR === '2027') await compatibility.waitFor({ timeout: 5000 }).then(() => compatibility.locator('.monaco-button', { hasText: /^No$/ }).click(), () => {})
  }

  // All the scrollback of the active terminal, or of the newest terminal named `name`, through select all and copy
  async function terminalText(name) {
    const tab = page.locator('.panel .composite-bar [role="tab"]', { hasText: 'Terminal' }).first()
    if (await tab.count()) await tab.click()
    // A shell the pin stage opened can stay selected while a task runs. VS Code lists tabs only for two or more terminals.
    const tabs = page.locator('.terminal-tabs-entry:visible')
    if (name && (await tabs.count())) await tabs.filter({ hasText: name }).last().click({ timeout: 5000 })
    await page.locator('.terminal-wrapper.active .xterm-rows').first().waitFor({ timeout: 10_000 })
    await runCommand(page, 'Terminal: Select All')
    await page.keyboard.press('Control+Shift+C')
    return page.evaluate(() => navigator.clipboard.readText())
  }
  const dump = async (label, name) => {
    const text = await terminalText(name).catch((e) => `(terminal text unavailable: ${e.message})`)
    writeFileSync(join(results, `teams-${YEAR}-${repo.name}-${label}.log`), text)
    return text
  }
  const shell = (command, timeout) => runShell(page, command, timeout)

  const failed = new Set()
  // Runs one stage. It is skipped when a stage it needs failed. fn returns '' for success or the error text.
  async function stage(name, needs, fn) {
    const blocking = needs.find((n) => failed.has(n))
    if (blocking) {
      failed.add(name)
      record(repo, name, 'skipped', `${blocking} failed`)
      return
    }
    const started = Date.now()
    let error
    try {
      error = await fn()
    } catch (e) {
      error = `${e.message}`.split('\n')[0]
      // A robot program that ends closes the Sim GUI, so a frame call throws: the terminal tells why
      const text = await dump(name.replaceAll(' ', '-'))
      // The last error is the one that ended the program; an earlier one may be from the build
      const robot = text.startsWith('(terminal text unavailable') ? '' : text.split('\n').filter((l) => ROBOT_ERROR.test(l)).pop()?.trim()
      if (robot) error += `; ${robot}`
    }
    if (error) {
      failed.add(name)
      await page.screenshot({ path: join(results, `teams-${YEAR}-${repo.name}-${name.replaceAll(' ', '-')}.png`) }).catch(() => {})
    }
    record(repo, name, error ? 'fail' : 'ok', error ?? '', seconds(started))
    if (name === process.env.TEAMS_UNTIL) throw new Stop()
  }

  try {
    await page.goto(url)
    await waitForWorkbench()

    await stage('clone', [], async () => {
      await runCommand(page, 'Git: Clone')
      await quickInputReady(page)
      await page.keyboard.type(repo.url)
      await page.keyboard.press('Enter')
      // The folder to clone into is /workspace, where its dialog starts. It can still be listing at the first Enter.
      const dialog = page.locator('.quick-input-widget:not([style*="display: none"])')
      await page.waitForTimeout(1500)
      for (let i = 0; i < 5 && (await dialog.count()); i++) {
        await page.keyboard.press('Enter')
        await page.waitForTimeout(800)
      }
      // The clone opens as the workspace folder, which reloads the page
      const buildGradle = page.locator('.explorer-viewlet .monaco-list-row', { hasText: 'build.gradle' }).first()
      const projectRoot = page.locator('.explorer-viewlet .monaco-list-row[aria-expanded="false"]', { hasText: new RegExp(`^${repo.url.split('/').pop()}$`) }).first()
      // A failed clone says so in a dialog
      const gitError = page.locator('.monaco-dialog-box', { hasText: 'Git:' })
      const refused = gitError.waitFor({ timeout: 600_000 }).then(
        async () => {
          throw new Error((await gitError.locator('.dialog-message-text').innerText()).replace(/\s+/g, ' '))
        },
        () => new Promise(() => {})
      )
      await Promise.race([buildGradle.waitFor({ timeout: 600_000 }), projectRoot.waitFor({ timeout: 600_000 }), refused]).catch(async (error) => {
        if (!error.name?.includes('Timeout')) throw error
        const shown = async (selector) => (await page.locator(selector).allInnerTexts()).join(' | ').replace(/\s+/g, ' ').slice(0, 300) || 'none'
        throw new Error(`no project after 600 s. Requests in flight: ${[...pending].join(' ') || 'none'}. Input box: ${await shown('.quick-input-widget:not([style*="display: none"])')}. Notifications: ${await shown('.notifications-toasts .notification-list-item')}. Status bar: ${await shown('.statusbar-item')}`)
      })
      if (!(await buildGradle.isVisible())) await projectRoot.click()
      await buildGradle.waitFor({ timeout: 60_000 })
      await waitForWorkbench()
      return ''
    })

    // The clone is the default branch. Check out the pinned commit and reopen the folder.
    await stage('pin', ['clone'], async () => {
      const out = await shell(`git checkout -q --detach ${repo.sha} && git rev-parse HEAD`)
      if (!out.split('\n').includes(repo.sha)) return `HEAD is not ${repo.sha}: ${out.slice(-300)}`
      await page.reload()
      await waitForWorkbench()
      return ''
    })

    // The vendor libraries resolve in the background when the project opens: a status bar progress, and a
    // warning that offers to import jars when a download or the transform fails. The build uses the same
    // cache, so a failure shows again there.
    await stage('vendor download and transform', ['pin'], async () => {
      let seen = false
      for (const start = Date.now(); Date.now() - start < 600_000; await page.waitForTimeout(500)) {
        const busy = await page.locator('.statusbar-item', { hasText: 'Vendor libraries' }).count()
        const toast = (await page.locator('.notifications-toasts .notification-list-item', { hasText: 'Import Jars' }).allInnerTexts())[0]
        if (toast) {
          return toast.replace(/\s+/g, ' ')
        }
        if (busy) {
          seen = true
          if (Date.now() - start > 120_000 && Date.now() - start < 121_000) console.log(`${repo.name}: still busy: ${(await page.locator('.statusbar-item', { hasText: 'Vendor libraries' }).first().innerText()).replace(/\s+/g, ' ')}. Requests in flight: ${[...pending].join(' ') || 'none'}`)
        } else if (seen || Date.now() - start > 90_000) break
      }
      return ''
    })

    await stage('build', ['pin'], async () => {
      await runCommand(page, 'WPILib: Build Robot Code')
      // Its text is read from the newest Java Build terminal through copy, since the build can be long
      let log = ''
      let finished = false
      // Java Build opens a moment after the command, so a failed read is retried
      let readError = ''
      for (const start = Date.now(); Date.now() - start < BUILD_MS && !finished; await page.waitForTimeout(3000)) {
        log = await terminalText('Java Build').then(
          (text) => ((readError = ''), text),
          (e) => ((readError = e.message.split('\n')[0]), '')
        )
        finished = /BUILD (SUCCESSFUL|FAILED)|terminated with exit code/.test(log)
      }
      log = await dump('build', 'Java Build')
      if (!finished) {
        await page.screenshot({ path: join(results, `teams-${YEAR}-${repo.name}-build.png`) })
        const reading = readError ? ` Reading the Java Build terminal failed: ${readError}.` : ''
        return `the build did not finish in ${BUILD_MS / 60_000} minutes.${reading} Last output: ${JSON.stringify(log.slice(-400))}`
      }
      if (log.includes('BUILD SUCCESSFUL')) return ''
      const marker = log.split('\n').find((l) => /error:|FAILED|could not|Could not|Error/.test(l))
      // The names javac could not find, to tell a few moved classes from a broken build
      const missing = [...new Set([...log.matchAll(/symbol:\s+(?:class|variable|method) (\w+)/g)].map((m) => m[1]))]
      return `${marker?.trim() ?? log.slice(-400)}\nErrors: ${log.match(/^.*error:/gm)?.length ?? 0}, missing symbols: ${missing.slice(0, 20).join(', ')}`
    })

    // The simulation. In 2027 the robot is enabled with the Sim GUI's own buttons, so its Robot State window sits at
    // known positions, and the Sim GUI draws small. In 2026 the driver station message of halsim_ws enables it.
    const clickGui = YEAR === '2027'
    const layout = '{"MainWindow":{"GLOBAL":{"width":"1000","height":"400","maximized":"0","userScale":"2","style":"0","fps":"60"}}}'
    let guiHandle
    await stage('simulate start', ['build'], async () => {
      if (clickGui) await shell(`echo '${layout}' > simgui-window.json`)
      // As on the desktop, the extensions are not asked for when the project's settings skip the picker
      const pick = !/"wpilib\.skipSelectSimulateExtension"\s*:\s*true/.test(await shell('cat .vscode/settings.json'))
      let note = ''
      try {
        await simulate(page, { gui: true, pickerTimeout: BUILD_MS, pick })
      } catch (e) {
        // The command may not have shown its picker; the terminal tells what happened
        const toasts = await page.locator('.notifications-toasts .notification-list-item').allInnerTexts()
        const rows = await page.locator('.quick-input-list .monaco-list-row').allInnerTexts()
        note = `${firstLine(e.message)}. Notifications: ${toasts.join(' | ').replace(/\s+/g, ' ').slice(0, 300)}. Picker: ${rows.join(' | ').replace(/\s+/g, ' ').slice(0, 300)}\n`
      }
      for (const start = Date.now(); Date.now() - start < 600_000; await page.waitForTimeout(3000)) {
        // A robot that starts slowly can keep the page too busy to answer a click
        const text = await terminalText().catch(() => '')
        // Kept as it goes: a page that crashes cannot be read afterwards
        if (text) writeFileSync(join(results, `teams-${YEAR}-${repo.name}-sim.log`), text)
        // A robot that overruns every loop can push the startup line out of the terminal's scrollback, and a loop
        // overrun is only reported once startup is complete
        if (/Robot program startup complete|Loop time of [\d.]+s overrun/.test(text)) {
          guiHandle = await page.waitForSelector('iframe[title="Simulation GUI"]', { timeout: 60_000 }).catch(() => undefined)
          if (guiHandle) return ''
          // The robot started and stopped again: the Sim GUI closes with it
          const stopped = await dump('sim')
          const exception = stopped.split('\n').find((l) => /\b[\w.$]+(Exception|Error)\b/.test(l))?.trim()
          return `${exception ?? 'no Simulation GUI panel opened'}\n${note}${stopped.slice(-600)}`
        }
        const error = errorLine(text)
        if (error) {
          writeFileSync(join(results, `teams-${YEAR}-${repo.name}-sim.log`), text)
          return `${error}\n${note}${text.slice(-600)}`
        }
      }
      const last = await terminalText()
      writeFileSync(join(results, `teams-${YEAR}-${repo.name}-sim.log`), last)
      return `the robot program did not finish starting in 10 minutes\n${note}Last output: ${JSON.stringify(last.slice(-500))}`
    })

    const robotLoop = async () => {
      const gui = await guiHandle.contentFrame()
      if (!gui) {
        // The Sim GUI closes when the robot program ends
        const text = await dump('sim')
        return errorLine(text) || `the Sim GUI closed after the robot started. Last output: ${JSON.stringify(text.slice(-400))}`
      }
      await gui.locator('canvas#canvas').waitFor({ timeout: 120_000 })
      await gui.waitForFunction(() => Module.frcSim?.host?.status() === 'running', undefined, { timeout: 120_000 })
      // Updates of each NetworkTables topic: a robot loop publishes every period
      await page.evaluate(() => {
        const nt = window.frcDevWasmNt4
        const names = new Map()
        const updates = new Map()
        const handle = nt.handleRobotMessage.bind(nt)
        nt.handleRobotMessage = (message) => {
          const { id, name } = message.data
          if (message.type === 'nt.announce') names.set(id, name)
          else if (message.type === 'nt.value') {
            const topic = names.get(id) ?? String(id)
            if (!updates.has(topic)) updates.set(topic, [])
            updates.get(topic).push(performance.now())
          }
          handle(message)
        }
        window.teamsProbe = updates
      })
      if (clickGui) {
        const box = await guiHandle.boundingBox()
        await page.mouse.click(box.x + 41, box.y + 91)
        await page.waitForTimeout(500)
        await page.mouse.click(box.x + 27, box.y + 171)
      } else {
        const ds = { '>ds': true, '>enabled': true, '>autonomous': false, '>test': false, '>station': 'red1', '>new_data': true }
        await page.evaluate((data) => window.frcDevWasmSim.send({ type: 'DriverStation', device: '', data }), ds)
      }
      await page.evaluate(() => window.teamsProbe.clear())
      await page.waitForTimeout(SOAK_MS)
      const state = await gui.evaluate(() => JSON.parse(Module.ccall('frc_sim_state', 'string', [], [])))
      // The robot's own view: bit 0 of the driver station's control word published to NetworkTables
      const controlWord = await page.evaluate(() => window.frcDevWasmNt4.getTopics().find((t) => t.name === '/FMSInfo/FMSControlData')?.value)
      const enabled = clickGui ? state.enabled : (controlWord & 1) === 1
      const busiest = await page.evaluate(() => {
        const [topic, times] = [...window.teamsProbe].sort((a, b) => b[1].length - a[1].length)[0] ?? ['', []]
        const gaps = times.slice(1).map((t, i) => t - times[i]).sort((a, b) => a - b)
        return { topic, count: times.length, medianMs: gaps[Math.floor(gaps.length / 2)] ?? 0 }
      })
      const text = await dump('sim')
      await page.screenshot({ path: join(results, `teams-${YEAR}-${repo.name}.png`) })
      console.log(`${repo.name}: enabled ${enabled}, busiest topic ${busiest.topic}: ${busiest.count} updates in ${SOAK_MS / 1000} s, median ${busiest.medianMs.toFixed(0)} ms apart`)
      if (errorLine(text)) return errorLine(text)
      if (!enabled) return `the robot did not enable: ${JSON.stringify({ ...state, controlWord, known: undefined })}`
      if (busiest.count < 10) return `only ${busiest.count} NetworkTables updates in ${SOAK_MS / 1000} s`
      return ''
    }
    // The Sim GUI goes away when the robot program ends, so a failure here is read from the terminal
    await stage('robot loop', ['simulate start'], () =>
      robotLoop().catch(async (e) => {
        const text = await dump('sim')
        // The last error is the one that ended the program; an earlier one may be from the build
        const robot = text.split('\n').filter((l) => ROBOT_ERROR.test(l)).pop()?.trim()
        return robot ? `${robot}\n${firstLine(e.message)}` : firstLine(e.message)
      })
    )

    await runCommand(page, 'WPILib: Stop Robot Simulation').catch(() => {})
    console.log(`${repo.name}: network ${[...external].map(([host, n]) => `${host} ${n}`).join(', ')}`)
  } catch (error) {
    if (error instanceof Stop) return
    console.error(error)
    outcomes.push({ repo: repo.name, stage: 'test', status: 'fail', detail: String(error.message).slice(0, 300) })
    await page.screenshot({ path: join(results, `teams-${YEAR}-${repo.name}-failure.png`) }).catch(() => {})
  } finally {
    if (errors.length > 0) console.log(`${repo.name}: browser errors (${errors.length}):\n${errors.slice(0, 10).join('\n')}`)
    await context.close().catch(() => {}) // writes the HAR file
    rmSync(profile, { recursive: true, force: true })
  }
}

try {
  for (const repo of selected) await runRepo(repo)
} finally {
  await server?.close()
}

const summary = outcomes.map((o) => `${o.repo.padEnd(14)} ${o.stage.padEnd(30)} ${o.status}${o.gap ? ` (${o.gap})` : ''}`).join('\n')
console.log(`\nStages\n${summary}`)
writeFileSync(join(results, `teams${YEAR}.json`), `${JSON.stringify({ pinned: Object.fromEntries(REPOS.map((r) => [r.name, r.sha])), outcomes }, null, 2)}\n`)
const real = outcomes.filter((o) => o.status === 'fail')
if (real.length > 0) {
  console.error(`failed: ${real.map((o) => `${o.repo} ${o.stage}`).join(', ')}`)
  process.exit(1)
}
