// Builds VS Code's Git, Git Base and GitHub extensions for the browser into
// apps/ide/public/runtime/vscode-git/<name>/: their sources at the commit of the workbench, bundled
// with Node's modules replaced by shims/.
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { rolldown } from 'rolldown'

// The commit of VS Code that @codingame/monaco-vscode-api is built from
const VSCODE_COMMIT = 'c4453f8ea430795f941c1b2ff6c9321135a74a02'
const HERE = import.meta.dirname
const ROOT = path.resolve(HERE, '..', '..')
const WORK = path.join(ROOT, '.cache', 'vscode-git')
const REPO = path.join(WORK, 'vscode')
const OUT = path.join(ROOT, 'apps', 'ide', 'public', 'runtime', 'vscode-git')

const git = (...args) => execFileSync('git', args, { cwd: REPO, stdio: 'inherit' })

function sources() {
  const head = fs.existsSync(path.join(REPO, '.git')) ? execFileSync('git', ['rev-parse', 'HEAD'], { cwd: REPO, encoding: 'utf8' }).trim() : ''
  if (head !== VSCODE_COMMIT) {
    fs.rmSync(REPO, { recursive: true, force: true })
    fs.mkdirSync(REPO, { recursive: true })
    git('init', '-q')
    git('remote', 'add', 'origin', 'https://github.com/microsoft/vscode.git')
    git('fetch', '-q', '--depth', '1', '--filter=blob:none', 'origin', VSCODE_COMMIT)
  }
  // Files are downloaded as the checkout needs them
  git('sparse-checkout', 'set', '--no-cone', '/extensions/git/', '/extensions/git-base/', '/extensions/github/', '/extensions/tsconfig.base.json')
  git('checkout', '-q', VSCODE_COMMIT)
}

sources()
const shim = (name) => path.join(HERE, 'shims', name)
const dep = (name) => path.join(HERE, 'node_modules', name)
const alias = {
  child_process: shim('child_process.js'),
  fs: shim('fs.js'),
  'fs/promises': shim('fs-promises.js'),
  os: shim('os.js'),
  http: shim('unavailable.js'),
  crypto: shim('unavailable.js'),
  '@vscode/fs-copyfile': shim('unavailable.js'),
  '@vscode/extension-telemetry': shim('unavailable.js'),
  url: shim('small.js'),
  querystring: shim('small.js'),
  util: shim('small.js'),
  which: shim('which.js'),
  https: shim('https.js'),
  tunnel: shim('unavailable.js'),
  timers: shim('timers.js'),
  path: dep('path-browserify'),
  events: dep('events'),
  stream: dep('readable-stream'),
  string_decoder: dep('string_decoder'),
  buffer: dep('buffer')
}

// Bundles extensions/<name>/src/<entry> as the manifest's browser entry, and copies the files the
// manifest refers to
async function build(name, entry, copy, nlsEdits = () => ({})) {
  const ext = path.join(REPO, 'extensions', name)
  const out = path.join(OUT, name)
  const main = 'dist/browser/extension.js'
  const bundle = await rolldown({
    input: path.join(ext, 'src', entry),
    platform: 'browser',
    external: ['vscode'],
    // The extension's sources are outside this package, so its node_modules is searched as well
    resolve: { modules: ['node_modules', path.join(HERE, 'node_modules')], alias },
    transform: {
      define: { __dirname: JSON.stringify(`/${path.posix.dirname(main)}`) },
      inject: { process: shim('process.js'), Buffer: [dep('buffer'), 'Buffer'] }
    },
    onLog(level, log) {
      if (log.code === 'UNRESOLVED_IMPORT') throw new Error(log.message)
    }
  })
  // One file: the extension host loads only the browser entry
  await bundle.write({ file: path.join(out, main), format: 'cjs', minify: true, codeSplitting: false })
  await bundle.close()
  const manifest = JSON.parse(fs.readFileSync(path.join(ext, 'package.json'), 'utf8'))
  // The bundle is CommonJS
  delete manifest.main
  delete manifest.type
  manifest.browser = `./${main}`
  fs.writeFileSync(path.join(out, 'package.json'), JSON.stringify(manifest, null, 2))
  // The workbench takes plain strings, not { message, comment } objects
  const nls = JSON.parse(fs.readFileSync(path.join(ext, 'package.nls.json'), 'utf8'))
  for (const [key, value] of Object.entries(nls)) if (typeof value === 'object') nls[key] = value.message
  Object.assign(nls, nlsEdits(nls))
  fs.writeFileSync(path.join(out, 'package.nls.json'), JSON.stringify(nls, null, 2))
  for (const file of copy) fs.cpSync(path.join(ext, file), path.join(out, file), { recursive: true })
  const files = fs.readdirSync(out, { recursive: true, withFileTypes: true }).filter((e) => e.isFile()).map((e) => path.relative(out, path.join(e.parentPath, e.name)))
  fs.writeFileSync(path.join(out, 'files.json'), JSON.stringify(files))
  console.log(`${name}: ${files.length} files, ${fs.statSync(path.join(out, main)).size} bytes of code`)
}

fs.rmSync(OUT, { recursive: true, force: true })
await build('git-base', 'extension.ts', ['languages', 'syntaxes', 'resources'])
// A project folder is always open, so the empty window's Clone button would never show
await build('git', 'main.ts', ['resources'], (nls) => {
  const folder = nls['view.workbench.scm.folder']
  if (!folder.includes('\nTo learn more')) throw new Error('view.workbench.scm.folder changed upstream')
  const clone = '\nOr clone a repository into a new project.\n[Clone Repository](command:git.cloneRecursive)'
  return { 'view.workbench.scm.folder': folder.replace('\nTo learn more', `${clone}\nTo learn more`) }
})
await build('github', 'extension.ts', ['images', 'markdown.css'])
