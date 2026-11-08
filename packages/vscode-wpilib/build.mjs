// Builds the WPILib VS Code extension of each season for the browser into
// apps/ide/public/runtime/<year>/vscode-wpilib/: its sources at the release tag with patches/<year>/
// applied, bundled with Node's modules replaced by shims/ and the modules that start processes replaced
// by replacements/<year>/, plus the resources (templates, examples, webview pages) of the released vsix.
// `--year <y>` limits it to one season.
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { unzipSync } from 'fflate'
import { rolldown } from 'rolldown'
import { WPILIB_YEARS } from '../runtime/src/years.ts'

// The vscode-wpilib release of each season: version of its tag, commit and the sha256 of its vsix.
const SEASONS = {
  2026: {
    version: '2026.2.1',
    commit: 'ee754c0f8bf541a11e2e946f6180d7df6a6ba895',
    vsixSha256: '423f421d093c38366220b1846e105d065e702e901ff51b186bb6af613e27b3b6',
    shims: { glob: 'glob.js' }
  },
  2027: {
    version: '2027.0.0-alpha-8',
    commit: '1059df7c14c26accea9a05ea015b28d1faf9481a',
    vsixSha256: '21f0f21418ffad867c763d6b3bf2ac299f9a4b7a8d41c18a9cafe324fdedeac4',
    // Node's newer forms of modules, which the 2027 extension imports
    shims: { 'fs/promises': 'fs-promises.js', constants: 'constants.js', glob: 'glob-promise.js' },
    // Downloads use the global fetch, not node-fetch
    globalFetch: true
  }
}
const HERE = import.meta.dirname
const ROOT = path.resolve(HERE, '..', '..')
const WORK = path.join(ROOT, '.cache', 'vscode-wpilib')

const args = process.argv.slice(2)
const years = args.flatMap((arg, i) => (args[i - 1] === '--year' ? [arg] : []))
for (const year of years) if (!SEASONS[year]) throw new Error(`WPILib ${year} is not one of ${Object.keys(SEASONS).join(', ')}`)

// The sources at the season's commit with patches/<year>/ applied
function sources(year, repo) {
  const { commit } = SEASONS[year]
  const git = (...args) => execFileSync('git', args, { cwd: repo, stdio: 'inherit' })
  const head = fs.existsSync(path.join(repo, '.git')) ? execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim() : ''
  if (head !== commit) {
    fs.rmSync(repo, { recursive: true, force: true })
    fs.mkdirSync(repo, { recursive: true })
    git('init', '-q')
    git('remote', 'add', 'origin', 'https://github.com/wpilibsuite/vscode-wpilib.git')
    git('fetch', '-q', '--depth', '1', '--filter=blob:none', 'origin', commit)
    git('sparse-checkout', 'set', '--no-cone', '/vscode-wpilib/src/')
  }
  git('checkout', '-q', '-f', commit)
  const patches = path.join(HERE, 'patches', year)
  for (const patch of fs.readdirSync(patches).sort()) git('apply', path.join(patches, patch))
}

