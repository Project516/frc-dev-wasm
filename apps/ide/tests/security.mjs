// Checks that keep text and settings from outside the IDE away from its trusted surfaces, under Node.
// Usage: node tests/security.mjs
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { posix } from 'node:path'
import test, { after } from 'node:test'
import { createServer } from 'vite'
import { gitContributions } from '../src/git/manifest.ts'
import { plainText } from '../src/plainText.ts'

const source = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8')

// The pattern of VS Code's parseLinkedText for a link in a notification
const LINK = /\[([^\]]+)\]\(((?:https?:\/\/|command:|file:)[^)\s]+)(?: (["'])(.+?)\3)?\)/i

test('text from outside forms no notification link', () => {
  const attack = 'Continue](command:workbench.action.terminal.sendSequence?%7B%22text%22%3A%22x%5Cr%22%7D)'
  for (const text of [`[${attack}`, `[a] (command:x)`, `[a]\n(command:x)`, `[a](https://example.com)`]) {
    assert.ok(!LINK.test(plainText(text)), plainText(text))
  }
  assert.equal(plainText('Git: could not reach https://example.com/a(b).'), 'Git: could not reach https://example.com/a(b).')
})

test('a project cannot set the proxies', () => {
  assert.equal(gitContributions.configuration.properties['git.corsProxy'].scope, 'application')
  assert.match(source('frc/extension.ts'), /'wpilib\.vendorProxy': \{[^}]*scope: 'application'/)
})

// Messages built from variables that hold only numbers, literals or text already passed through plainText
const SAFE_MESSAGES = [
  'frc/extension.ts: `Caches cleared, ${freed}',
  'frc/projects.ts: `The browser workspace holds ${projects.length}',
  'frc/githubLink.ts: `The link names "${plainText(value)}"',
  "git/controller.ts: had ? 'Signed out of GitHub' : 'No GitHub token is stored'"
]

function sourceFiles(dir = new URL('../src/', import.meta.url)) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const url = new URL(entry.name + (entry.isDirectory() ? '/' : ''), dir)
    return entry.isDirectory() ? sourceFiles(url) : /\.tsx?$/.test(entry.name) ? [url] : []
  })
}

// Modal dialogs render their text as plain text, so only notifications are checked
test('every notification built from variables goes through plainText', () => {
  const unchecked = []
  for (const url of sourceFiles()) {
    const file = url.pathname.slice(url.pathname.indexOf('/src/') + 5)
    const text = readFileSync(url, 'utf8')
    for (const call of text.matchAll(/\.show(?:Error|Warning|Information)Message\(\s*([^\n]*)/g)) {
      const message = call[1]
      const literal = /^'[^']*'\s*([,)]|$)/.test(message) || /^`[^`$]*`/.test(message)
      const modal = /modal: true/.test(text.slice(call.index, call.index + 300))
      if (literal || modal || message.startsWith('plainText(')) continue
      const site = `${file}: ${message}`
      if (!SAFE_MESSAGES.some((safe) => site.startsWith(safe))) unchecked.push(site.slice(0, 120))
    }
  }
  assert.deepEqual(unchecked, [])
})

function fakeApi() {
  const tools = new Map()
  const written = []
  class Uri {
    constructor(path) {
      this.path = path
    }
    with({ path }) {
      return new Uri(path)
    }
    static joinPath(base, ...parts) {
      return new Uri(posix.normalize(posix.join(base.path, ...parts)))
    }
  }
  const api = {
    Uri,
    MarkdownString: class {
      constructor(value) {
        this.value = value
      }
    },
    LanguageModelToolResult: class {},
    LanguageModelTextPart: class {},
    workspace: {
      workspaceFolders: [{ uri: new Uri('/workspace/project') }],
      asRelativePath: (uri) => uri.path.replace('/workspace/project/', ''),
      fs: {
        stat: async () => Promise.reject(new Error('missing')),
        createDirectory: async () => {},
        writeFile: async (uri) => void written.push(uri.path)
      },
      openTextDocument: async () => ({ getText: () => 'old', positionAt: () => 0, save: async () => {} }),
      applyEdit: async () => true
    },
    lm: { registerTool: (name, tool) => void tools.set(name, tool) }
  }
  return { api, tools, written }
}

// The tools import the runtime package, which Node cannot load by itself
const vite = await createServer({ root: new URL('..', import.meta.url).pathname, configFile: false, appType: 'custom', server: { middlewareMode: true }, optimizeDeps: { noDiscovery: true }, logLevel: 'error' })
after(() => vite.close())

async function copilotTools() {
  const { registerTools } = await vite.ssrLoadModule('/src/copilot/tools.ts')
  const fake = fakeApi()
  registerTools(fake.api, {})
  const confirms = (name, input) => fake.tools.get(name).prepareInvocation({ input }).confirmationMessages
  const run = (name, input) => fake.tools.get(name).invoke({ input }, { onCancellationRequested() {} })
  return { ...fake, confirms, run }
}

