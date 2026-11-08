import type * as vscode from 'vscode'

type Options = string | { encoding?: string; mode?: number } | undefined

export interface GitFs {
  promises: {
    readFile(path: string, options?: Options): Promise<Uint8Array | string>
    writeFile(path: string, data: Uint8Array | string, options?: Options): Promise<void>
    unlink(path: string): Promise<void>
    readdir(path: string): Promise<string[]>
    mkdir(path: string): Promise<void>
    rmdir(path: string): Promise<void>
    stat(path: string): Promise<Stats>
    lstat(path: string): Promise<Stats>
    readlink(path: string): Promise<string>
    symlink(target: string, path: string): Promise<void>
    chmod(path: string, mode: number): Promise<void>
  }
}

interface Stats {
  type: 'file' | 'dir'
  mode: number
  size: number
  mtimeMs: number
  ctimeMs: number
  uid: number
  gid: number
  ino: number
  dev: number
  isFile(): boolean
  isDirectory(): boolean
  isSymbolicLink(): boolean
}

const ERROR_CODES: Array<[RegExp, string]> = [
  [/NotFound/i, 'ENOENT'],
  [/FileExists/i, 'EEXIST'],
  [/NotADirectory/i, 'ENOTDIR'],
  [/IsADirectory/i, 'EISDIR'],
  [/NoPermissions/i, 'EACCES']
]

function toFsError(error: unknown, path: string): Error {
  const text = `${(error as { code?: string }).code ?? ''} ${(error as Error).name ?? ''} ${(error as Error).message ?? ''}`
  const code = ERROR_CODES.find(([pattern]) => pattern.test(text))?.[1] ?? 'EIO'
  return Object.assign(new Error(`${code}: ${path}`), { code })
}

// isomorphic-git passes paths such as /dir/. that node's fs resolves but URIs do not
function normalize(path: string): string {
  const parts: string[] = []
  for (const part of path.split('/')) {
    if (part === '..') parts.pop()
    else if (part && part !== '.') parts.push(part)
  }
  return `/${parts.join('/')}`
}

const encoder = new TextEncoder()
const decoder = new TextDecoder()

// Git file modes come from the repository, not from the file system: the browser has no execute bit,
// so `executable` holds the paths git should report as 0o100755.
export function createGitFs(api: typeof vscode, executable: Set<string>): GitFs {
  const uri = (path: string) => api.Uri.file(normalize(path))
  const wrap = async <T>(path: string, task: () => PromiseLike<T>): Promise<T> => {
    try {
      return await task()
    } catch (error) {
      throw toFsError(error, path)
    }
  }

  const stat = (path: string) =>
    wrap(path, async (): Promise<Stats> => {
      const s = await api.workspace.fs.stat(uri(path))
      const dir = !!(s.type & api.FileType.Directory)
      return {
        type: dir ? 'dir' : 'file',
        mode: dir ? 0o40000 : executable.has(normalize(path)) ? 0o100755 : 0o100644,
        size: s.size,
        mtimeMs: s.mtime,
        ctimeMs: s.ctime,
        uid: 1,
        gid: 1,
        ino: 0,
        dev: 1,
        isFile: () => !dir,
        isDirectory: () => dir,
        isSymbolicLink: () => false
      }
    })

  const writeFile = (path: string, data: Uint8Array | string, options?: Options) => {
    const mode = typeof options === 'object' ? options.mode : undefined
    if (mode !== undefined) {
      if (mode & 0o111) executable.add(normalize(path))
      else executable.delete(normalize(path))
    }
    return wrap(path, () => api.workspace.fs.writeFile(uri(path), typeof data === 'string' ? encoder.encode(data) : data))
  }

  return {
    promises: {
      async readFile(path, options) {
        const data = await wrap(path, () => api.workspace.fs.readFile(uri(path)))
        const encoding = typeof options === 'string' ? options : options?.encoding
        return encoding ? decoder.decode(data) : data
      },
      writeFile,
      unlink: (path) => wrap(path, () => api.workspace.fs.delete(uri(path), { recursive: false, useTrash: false })),
      rmdir: (path) => wrap(path, () => api.workspace.fs.delete(uri(path), { recursive: false, useTrash: false })),
      readdir: (path) => wrap(path, async () => (await api.workspace.fs.readDirectory(uri(path))).map(([name]) => name)),
      mkdir: (path) => wrap(path, () => api.workspace.fs.createDirectory(uri(path))),
      stat,
      lstat: stat,
      readlink: (path) => Promise.reject(toFsError({ message: 'NotFound' }, path)),
      // No symlinks in the browser: the link target is written as the file content, like core.symlinks=false
      symlink: (target, path) => writeFile(path, target),
      async chmod(path, mode) {
        if (mode & 0o111) executable.add(normalize(path))
        else executable.delete(normalize(path))
      }
    }
  }
}
