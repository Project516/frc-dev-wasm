import type { CpOptions, FileContent, FsStat, IFileSystem, MkdirOptions, RmOptions } from 'just-bash/browser'
import type { Files } from './files'
import { dirname, resolvePath } from './paths'

interface DirentEntry {
  name: string
  isFile: boolean
  isDirectory: boolean
  isSymbolicLink: boolean
}

const encoder = new TextEncoder()
const decoder = new TextDecoder()

const ERRORS: Array<[RegExp, string]> = [
  [/NotFound|ENOENT/i, 'ENOENT: no such file or directory'],
  [/IsADirectory|EISDIR/i, 'EISDIR: illegal operation on a directory'],
  [/NotADirectory|ENOTDIR/i, 'ENOTDIR: not a directory'],
  [/Exists|EEXIST/i, 'EEXIST: file already exists']
]

// Commands read the errno prefix to word their messages
function fail(error: unknown, op: string, path: string): never {
  const text = error instanceof Error ? `${(error as { code?: string }).code ?? ''} ${error.name} ${error.message}` : String(error)
  const known = ERRORS.find(([pattern]) => pattern.test(text))
  if (!known) throw error
  throw new Error(`${known[1]}, ${op} '${path}'`)
}

// just-bash looks for each command it runs as a file in these, so they never reach the workspace
const COMMAND_DIRS = /^\/(usr\/)?bin(\/|$)/

const missing = (op: string, path: string) => new Error(`ENOENT: no such file or directory, ${op} '${path}'`)

// The workspace as a just-bash file system. No symlinks, permissions or hard links.
// The project folder cannot be removed, so find . -delete empties it and stops.
export class WorkspaceFs implements IFileSystem {
  constructor(
    private readonly files: Files,
    private readonly project: string
  ) {}

  async readFile(path: string): Promise<string> {
    return decoder.decode(await this.readFileBuffer(path))
  }

  readFileBuffer(path: string): Promise<Uint8Array> {
    return this.files.read(path).catch((error) => fail(error, 'open', path))
  }

  async writeFile(path: string, content: FileContent): Promise<void> {
    const data = typeof content === 'string' ? encoder.encode(content) : content
    await this.files.write(path, data).catch((error) => fail(error, 'write', path))
  }

  async appendFile(path: string, content: FileContent): Promise<void> {
    const existing = await this.files.read(path).catch(() => new Uint8Array())
    const added = typeof content === 'string' ? encoder.encode(content) : content
    const joined = new Uint8Array(existing.length + added.length)
    joined.set(existing)
    joined.set(added, existing.length)
    await this.writeFile(path, joined)
  }

  async exists(path: string): Promise<boolean> {
    if (COMMAND_DIRS.test(path)) return false
    return (await this.files.stat(path)) !== undefined
  }

  async stat(path: string): Promise<FsStat> {
    const stat = COMMAND_DIRS.test(path) ? undefined : await this.files.stat(path)
    if (!stat) throw missing('stat', path)
    return { isFile: !stat.dir, isDirectory: stat.dir, isSymbolicLink: false, mode: stat.dir ? 0o755 : 0o644, size: stat.size, mtime: new Date(stat.mtime) }
  }

  lstat(path: string): Promise<FsStat> {
    return this.stat(path)
  }

  async mkdir(path: string, options?: MkdirOptions): Promise<void> {
    const stat = await this.files.stat(path)
    if (stat) {
      if (options?.recursive && stat.dir) return
      throw new Error(`EEXIST: ${stat.dir ? 'directory' : 'file'} already exists, mkdir '${path}'`)
    }
    if (!options?.recursive && !(await this.files.stat(dirname(path)))?.dir) throw missing('mkdir', path)
    await this.files.mkdir(path)
  }

  async createExclusive(path: string, options: { directory?: boolean }): Promise<void> {
    if (await this.files.stat(path)) throw new Error(`EEXIST: file already exists, open '${path}'`)
    if (options.directory) await this.mkdir(path)
    else await this.files.write(path, new Uint8Array())
  }

  async readdir(path: string): Promise<string[]> {
    return (await this.readdirWithFileTypes(path)).map((entry) => entry.name)
  }

  async readdirWithFileTypes(path: string): Promise<DirentEntry[]> {
    const entries = await this.files.list(path).catch((error) => fail(error, 'scandir', path))
    return entries.map(([name, dir]) => ({ name, isFile: !dir, isDirectory: dir, isSymbolicLink: false }))
  }

  async rm(path: string, options?: RmOptions): Promise<void> {
    if (resolvePath('/', path) === this.project) throw new Error(`EBUSY: resource busy or locked, rm '${path}'`)
    const stat = await this.files.stat(path)
    if (!stat) {
      if (options?.force) return
      throw missing('rm', path)
    }
    if (stat.dir && !options?.recursive && (await this.files.list(path)).length > 0) throw new Error(`ENOTEMPTY: directory not empty, rm '${path}'`)
    await this.files.remove(path, true)
  }

  async cp(src: string, dest: string, options?: CpOptions): Promise<void> {
    const stat = await this.files.stat(src)
    if (!stat) throw missing('cp', src)
    if (stat.dir && !options?.recursive) throw new Error(`EISDIR: is a directory, cp '${src}'`)
    await this.files.copy(src, dest, true)
  }

  async mv(src: string, dest: string): Promise<void> {
    if (!(await this.files.stat(src))) throw missing('mv', src)
    await this.files.rename(src, dest, true)
  }

  resolvePath(base: string, path: string): string {
    return resolvePath(base, path)
  }

  getAllPaths(): string[] {
    return []
  }

  async chmod(path: string): Promise<void> {
    await this.stat(path)
  }

  async utimes(path: string): Promise<void> {
    await this.stat(path)
  }

  async symlink(): Promise<void> {
    throw new Error('EPERM: symbolic links are not supported in the workspace')
  }

  async link(): Promise<void> {
    throw new Error('EPERM: hard links are not supported in the workspace')
  }

  async readlink(path: string): Promise<string> {
    throw new Error(`EINVAL: invalid argument, readlink '${path}'`)
  }

  async realpath(path: string): Promise<string> {
    await this.stat(path)
    return resolvePath('/', path)
  }
}