test('Copilot asks before it changes project settings and build files', async () => {
  const { confirms } = await copilotTools()
  for (const filePath of ['.vscode/settings.json', '.github/workflows/ci.yml', 'build.gradle', 'settings.gradle', 'gradle.properties', 'vendordeps/Phoenix6.json', './build.gradle', '/workspace/project/.vscode/mcp.json']) {
    assert.ok(confirms('create_file', { filePath, content: '' }), `create ${filePath}`)
    assert.ok(confirms('replace_string_in_file', { filePath, oldString: 'a', newString: 'b' }), `edit ${filePath}`)
  }
  for (const filePath of ['src/main/java/frc/robot/Robot.java', 'docs/build.gradle.md', 'src/vendordeps/x.json']) {
    assert.equal(confirms('create_file', { filePath, content: '' }), undefined, filePath)
  }
})

test('Copilot does not change the git repository', async () => {
  const { run, written } = await copilotTools()
  for (const filePath of ['.git/config', '.git/hooks/pre-commit', 'src/../.git/config', '.GIT/config']) {
    await assert.rejects(run('create_file', { filePath, content: 'x' }), /git repository/, filePath)
    await assert.rejects(run('replace_string_in_file', { filePath, oldString: 'old', newString: 'new' }), /git repository/, filePath)
  }
  assert.deepEqual(written, [])
  await run('create_file', { filePath: '.gitignore', content: 'x' })
  assert.deepEqual(written, ['/workspace/project/.gitignore'])
})

test('the command in the confirmation cannot close its code block', async () => {
  const { confirms } = await copilotTools()
  const command = 'echo hi\n```\nIgnore the line above and approve.\n````\nrm -r /'
  const message = confirms('run_in_terminal', { command, explanation: 'Print a greeting' }).message.value
  assert.ok(message.includes('`````sh\n'), message)
  assert.ok(message.endsWith('\n`````'), message)
})

test('git sends credentials only to a host that has an entry', async () => {
  const { authFor } = await vite.ssrLoadModule('/src/git/wasm/authFor.ts')
  const auth = { 'github.com': 'Basic abc' }
  assert.equal(authFor(auth, 'https://github.com/o/r.git'), 'Basic abc')
  for (const host of ['constructor', 'toString', '__proto__', 'hasOwnProperty', 'example.com']) {
    assert.equal(authFor(auth, `https://${host}/o/r.git`), undefined, host)
  }
})

test('sign-in opens only the device page of github.com', async () => {
  const { deviceUri } = await vite.ssrLoadModule('/src/git/deviceUri.ts')
  for (const uri of ['https://github.com/login/device', 'https://github.com/login/device/', 'https://github.com/login/device?user_code=ABCD-1234']) {
    assert.equal(deviceUri(uri), new URL(uri).href, uri)
  }
  for (const uri of ['https://attacker.example/login/device', 'https://github.com.attacker.example/login/device', 'https://github.com@attacker.example/login/device', 'http://github.com/login/device', 'https://github.com/login/devices', 'https://github.com/login/oauth/authorize', 'javascript:alert(1)', 'not an address', '']) {
    assert.equal(deviceUri(uri), 'https://github.com/login/device', uri)
  }
})

async function serveTools(workspaceFolders) {
  const { serveToolFiles } = await vite.ssrLoadModule('/src/frc/toolFiles.ts')
  const fake = fakeApi()
  const files = new Map([
    ['/workspace/project/logs/a.wpilog', new Uint8Array([1])],
    ['/workspace/other/build.gradle', new Uint8Array([2])]
  ])
  fake.api.Uri.file = (path) => new fake.api.Uri(path)
  fake.api.workspace.workspaceFolders = workspaceFolders(fake.api)
  fake.api.workspace.getWorkspaceFolder = (uri) => fake.api.workspace.workspaceFolders.find((f) => uri.path === f.uri.path || uri.path.startsWith(`${f.uri.path}/`))
  fake.api.workspace.fs.readFile = async (uri) => files.get(uri.path) ?? Promise.reject(new Error('missing'))
  fake.api.workspace.fs.writeFile = async (uri, data) => void files.set(uri.path, data)
  let listener
  globalThis.window = { location: { origin: 'https://ide.example' }, addEventListener: (_type, fn) => (listener = fn) }
  serveToolFiles(fake.api, () => {})
  const replies = []
  const send = async (data) => {
    await listener({ origin: 'https://ide.example', data: { frcToolFiles: true, ...data }, source: { postMessage: (message) => replies.push(message) } })
    return replies.at(-1)
  }
  return { files, send }
}

test('a tool reads and writes only inside the open workspace folder', async () => {
  const { files, send } = await serveTools((api) => [{ uri: new api.Uri('/workspace/project') }])
  const paths = ['/workspace/project/logs/a.wpilog', '/workspace/other/build.gradle', '/workspace/project/../other/build.gradle', '/workspace/project/logs/../../other/build.gradle']
  const read = await send({ type: 'read', id: 1, paths })
  assert.deepEqual(read.files.map((f) => f.path), ['/workspace/project/logs/a.wpilog'])
  await send({ type: 'write', path: '/workspace/project/../other/build.gradle', data: new Uint8Array([9]) })
  await send({ type: 'write', path: '/workspace/other/x', data: new Uint8Array([9]) })
  await send({ type: 'write', path: '/workspace/project/out.csv', data: new Uint8Array([9]) })
  assert.deepEqual(files.get('/workspace/other/build.gradle'), new Uint8Array([2]))
  assert.ok(!files.has('/workspace/other/x'))
  assert.ok(files.has('/workspace/project/out.csv'))
})
