// cp -r, mv and rm -r on the workspace file system. uutils cannot do these on WASI (directory
// copies are unsupported), and a snapshot of a whole tree is the wrong tool for moving or deleting it.
import type { Files } from './files.ts'
import type { Io } from './io.ts'
import { basename, dirname, join, resolvePath } from './paths.ts'

export interface FileOpsContext {
  files: Files
  cwd: string
}

const hasLetter = (args: string[], letters: string, long: string[]) =>
  args.some((a) => (a.startsWith('--') ? long.includes(a) : /^-[a-zA-Z]+$/.test(a) && [...letters].some((l) => a.includes(l))))

export const isRecursiveCopy = (args: string[]) => hasLetter(args, 'rRa', ['--recursive', '--archive'])
export const isRecursiveRemove = (args: string[]) => hasLetter(args, 'rR', ['--recursive'])

function parseOperands(args: string[]) {
  const operands: string[] = []
  let target: string | undefined
  let noClobber = false
  let force = false
  let noTargetDirectory = false
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]
    if (arg === '--') {
      operands.push(...args.slice(i + 1))
      break
    }
    if (arg === '-t') target = args[++i]
    else if (arg.startsWith('--target-directory=')) target = arg.slice(19)
    else if (arg === '--no-clobber') noClobber = true
    else if (arg === '--force') force = true
    else if (arg === '--no-target-directory') noTargetDirectory = true
    else if (arg.startsWith('-') && arg.length > 1) {
      if (!arg.startsWith('--') && arg.includes('n')) noClobber = true
      if (!arg.startsWith('--') && arg.includes('f')) force = true
      if (!arg.startsWith('--') && arg.includes('T')) noTargetDirectory = true
    } else operands.push(arg)
  }
  return { operands, target, noClobber, force, noTargetDirectory }
}

// Operands resolving to / are refused for rm, before anything runs
export function removesRoot(args: string[], cwd: string): boolean {
  return args.some((a) => !a.startsWith('-') && resolvePath(cwd, a) === '/')
}

async function copyTree(ctx: FileOpsContext, io: Io, from: string, to: string, dir: boolean, noClobber: boolean): Promise<boolean> {
  const { files } = ctx
  const target = await files.stat(to)
  if (!dir) {
    if (target?.dir) {
      io.stderr.write(`cp: cannot overwrite directory '${to}' with non-directory\n`)
      return false
    }
    if (!(target && noClobber)) await files.copy(from, to, true)
    return true
  }
  if (!target) {
    await files.copy(from, to, false)
    return true
  }
  if (!target.dir) {
    io.stderr.write(`cp: cannot overwrite non-directory '${to}' with directory '${from}'\n`)
    return false
  }
  let ok = true
  for (const [name, childDir] of await files.list(from)) {
    ok = (await copyTree(ctx, io, join(from, name), join(to, name), childDir, noClobber)) && ok
  }
  return ok
}

// Merges into an existing target like cp does, and never deletes anything there
export async function copyRecursive(args: string[], io: Io, ctx: FileOpsContext): Promise<number> {
  const { files, cwd } = ctx
  const { operands, target, noClobber, noTargetDirectory } = parseOperands(args)
  const destination = target ?? operands.pop()
  if (!destination || operands.length === 0) {
    io.stderr.write('cp: missing file operand\n')
    return 1
  }
  const destPath = resolvePath(cwd, destination)
  const destStat = await files.stat(destPath)
  if (operands.length > 1 && !destStat?.dir) {
    io.stderr.write(`cp: target '${destination}' is not a directory\n`)
    return 1
  }
  let status = 0
  for (const operand of operands) {
    const from = resolvePath(cwd, operand)
    const stat = await files.stat(from)
    if (!stat) {
      io.stderr.write(`cp: cannot stat '${operand}': No such file or directory\n`)
      status = 1
      continue
    }
    // "a/." means the contents of a
    const contents = stat.dir && (operand === '.' || operand.endsWith('/.'))
    const to = contents || noTargetDirectory || !destStat?.dir ? destPath : join(destPath, basename(from))
    if (to === from || to.startsWith(`${from}/`)) {
      io.stderr.write(`cp: cannot copy '${operand}' into itself, '${destination}'\n`)
      status = 1
      continue
    }
    if (!(await copyTree(ctx, io, from, to, stat.dir, noClobber))) status = 1
  }
  return status
}

export async function move(args: string[], io: Io, ctx: FileOpsContext): Promise<number> {
  const { files, cwd } = ctx
  const { operands, target, noClobber, noTargetDirectory } = parseOperands(args)
  const destination = target ?? operands.pop()
  if (!destination || operands.length === 0) {
    io.stderr.write('mv: missing file operand\n')
    return 1
  }
  const destPath = resolvePath(cwd, destination)
  const destStat = await files.stat(destPath)
  if (operands.length > 1 && (!destStat?.dir || noTargetDirectory)) {
    io.stderr.write(`mv: target '${destination}' is not a directory\n`)
    return 1
  }
  let status = 0
  const fail = (message: string) => {
    io.stderr.write(`mv: ${message}\n`)
    status = 1
  }
  for (const operand of operands) {
    const from = resolvePath(cwd, operand)
    const stat = await files.stat(from)
    if (!stat) {
      fail(`cannot stat '${operand}': No such file or directory`)
      continue
    }
    const to = destStat?.dir && !noTargetDirectory ? join(destPath, basename(from)) : destPath
    if (to === from) {
      fail(`'${operand}' and '${destination}' are the same file`)
      continue
    }
    if (to.startsWith(`${from}/`)) {
      fail(`cannot move '${operand}' to a subdirectory of itself, '${destination}'`)
      continue
    }
    if (!(await files.stat(dirname(to)))?.dir) {
      fail(`cannot move '${operand}' to '${destination}': No such file or directory`)
      continue
    }
    const existing = await files.stat(to)
    if (existing && noClobber) continue
    if (existing && stat.dir && !existing.dir) {
      fail(`cannot overwrite non-directory '${destination}' with directory '${operand}'`)
      continue
    }
    if (existing && !stat.dir && existing.dir) {
      fail(`cannot overwrite directory '${destination}' with non-directory`)
      continue
    }
    if (existing?.dir && (await files.list(to)).length > 0) {
      fail(`cannot move '${operand}' to '${destination}': Directory not empty`)
      continue
    }
    await files.rename(from, to, existing !== undefined)
  }
  return status
}

// rm with -r, -R or --recursive
export async function removeRecursive(args: string[], io: Io, ctx: FileOpsContext): Promise<number> {
  const { files, cwd } = ctx
  const { operands, force } = parseOperands(args)
  if (operands.length === 0 && !force) {
    io.stderr.write('rm: missing operand\n')
    return 1
  }
  let status = 0
  for (const operand of operands) {
    const path = resolvePath(cwd, operand)
    if (path === '/') {
      io.stderr.write("rm: it is dangerous to operate recursively on '/'\n")
      status = 1
      continue
    }
    if (/(^|\/)\.\.?$/.test(operand)) {
      io.stderr.write(`rm: refusing to remove '.' or '..' directory: skipping '${operand}'\n`)
      status = 1
      continue
    }
    if (!(await files.stat(path))) {
      if (!force) {
        io.stderr.write(`rm: cannot remove '${operand}': No such file or directory\n`)
        status = 1
      }
      continue
    }
    await files.remove(path, true)
  }
  return status
}
