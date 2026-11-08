// The parts of node:fs that VS Code's Git extension uses, over the workbench file system
import { Buffer } from 'buffer'
import { Readable } from 'readable-stream'
import * as vscode from 'vscode'

const uri = (path) => vscode.Uri.file(String(path))

function toNodeError(error, path) {
  const code = error instanceof vscode.FileSystemError ? { FileNotFound: 'ENOENT', FileExists: 'EEXIST', FileNotADirectory: 'ENOTDIR', FileIsADirectory: 'EISDIR', NoPermissions: 'EACCES' }[error.code] ?? 'EIO' : 'EIO'
  return Object.assign(new Error(`${code}: ${error.message}, '${path}'`), { code, path })
}
const wrap = (path, work) => work().catch((error) => Promise.reject(toNodeError(error, path)))

class Stats {
  constructor(stat) {
    this.type = stat.type
    this.size = stat.size
    this.mtimeMs = stat.mtime
    this.ctimeMs = stat.ctime
    this.mtime = new Date(stat.mtime)
    this.ctime = new Date(stat.ctime)
    this.mode = stat.type & vscode.FileType.Directory ? 0o40755 : 0o100644
  }
  isDirectory() {
    return !!(this.type & vscode.FileType.Directory)
  }
  isFile() {
    return !!(this.type & vscode.FileType.File)
  }
  isSymbolicLink() {
    return !!(this.type & vscode.FileType.SymbolicLink)
  }
}

class Dirent {
  constructor(name, type) {
    this.name = name
    this.type = type
  }
  isDirectory() {
    return !!(this.type & vscode.FileType.Directory)
  }
  isFile() {
    return !!(this.type & vscode.FileType.File)
  }
  isSymbolicLink() {
    return !!(this.type & vscode.FileType.SymbolicLink)
  }
}

const decode = (bytes, options) => {
  const encoding = typeof options === 'string' ? options : options?.encoding
  return encoding ? Buffer.from(bytes).toString(encoding) : Buffer.from(bytes)
}

export const promises = {
  readFile: (path, options) => wrap(path, async () => decode(await vscode.workspace.fs.readFile(uri(path)), options)),
  writeFile: (path, data) => wrap(path, () => vscode.workspace.fs.writeFile(uri(path), typeof data === 'string' ? Buffer.from(data) : data)),
  stat: (path) => wrap(path, async () => new Stats(await vscode.workspace.fs.stat(uri(path)))),
  lstat: (path) => promises.stat(path),
  readdir: (path, options) =>
    wrap(path, async () => {
      const entries = await vscode.workspace.fs.readDirectory(uri(path))
      return options?.withFileTypes ? entries.map(([name, type]) => new Dirent(name, type)) : entries.map(([name]) => name)
    }),
  mkdir: (path) => wrap(path, () => vscode.workspace.fs.createDirectory(uri(path))),
  unlink: (path) => wrap(path, () => vscode.workspace.fs.delete(uri(path))),
  rm: (path, options) => wrap(path, () => vscode.workspace.fs.delete(uri(path), { recursive: !!options?.recursive })),
  rename: (from, to) => wrap(from, () => vscode.workspace.fs.rename(uri(from), uri(to), { overwrite: true })),
  // No symbolic links in the browser workspace
  realpath: async (path) => String(path)
}

const callback = (operation) => (...args) => {
  const done = args.pop()
  operation(...args).then((result) => done(null, result), (error) => done(error))
}

export const readFile = callback(promises.readFile)
export const writeFile = callback(promises.writeFile)
export const stat = callback(promises.stat)
export const lstat = callback(promises.lstat)
export const readdir = callback(promises.readdir)
export const mkdir = callback(promises.mkdir)
export const unlink = callback(promises.unlink)
export const realpath = Object.assign(callback(promises.realpath), { native: callback(promises.realpath) })
export const exists = (path, done) => promises.stat(path).then(() => done(true), () => done(false))
// Synchronous calls cannot reach the workbench file system. The extension only makes the others on
// Windows and for its IPC server.
export const existsSync = () => false
const synchronous = (name) => () => {
  throw new Error(`fs.${name} is not available in the browser`)
}
export const readFileSync = synchronous('readFileSync')
export const writeFileSync = synchronous('writeFileSync')
export const unlinkSync = synchronous('unlinkSync')
export const renameSync = synchronous('renameSync')

export function createReadStream(path, options) {
  const stream = new Readable({ read() {} })
  promises.readFile(path, options).then(
    (data) => {
      stream.push(data)
      stream.push(null)
    },
    (error) => stream.destroy(error)
  )
  return stream
}

export default { promises, readFile, writeFile, stat, lstat, readdir, mkdir, unlink, realpath, exists, existsSync, readFileSync, writeFileSync, unlinkSync, renameSync, createReadStream, Stats, Dirent }
