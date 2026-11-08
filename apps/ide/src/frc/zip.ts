import type { ZipOptions } from 'fflate'
import type * as vscode from 'vscode'
import { workspaceFolder } from '../workspace'
import { openFolder } from './projects'

const EXCLUDED_ROOT_DIRS = new Set(['build', '.gradle', 'bin'])
const STORED_EXTENSIONS = /\.(jar|zip|png|jpe?g|gz)$/i
const UNIX = 3
const EXECUTABLE_FILE = 0o100755 * 0x10000

function pickFile(accept: string): Promise<File | undefined> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = accept
    input.addEventListener('change', () => resolve(input.files?.[0]))
    input.addEventListener('cancel', () => resolve(undefined))
    input.click()
  })
}

async function exists(api: typeof vscode, uri: vscode.Uri): Promise<boolean> {
  return api.workspace.fs.stat(uri).then(() => true, () => false)
}

// Entries of a GitHub zip share one top-level folder
function stripSingleTopLevel(paths: string[]): { prefix: string; paths: string[] } {
  const tops = new Set(paths.map((p) => p.split('/')[0]))
  const prefix = tops.size === 1 && paths.every((p) => p.includes('/')) ? `${[...tops][0]}/` : ''
  return { prefix, paths: paths.map((p) => p.slice(prefix.length)) }
}

export async function importProjectFromZip(api: typeof vscode): Promise<void> {
  const file = await pickFile('.zip,application/zip')
  if (!file) return
  const { unzipSync } = await import('fflate')
  const entries = unzipSync(new Uint8Array(await file.arrayBuffer()), {
    filter: (entry) => !entry.name.endsWith('/') && !entry.name.startsWith('__MACOSX/')
  })
  const names = Object.keys(entries)
  if (names.length === 0) throw new Error('The zip file is empty')
  const { prefix, paths } = stripSingleTopLevel(names)

  const root = api.Uri.file(workspaceFolder.path)
  const base = (prefix ? prefix.slice(0, -1) : file.name.replace(/\.zip$/i, '')).replace(/[^\w.-]/g, '-')
  let suggestion = base
  for (let n = 1; await exists(api, api.Uri.joinPath(root, suggestion)); n++) suggestion = `${base}-${n}`
  const name = await api.window.showInputBox({
    title: 'Import Project from Zip',
    prompt: 'Project folder name',
    value: suggestion,
    validateInput: async (v) =>
      !/^[\w.-]+$/.test(v) ? 'Use letters, digits, dot, dash and underscore' : (await exists(api, api.Uri.joinPath(root, v))) ? 'A folder with this name exists' : undefined
  })
  if (!name) return

  const target = api.Uri.joinPath(root, name)
  await api.window.withProgress(
    { location: api.ProgressLocation.Notification, title: `Importing ${file.name}` },
    async () => {
      for (const [i, path] of paths.entries()) {
        const parts = path.split('/')
        if (parts.some((p) => p === '' || p === '..')) continue
        await api.workspace.fs.writeFile(api.Uri.joinPath(target, ...parts), entries[names[i]])
      }
    }
  )
  await openFolder(api, target.path)
}

async function readTree(
  api: typeof vscode,
  dir: vscode.Uri,
  prefix: string,
  out: Record<string, Uint8Array | [Uint8Array, ZipOptions]>
): Promise<void> {
  for (const [name, type] of await api.workspace.fs.readDirectory(dir)) {
    const path = `${prefix}${name}`
    if (!prefix && EXCLUDED_ROOT_DIRS.has(name)) continue
    const uri = api.Uri.joinPath(dir, name)
    if (type & api.FileType.Directory) {
      await readTree(api, uri, `${path}/`, out)
      continue
    }
    const data = await api.workspace.fs.readFile(uri)
    const options: ZipOptions = {}
    if (path.startsWith('.git/objects/') || STORED_EXTENSIONS.test(name)) options.level = 0
    if (path === 'gradlew') Object.assign(options, { os: UNIX, attrs: EXECUTABLE_FILE })
    out[path] = [data, options]
  }
}

function download(data: Uint8Array<ArrayBuffer>, filename: string): void {
  const url = URL.createObjectURL(new Blob([data], { type: 'application/zip' }))
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

export async function exportProjectAsZip(api: typeof vscode): Promise<void> {
  const folder = api.workspace.workspaceFolders?.[0]?.uri
  if (!folder) throw new Error('Open a robot project first')
  const name = folder.path.split('/').pop() || 'project'
  const data = await api.window.withProgress(
    { location: api.ProgressLocation.Notification, title: `Exporting ${name}` },
    async () => {
      const files: Record<string, Uint8Array | [Uint8Array, ZipOptions]> = {}
      await readTree(api, folder, '', files)
      const { zipSync } = await import('fflate')
      return zipSync(files)
    }
  )
  download(data as Uint8Array<ArrayBuffer>, `${name}.zip`)
}
