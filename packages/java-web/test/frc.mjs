// Runs the 2027 HAL and shim tests on java-web under Node: all must pass, and as many must run as on
// HotSpot. Compiles WPILib's templates with javac on java-web, which must print and write what it does on
// HotSpot. Then runs a TimedRobot on the shim and checks that its loop keeps time.
// Usage: node --expose-gc test/frc.mjs. Runs java-web's npm package (pinned in package.json) and builds the 2027
// shim first, so it needs what fetch-deps --year 2027 downloads.
import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { runNode } from '@project516/java-web'

const ROOT = path.resolve(import.meta.dirname, '..', '..', '..')
const RUNTIME = path.join(ROOT, 'apps', 'ide', 'public', 'runtime', '2027')
const BUILD = path.join(ROOT, 'packages', 'java', 'build', '2027')
const CACHE = path.join(ROOT, '.cache', '2027')
const ROBOT = path.join(ROOT, '.cache', 'java-web', 'frc-robot')
const COMPILE = path.join(ROOT, '.cache', 'java-web', 'frc-compile')

// A filter that matches no test: the shim's tests are compiled and the vendor jars transformed, nothing runs
execFileSync('node', [path.join(ROOT, 'packages', 'java', 'build.mjs'), '--year', '2027', '--test', 'none'], { stdio: 'inherit' })

const jdk = fs.readFileSync(path.join(CACHE, 'java-home'), 'utf8').trim()
const shimJar = path.join(RUNTIME, 'frcdevwasm-shim.jar')
const manifest = JSON.parse(fs.readFileSync(path.join(RUNTIME, 'manifest.json'), 'utf8'))
// As the shim's own build: with the vision jars, which 2027 projects get from vendordeps
const wpilib = [...manifest.classpath, ...(manifest.optional ?? [])].map((p) => path.join(RUNTIME, p))
const toolsDir = path.join(ROOT, '.cache', 'tools')
const asmJar = path.join(toolsDir, fs.readdirSync(toolsDir).find((f) => /^asm-.*\.jar$/.test(f)))

fs.rmSync(ROBOT, { recursive: true, force: true })
const robotSources = fs.readdirSync(path.join(import.meta.dirname, 'robot'), { recursive: true }).filter((f) => f.endsWith('.java'))
execFileSync(path.join(jdk, 'bin', 'javac'), ['-nowarn', '-d', ROBOT, '-cp', [shimJar, ...wpilib].join(':'),
  ...robotSources.map((f) => path.join(import.meta.dirname, 'robot', f))], { stdio: 'inherit' })

// The suites share this process, so the limit covers the earlier VMs too
const maxRssMb = 2200
const hotspotOptions = { encoding: 'utf8', maxBuffer: 64 << 20, timeout: 600_000 }
const suites = [
  {
    name: 'hal',
    main: 'dev.project516.frcdevwasm.test.TestMain',
    classpath: [path.join(BUILD, 'test-classes'), shimJar, path.join(RUNTIME, 'frcdevwasm-tools.jar'), path.join(BUILD, 'vendor-tool-classes'), asmJar, ...wpilib],
    args: [],
    props: {
      'frcdevwasm.runtime': RUNTIME,
      'frcdevwasm.vendor': path.join(CACHE, 'vendor'),
      'frcdevwasm.vendorTransformed': path.join(CACHE, 'vendor-transformed'),
    },
  },
  {
    name: 'shim',
    main: 'dev.project516.frcdevwasm.shim.test.TestMain',
    classpath: [path.join(BUILD, 'shim-test-classes'), shimJar, ...wpilib],
    args: [path.join(BUILD, 'shim-test-classes')],
    props: {},
  },
]

async function onJavaWeb(o) {
  const chunks = []
  const code = await runNode({ ...o, maxRssMb, stdout: (b) => chunks.push(b), stderr: (b) => chunks.push(b) })
  return { code, out: Buffer.concat(chunks).toString('utf8') }
}

function summary(out) {
  const m = /^(\d+) passed, (\d+) failed$/m.exec(out)
  assert.ok(m, `no summary in:\n${out}`)
  return { failed: Number(m[2]), total: Number(m[1]) + Number(m[2]) }
}

