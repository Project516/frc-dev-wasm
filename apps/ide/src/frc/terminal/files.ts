import type * as vscode from 'vscode'

export interface Stat {
  dir: boolean
  size: number
  mtime: number
}

// The workspace file system, by absolute path
export class Files {
  constructor(private readonly api: typeof vscode) {}

  private uri(path: string) {
    return this.api.Uri.file(path)
  }

  async stat(path: string): Promise<Stat | undefined> {
    try {
      const s = await this.api.workspace.fs.stat(this.uri(path))
      return { dir: !!(s.type & this.api.FileType.Directory), size: s.size, mtime: s.mtime }
    } catch {
      return undefined
    }
  }

  async list(path: string): Promise<Array<[name: string, dir: boolean]>> {
    const entries = await this.api.workspace.fs.readDirectory(this.uri(path))
    return entries.map(([name, type]) => [name, !!(type & this.api.FileType.Directory)])
  }

  async read(path: string): Promise<Uint8Array> {
    return this.api.workspace.fs.readFile(this.uri(path))
  }

  async write(path: string, data: Uint8Array): Promise<void> {
    await this.api.workspace.fs.writeFile(this.uri(path), data)
  }

  async mkdir(path: string): Promise<void> {
    await this.api.workspace.fs.createDirectory(this.uri(path))
  }

  async copy(from: string, to: string, overwrite: boolean): Promise<void> {
    await this.api.workspace.fs.copy(this.uri(from), this.uri(to), { overwrite })
  }

  async rename(from: string, to: string, overwrite: boolean): Promise<void> {
    await this.api.workspace.fs.rename(this.uri(from), this.uri(to), { overwrite })
  }

  async remove(path: string, recursive: boolean): Promise<void> {
    await this.api.workspace.fs.delete(this.uri(path), { recursive, useTrash: false })
  }
}
