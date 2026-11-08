// tar -c, -x and -t with -z, -v, -f and -C. just-bash's own tar is not in its browser build.
import { gunzipSync, gzipSync } from 'fflate'
import { packTar, unpackTar, type TarEntry } from 'modern-tar'
import type { Files } from './files'
import type { Io } from './io'
import { dirname, join, resolvePath } from './paths'

export interface TarContext {
  files: Files
  cwd: string
}

// Entries for a file or a whole directory, named as given on the command line
async function gather(files: Files, name: string, path: string, entries: TarEntry[]): Promise<void> {
  const stat = await files.stat(path)
  if (!stat) throw new Error(`${name}: Cannot stat: No such file or directory`)
  if (!stat.dir) {
    entries.push({ header: { name, size: stat.size, type: 'file' }, body: await files.read(path) })
    return
  }
  entries.push({ header: { name: `${name}/`, size: 0, type: 'directory' } })
  for (const [child] of await files.list(path)) await gather(files, `${name}/${child}`, join(path, child), entries)
}

export async function tar(args: string[], io: Io, ctx: TarContext): Promise<number> {
  let mode: 'c' | 'x' | 't' | undefined
  let gzip = false
  let verbose = false
  let archive: string | undefined
  let base = ctx.cwd
  const operands: string[] = []
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]
    if (arg === '-C' || arg === '--directory') base = resolvePath(ctx.cwd, args[++i] ?? '')
    else if (arg === '-f' || arg === '--file') archive = args[++i]
    else if (arg.startsWith('-') && arg !== '-') {
      for (const letter of arg.replace(/^-+/, '')) {
        if (letter === 'c' || letter === 'x' || letter === 't') mode = letter
        else if (letter === 'z') gzip = true
        else if (letter === 'v') verbose = true
        else if (letter === 'f') archive = args[++i]
      }
    } else if (i === 0 && !archive && /^[cxtzvf]+$/.test(arg)) {
      for (const letter of arg) {
        if (letter === 'f') archive = args[++i]
        else if (letter === 'z') gzip = true
        else if (letter === 'v') verbose = true
        else mode = letter as 'c' | 'x' | 't'
      }
    } else operands.push(arg)
  }
  if (!mode) {
    io.stderr.write('tar: You must specify one of -c, -x or -t\n')
    return 2
  }
  const { files } = ctx
  const target = archive && archive !== '-' ? resolvePath(ctx.cwd, archive) : undefined
  try {
    if (mode === 'c') {
      const entries: TarEntry[] = []
      for (const operand of operands) await gather(files, operand.replace(/\/+$/, ''), resolvePath(base, operand), entries)
      if (verbose) for (const entry of entries) io.stderr.write(`${entry.header.name}\n`)
      let data: Uint8Array = await packTar(entries)
      if (gzip) data = gzipSync(data)
      if (target) await files.write(target, data)
      else io.stdout.write(data)
      return 0
    }
    let data: Uint8Array = target ? await files.read(target) : (io.stdin ?? new Uint8Array())
    if (gzip || (data[0] === 0x1f && data[1] === 0x8b)) data = gunzipSync(data)
    for (const entry of await unpackTar(data)) {
      const name = entry.header.name
      if (verbose || mode === 't') io.stdout.write(`${name}\n`)
      if (mode === 't') continue
      const path = resolvePath(base, name)
      if (!path.startsWith(base === '/' ? '/' : `${base}/`) && path !== base) throw new Error(`${name}: Member name contains '..'`)
      if (entry.header.type === 'directory') await files.mkdir(path)
      else {
        await files.mkdir(dirname(path))
        await files.write(path, entry.data ?? new Uint8Array())
      }
    }
    return 0
  } catch (error) {
    io.stderr.write(`tar: ${error instanceof Error ? error.message : String(error)}\n`)
    return 2
  }
}