async function vsix({ version, vsixSha256 }) {
  const file = path.join(WORK, `vscode-wpilib-${version}.vsix`)
  if (!fs.existsSync(file)) {
    const url = `https://github.com/wpilibsuite/vscode-wpilib/releases/download/v${version}/vscode-wpilib-${version}.vsix`
    const res = await fetch(url)
    if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`)
    fs.writeFileSync(file, Buffer.from(await res.arrayBuffer()))
  }
  const bytes = fs.readFileSync(file)
  const hash = createHash('sha256').update(bytes).digest('hex')
  if (hash !== vsixSha256) throw new Error(`${file}: sha256 ${hash}, expected ${vsixSha256}`)
  return unzipSync(bytes)
}

// What the browser needs from the vsix: no C++ resources, no compiled Node bundle
const SKIP = [/^gulpfile\.mjs$/, /^resources\/cpp\//, /^resources\/testing\//, /^out\//, /^i18n\//, /\.md$/, /^TODO\.txt$/, /^eslint\.config\.js$/]

// Upstream modules that start processes, open sockets, need the C++ extension or set file modes, and the file of
// replacements/<year>/ that stands in for each
const REPLACED = {
  'executor.ts': 'executor.ts',
  'builtintools.ts': 'builtintools.ts',
  'jdkdetector.ts': 'jdkdetector.ts',
  'logger.ts': 'logger.ts',
  'java/simulate.ts': 'simulate.ts',
  'cpp/cpp.ts': 'cpp.ts',
  'cppprovider/apiprovider.ts': 'apiprovider.ts',
  'shared/permissions.ts': 'permissions.ts'
}

const shim = (name) => path.join(HERE, 'shims', name)
const gitShim = (name) => path.join(ROOT, 'packages', 'vscode-git', 'shims', name)
const dep = (name) => path.join(HERE, 'node_modules', name)

async function bundle(year, src, out) {
  const replacements = path.join(HERE, 'replacements', year)
  // Swaps the replaced modules, and turns webpack's `!!raw-loader!<module>` into the module's text
  const replace = {
    name: 'frc-dev-wasm-replace',
    async resolveId(source, importer, options) {
      if (source.startsWith('!!raw-loader!')) return { id: `\0raw:${path.join(HERE, 'node_modules', source.slice('!!raw-loader!'.length))}` }
      const resolved = await this.resolve(source, importer, { ...options, skipSelf: true })
      if (!resolved) return null
      const relative = path.relative(fs.realpathSync(src), resolved.id).split(path.sep).join('/')
      return REPLACED[relative] ? path.join(replacements, REPLACED[relative]) : null
    },
    load(id) {
      if (id.startsWith('\0raw:')) return `export default ${JSON.stringify(fs.readFileSync(id.slice('\0raw:'.length), 'utf8'))}`
      return null
    }
  }
  const alias = {
    // First: fs/promises must come before fs, which would take it as its subpath
    ...Object.fromEntries(Object.entries(SEASONS[year].shims).map(([name, file]) => [name, shim(file)])),
    // Upstream's sources, for the entry and the replacements
    'vscode-wpilib-src': src,
    // The IDE's host, for patches/
    'frc-dev-wasm-host': path.join(HERE, 'replacements', 'host.ts'),
    fs: shim('fs.js'),
    child_process: shim('child_process.js'),
    net: shim('net.js'),
    'node-fetch': shim('node-fetch.js'),
    ncp: shim('ncp.js'),
    mkdirp: shim('mkdirp.js'),
    'extract-zip': shim('extract-zip.js'),
    os: shim('os.js'),
    util: gitShim('small.js'),
    timers: gitShim('timers.js'),
    path: dep('path-browserify'),
    events: dep('events'),
    stream: dep('readable-stream'),
    string_decoder: dep('string_decoder'),
    buffer: dep('buffer')
  }
  const inject = { process: gitShim('process.js'), Buffer: [dep('buffer'), 'Buffer'] }
  if (SEASONS[year].globalFetch) inject.fetch = [shim('fetch.js'), 'fetch']

  const main = 'dist/browser/extension.js'
  const build = await rolldown({
    input: path.join(HERE, 'replacements', 'entry.ts'),
    platform: 'browser',
    external: ['vscode'],
    plugins: [replace],
    resolve: { modules: ['node_modules', path.join(HERE, 'node_modules')], alias },
    transform: { inject },
    onLog(level, log) {
      if (log.code === 'UNRESOLVED_IMPORT') throw new Error(log.message)
    }
  })
  await build.write({ file: path.join(out, main), format: 'cjs', minify: true, codeSplitting: false })
  await build.close()
  return main
}

// The manifest without the C++ parts, which need the C/C++ extension
function manifest(text, main) {
  const m = JSON.parse(text)
  const cpp = (command) => /Cpp/.test(command)
  delete m.main
  m.browser = `./${main}`
  m.contributes.commands = m.contributes.commands.filter((c) => !cpp(c.command))
  m.activationEvents = m.activationEvents.filter((e) => !cpp(e))
  m.contributes.views.explorer = m.contributes.views.explorer.filter((v) => v.id !== 'cppDependencies')
  return JSON.stringify(m, null, 2)
}

async function buildSeason(year) {
  const { version } = SEASONS[year]
  const repo = path.join(WORK, year)
  const out = path.join(ROOT, 'apps', 'ide', 'public', 'runtime', year, 'vscode-wpilib')
  fs.mkdirSync(WORK, { recursive: true })
  sources(year, repo)
  const files = await vsix(SEASONS[year])
  fs.rmSync(out, { recursive: true, force: true })
  const main = await bundle(year, path.join(repo, 'vscode-wpilib', 'src'), out)
  for (const [name, bytes] of Object.entries(files)) {
    if (!name.startsWith('extension/') || name.endsWith('/')) continue
    const file = name.slice('extension/'.length)
    if (file === 'package.json' || SKIP.some((re) => re.test(file))) continue
    fs.mkdirSync(path.join(out, path.dirname(file)), { recursive: true })
    fs.writeFileSync(path.join(out, file), bytes)
  }
  fs.writeFileSync(path.join(out, 'package.json'), manifest(new TextDecoder().decode(files['extension/package.json']), main))
  const list = fs.readdirSync(out, { recursive: true, withFileTypes: true }).filter((e) => e.isFile()).map((e) => path.relative(out, path.join(e.parentPath, e.name)).split(path.sep).join('/'))
  fs.writeFileSync(path.join(out, 'files.json'), JSON.stringify(list))
  console.log(`vscode-wpilib ${version}: ${list.length} files, ${fs.statSync(path.join(out, main)).size} bytes of code`)
}

for (const year of years.length ? years : WPILIB_YEARS) await buildSeason(year)
