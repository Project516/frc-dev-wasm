export interface LazyFile {
  path: string
  size: number
  mtime: number
  read(buffer: Uint8Array, position: number): number
}

export interface Changes {
  removed: string[]
  dirs: string[]
  writes: Array<{ path: string; data?: Uint8Array; from?: string }>
}

export interface Mount {
  root: string
  dirs: string[]
  files: LazyFile[]
}

export interface RunOptions {
  args: string[]
  env: Record<string, string>
  cwd: string
  mounts: Mount[]
  input?: Uint8Array
  stdout(byte: number): void
  stderr(byte: number): void
  moduleOptions?: { frcCorsProxy?: string; frcAuth?(url: string): string | undefined }
  tty?: boolean
  columns?: number
}

export class GitHost {
  constructor(createGit: (opts: object) => Promise<unknown>, wasmModule: WebAssembly.Module)
  run(options: RunOptions): { status: number; changes: Changes[] }
}