for (const s of suites) {
  test(`${s.name} tests`, async () => {
    const defines = Object.entries(s.props).map(([k, v]) => `-D${k}=${v}`)
    const ref = spawnSync(path.join(jdk, 'bin', 'java'), ['-ea', ...defines, '-cp', s.classpath.join(':'), s.main, ...s.args], hotspotOptions)
    const { code, out } = await onJavaWeb({ classpath: s.classpath, mainClass: s.main, args: s.args, props: s.props, assertions: ['-ea'] })
    // Some tests race on HotSpot too, so compare counts, not output
    assert.deepEqual([...out.matchAll(/^FAIL ([^\s:]+)/gm)].map((m) => m[1]), [], out)
    assert.equal(summary(out).failed, 0, out)
    assert.ok(summary(out).total > 0, out)
    assert.equal(summary(out).total, summary(ref.stdout + ref.stderr).total)
    assert.equal(code, 0)
  })
}

const bundled = (pattern) => fs.readdirSync(path.join(RUNTIME, 'bundled')).filter((f) => pattern.test(f)).map((f) => path.join(RUNTIME, 'bundled', f))
const templateClasspath = [...wpilib, ...bundled(new RegExp(`^commandsv[23]-java-${manifest.wpilib}\\.jar$`))]
const processorPath = [...bundled(new RegExp(`^(avaje-jsonb-generator-.*|epilogue-processor-java-${manifest.wpilib})\\.jar$`)), ...wpilib]

fs.rmSync(COMPILE, { recursive: true, force: true })
fs.mkdirSync(COMPILE, { recursive: true })
execFileSync(path.join(jdk, 'bin', 'javac'), ['-d', path.join(COMPILE, 'driver'), path.join(import.meta.dirname, 'compile', 'WpilibCompile.java')], { stdio: 'inherit' })
execFileSync(path.join(jdk, 'bin', 'jar'), ['xf', path.join(RUNTIME, 'wpilib', 'templates.zip')], { cwd: COMPILE })

for (const [name, processors] of [['timed', false], ['commandv2', true], ['commandv3', true]]) {
  test(`compile the ${name} template`, async () => {
    const args = (out) => [templateClasspath.join(':'), processors ? processorPath.join(':') : '-', path.join(COMPILE, 'templates', name), path.join(COMPILE, out)]
    const driver = path.join(COMPILE, 'driver')
    const ref = spawnSync(path.join(jdk, 'bin', 'java'), ['-cp', driver, 'WpilibCompile', ...args(`${name}-hotspot`)], hotspotOptions)
    const { code, out } = await onJavaWeb({ classpath: [driver], mainClass: 'WpilibCompile', args: args(`${name}-java-web`) })
    assert.match(ref.stdout, /^compiled: true$/m, ref.stdout + ref.stderr)
    assert.equal(out, ref.stdout + ref.stderr)
    assert.equal(code, ref.status)
  })
}

// After warm-up, every 50 loops must take about a second, with no loop overruns
test('robot loop', async () => {
  const { code, out } = await onJavaWeb({ classpath: [ROBOT, shimJar, ...wpilib], mainClass: 'dev.project516.frcdevwasm.javaweb.Harness', args: ['frc.probe.Probe', '8000'] })
  assert.equal(code, 0, out)
  const lines = out.split('\n').filter((l) => l.startsWith('CONSOLE '))
  const probes = lines.map((l) => /PROBE periodic=(\d+) .* notifier=(\d+) .* now=(\d+)/.exec(l)).filter((m) => m !== null)
  assert.ok(probes.length >= 6, out)
  const warm = lines.indexOf(probes[1].input)
  assert.deepEqual(lines.slice(warm).filter((l) => l.includes('overrun')), [], 'overruns after warm-up')
  for (let i = 2; i < probes.length; i++) {
    const seconds = (Number(probes[i][3]) - Number(probes[i - 1][3])) / 1e9
    assert.ok(seconds > 0.9 && seconds < 1.2, `50 loops took ${seconds} s`)
    const notifier = Number(probes[i][2]) - Number(probes[i - 1][2])
    assert.ok(notifier >= 18 && notifier <= 22, `Notifier ran ${notifier} times in 50 loops`)
  }
})
