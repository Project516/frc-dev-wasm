// The parts of node:fs the WPILib extension uses. The extension's own folder is read-only and served
// by the site, with its file list known up front, so it also answers the synchronous calls. Everything
// else is the workbench file system, which has no synchronous calls.
import { Buffer } from 'buffer'
import { Writable } from 'readable-stream'
import * as vscode from 'vscode'

let extension = { root: '/extension/', base: '', files: new Set(), dirs: new Set() }

// Serves `root` (the extension path) from `base`, a URL ending with a slash, with `files` relative to it
export function mount(root, base, files) {
  const dirs = new Set()
  for (const file of files) for (let i = file.indexOf('/'); i > 0; i = file.indexOf('/', i + 1)) dirs.add(file.slice(0, i))
  extension = { root: root.replace(/\/?$/, '/'), base, files: new Set(files), dirs }
}

const normalize = (path) => String(path).replace(/\/+$/, '') || '/'
const inExtension = (path) => normalize(path) === extension.root.slice(0, -1) || normalize(path).startsWith(extension.root)
const relative = (path) => normalize(path).slice(extension.root.length)

function nodeError(code, path, message = code) {
  return Object.assign(new Error(`${code}: ${message}, '${path}'`), { code, path: String(path) })
}

function toNodeError(error, path) {
  if (error?.code && typeof error.code === 'string' && error.code.startsWith('E')) return error
  const code =
    error instanceof vscode.FileSystemError
      ? { FileNotFound: 'ENOENT', FileExists: 'EEXIST', FileNotADirectory: 'ENOTDIR', FileIsADirectory: 'EISDIR', NoPermissions: 'EACCES' }[error.code] ?? 'EIO'
      : 'EIO'
  return nodeError(code, path, error?.message)
}

const uri = (path) => vscode.Uri.file(String(path))
const wrap = (path, work) => Promise.resolve().then(work).catch((error) => Promise.reject(toNodeError(error, path)))

class Stats {
  constructor(type, size = 0, mtime = 0) {
    this.type = type
    this.size = size
    this.mtimeMs = mtime
    this.mtime = new Date(mtime)
    this.mode = type & vscode.FileType.Directory ? 0o40755 : 0o100755
  }
  isDirectory() {
    return !!(this.type & vscode.FileType.Directory)
  }
  isFile() {
    return !!(this.type & vscode.FileType.File)
  }
  isSymbolicLink() {
    return false
  }
}

function extensionStat(path) {
  const rel = relative(path)
  if (rel === '' || extension.dirs.has(rel)) return new Stats(vscode.FileType.Directory)
  if (extension.files.has(rel)) return new Stats(vscode.FileType.File)
  throw nodeError('ENOENT', path, 'no such file or directory')
}

function extensionList(path) {
  extensionStat(path)
  const rel = relative(path)
  const prefix = rel ? `${rel}/` : ''
  const names = new Set()
  for (const entry of [...extension.files, ...extension.dirs]) {
    if (entry.startsWith(prefix) && !entry.slice(prefix.length).includes('/')) names.add(entry.slice(prefix.length))
  }
  return [...names].sort()
}

const readOnly = (path) => Promise.reject(nodeError('EROFS', path, 'read-only file system'))

const decode = (bytes, options) => {
  const encoding = typeof options === 'string' ? options : options?.encoding
  return encoding ? Buffer.from(bytes).toString(encoding) : Buffer.from(bytes)
}

async function readBytes(path) {
  if (!inExtension(path)) return vscode.workspace.fs.readFile(uri(path))
  extensionStat(path)
  const res = await fetch(new URL(relative(path), extension.base))
  if (!res.ok) throw nodeError('EIO', path, `HTTP ${res.status}`)
  return new Uint8Array(await res.arrayBuffer())
}

export const promises = {
  readFile: (path, options) => wrap(path, async () => decode(await readBytes(path), options)),
  writeFile: (path, data, options) =>
    inExtension(path)
      ? readOnly(path)
      : wrap(path, () => vscode.workspace.fs.writeFile(uri(path), typeof data === 'string' ? Buffer.from(data, typeof options === 'string' ? options : options?.encoding ?? 'utf8') : data)),
  copyFile: (from, to) => wrap(from, async () => promises.writeFile(to, await readBytes(from))),
  stat: (path) =>
    wrap(path, async () => {
      if (inExtension(path)) return extensionStat(path)
      const stat = await vscode.workspace.fs.stat(uri(path))
      return new Stats(stat.type, stat.size, stat.mtime)
    }),
  lstat: (path) => promises.stat(path),
  readdir: (path) => wrap(path, async () => (inExtension(path) ? extensionList(path) : (await vscode.workspace.fs.readDirectory(uri(path))).map(([name]) => name))),
  mkdir: (path) => (inExtension(path) ? readOnly(path) : wrap(path, () => vscode.workspace.fs.createDirectory(uri(path)))),
  unlink: (path) => (inExtension(path) ? readOnly(path) : wrap(path, () => vscode.workspace.fs.delete(uri(path)))),
  rename: (from, to) => wrap(from, () => vscode.workspace.fs.rename(uri(from), uri(to), { overwrite: true })),
  // Workspace files have no modes; gradlew runs without one
  chmod: async () => undefined
}

const callback =
  (operation) =>
  (...args) => {
    const done = args.pop()
    operation(...args).then(
      (result) => done(null, result),
      (error) => done(error)
    )
  }

export const readFile = callback(promises.readFile)
export const writeFile = callback(promises.writeFile)
export const copyFile = callback(promises.copyFile)
export const stat = callback(promises.stat)
export const lstat = callback(promises.lstat)
export const readdir = callback(promises.readdir)
export const mkdir = callback(promises.mkdir)
export const unlink = callback(promises.unlink)
export const rename = callback(promises.rename)
export const chmod = callback(promises.chmod)

// Node's fs.exists calls back with the answer only; util.promisify knows it by this symbol
export function exists(path, done) {
  promises.stat(path).then(
    () => done(true),
    () => done(false)
  )
}
exists[Symbol.for('nodejs.util.promisify.custom')] = (path) =>
  promises.stat(path).then(
    () => true,
    () => false
  )

export function existsSync(path) {
  if (!inExtension(path)) return false
  try {
    extensionStat(path)
    return true
  } catch {
    return false
  }
}

export function lstatSync(path) {
  if (!inExtension(path)) throw nodeError('ENOSYS', path, 'synchronous access to workspace files is not available in the browser')
  return extensionStat(path)
}
export const statSync = lstatSync

export function readFileSync(path) {
  if (inExtension(path)) extensionStat(path)
  throw nodeError('ENOSYS', path, 'synchronous reads are not available in the browser')
}

// Collects the bytes and writes them when the stream ends
export function createWriteStream(path) {
  const chunks = []
  const stream = new Writable({
    write(chunk, _encoding, next) {
      chunks.push(Buffer.from(chunk))
      next()
    },
    final(done) {
      promises.writeFile(path, Buffer.concat(chunks)).then(() => done(), done)
    }
  })
  return stream
}

export const constants = { S_IXUSR: 0o100, S_IXGRP: 0o10, S_IXOTH: 0o1, F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1 }

export default {
  promises,
  readFile,
  writeFile,
  copyFile,
  stat,
  lstat,
  readdir,
  mkdir,
  unlink,
  rename,
  chmod,
  exists,
  existsSync,
  lstatSync,
  statSync,
  readFileSync,
  createWriteStream,
  constants
}
