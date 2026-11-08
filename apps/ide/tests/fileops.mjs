// cp -r, mv and rm -r against an in-memory file system, including its sharp edges:
// like VS Code's, copy with overwrite and rename with overwrite delete the target first.
import { copyRecursive, isRecursiveCopy, move, removeRecursive, removesRoot } from '../src/frc/terminal/fileops.ts'
import { Collector } from '../src/frc/terminal/io.ts'
import { relativizeArgs } from '../src/frc/terminal/paths.ts'

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok || !detail ? '' : `: ${detail}`}`)
  if (!ok) failures.push(name)
}

const dirname = (p) => p.slice(0, p.lastIndexOf('/')) || '/'

class MemFiles {
  constructor(tree) {
    this.nodes = new Map([['/', { dir: true }]])
    this.calls = []
    for (const [path, content] of Object.entries(tree)) {
      for (let p = dirname(path); p !== '/'; p = dirname(p)) if (!this.nodes.has(p)) this.nodes.set(p, { dir: true })
      this.nodes.set(path, content === null ? { dir: true } : { dir: false, data: new TextEncoder().encode(content) })
    }
  }
  paths(under = '/') {
    return [...this.nodes.keys()].filter((p) => p !== '/' && (under === '/' || p === under || p.startsWith(`${under}/`))).sort()
  }
  async stat(path) {
    const n = this.nodes.get(path)
    return n ? { dir: n.dir, size: n.data?.length ?? 0, mtime: 0 } : undefined
  }
  async list(path) {
    return this.paths(path).filter((p) => p !== path && dirname(p) === path).map((p) => [p.slice(p.lastIndexOf('/') + 1), this.nodes.get(p).dir])
  }
  async read(path) {
    return this.nodes.get(path).data
  }
  async write(path, data) {
    this.nodes.set(path, { dir: false, data })
  }
  async mkdir(path) {
    this.nodes.set(path, { dir: true })
  }
  drop(path) {
    for (const p of this.paths(path)) this.nodes.delete(p)
  }
  async copy(from, to, overwrite) {
    this.calls.push(`copy ${from} ${to} ${overwrite}`)
    if (this.nodes.has(to)) {
      if (!overwrite) throw new Error('exists')
      this.drop(to)
    }
    for (const p of this.paths(from)) this.nodes.set(to + p.slice(from.length), { ...this.nodes.get(p) })
  }
  async rename(from, to, overwrite) {
    this.calls.push(`rename ${from} ${to} ${overwrite}`)
    if (this.nodes.has(to)) {
      if (!overwrite) throw new Error('exists')
      this.drop(to)
    }
    for (const p of this.paths(from)) {
      this.nodes.set(to + p.slice(from.length), this.nodes.get(p))
      this.nodes.delete(p)
    }
  }
  async remove(path, recursive) {
    if (!recursive && this.paths(path).length > 1) throw new Error('not empty')
    this.drop(path)
  }
}

const io = () => ({ stdin: undefined, stdout: new Collector(), stderr: new Collector(), stdoutIsTty: false })
const text = (c) => new TextDecoder().decode(c.bytes())
const read = (fs, path) => new TextDecoder().decode(fs.nodes.get(path)?.data ?? new Uint8Array())

// mv
let fs = new MemFiles({ '/w/big/a.txt': 'A', '/w/big/sub/b.txt': 'B', '/w/dest/keep.txt': 'K' })
let i = io()
let status = await move(['big', 'dest'], i, { files: fs, cwd: '/w' })
check('mv into an existing directory moves the whole tree by rename', status === 0 && read(fs, '/w/dest/big/sub/b.txt') === 'B' && read(fs, '/w/dest/keep.txt') === 'K' && fs.calls.length === 1 && !fs.nodes.has('/w/big'), JSON.stringify(fs.calls))

fs = new MemFiles({ '/w/big/a.txt': 'A', '/w/dest/keep.txt': 'K' })
i = io()
status = await move(['big', 'dest/'], i, { files: fs, cwd: '/w' })
check('mv onto a name that is a non-empty directory never overwrites it', fs.nodes.has('/w/dest/big/a.txt') && read(fs, '/w/dest/keep.txt') === 'K', text(i.stderr))

fs = new MemFiles({ '/w/big/a.txt': 'A', '/w/other/keep.txt': 'K' })
i = io()
status = await move(['-T', 'big', 'other'], i, { files: fs, cwd: '/w' }).catch(() => 1)
check('mv of a directory onto a non-empty directory is refused and loses nothing', read(fs, '/w/other/keep.txt') === 'K' && read(fs, '/w/big/a.txt') === 'A' && status === 1 && text(i.stderr).includes('Directory not empty'), `${status} ${text(i.stderr)} ${JSON.stringify(fs.calls)}`)

fs = new MemFiles({ '/w/a.txt': 'A', '/w/b.txt': 'B' })
await move(['a.txt', 'b.txt'], io(), { files: fs, cwd: '/w' })
check('mv of a file over a file replaces it', read(fs, '/w/b.txt') === 'A' && !fs.nodes.has('/w/a.txt'))

fs = new MemFiles({ '/w/a.txt': 'A', '/w/d/x': 'X' })
i = io()
status = await move(['d', 'a.txt'], i, { files: fs, cwd: '/w' })
check('mv of a directory over a file is refused', status === 1 && read(fs, '/w/a.txt') === 'A' && fs.nodes.has('/w/d/x'), text(i.stderr))

fs = new MemFiles({ '/w/a.txt': 'A' })
i = io()
status = await move(['a.txt', 'nope/b.txt'], i, { files: fs, cwd: '/w' })
check('mv into a missing directory fails and keeps the source', status === 1 && read(fs, '/w/a.txt') === 'A')

fs = new MemFiles({ '/w/d/x': 'X' })
i = io()
status = await move(['d', 'd/e'], i, { files: fs, cwd: '/w' })
check('mv of a directory into itself is refused', status === 1 && fs.nodes.has('/w/d/x'))

// cp -r
fs = new MemFiles({ '/w/src/new.txt': 'N', '/w/src/same.txt': 'from-src', '/w/dst/same.txt': 'old', '/w/dst/extra.txt': 'E' })
i = io()
status = await copyRecursive(['src', 'dst'], i, { files: fs, cwd: '/w' })
check('cp -r into an existing directory keeps the files that are only there', read(fs, '/w/dst/src/new.txt') === 'N' && read(fs, '/w/dst/extra.txt') === 'E' && read(fs, '/w/dst/same.txt') === 'old', text(i.stderr))

fs = new MemFiles({ '/w/src/new.txt': 'N', '/w/src/same.txt': 'from-src', '/w/dst/same.txt': 'old', '/w/dst/extra.txt': 'E' })
await copyRecursive(['src/.', 'dst'], io(), { files: fs, cwd: '/w' })
check('cp -r src/. dst merges the contents of src', read(fs, '/w/dst/new.txt') === 'N' && read(fs, '/w/dst/same.txt') === 'from-src' && read(fs, '/w/dst/extra.txt') === 'E' && !fs.nodes.has('/w/dst/src'))

fs = new MemFiles({ '/w/src/f.txt': 'F', '/w/dst/f.txt': 'old', '/w/dst/g.txt': 'G' })
await copyRecursive(['-n', '-r', 'src/.', 'dst'], io(), { files: fs, cwd: '/w' })
check('cp -rn does not replace existing files', read(fs, '/w/dst/f.txt') === 'old' && read(fs, '/w/dst/g.txt') === 'G')

fs = new MemFiles({ '/w/d/x': 'X', '/w/f': 'plain' })
i = io()
status = await copyRecursive(['d', 'f'], i, { files: fs, cwd: '/w' })
check('cp -r of a directory over a file is an error', status === 1 && read(fs, '/w/f') === 'plain', text(i.stderr))

fs = new MemFiles({ '/w/f': 'plain', '/w/d/x': 'X' })
i = io()
status = await copyRecursive(['f', 'd'], i, { files: fs, cwd: '/w' })
check('cp -r of a file into a directory', status === 0 && read(fs, '/w/d/f') === 'plain' && read(fs, '/w/d/x') === 'X')

fs = new MemFiles({ '/w/d/x': 'X' })
status = await copyRecursive(['d', 'new'], io(), { files: fs, cwd: '/w' })
check('cp -r to a new name copies without overwrite', read(fs, '/w/new/x') === 'X' && fs.calls[0].endsWith('false'), JSON.stringify(fs.calls))

fs = new MemFiles({ '/w/d/x': 'X' })
status = await copyRecursive(['d', 'd/inner'], io(), { files: fs, cwd: '/w' })
check('cp -r into itself is refused', status === 1 && !fs.nodes.has('/w/d/inner'))

check('cp accepts --archive and -a', isRecursiveCopy(['--archive']) && isRecursiveCopy(['-a']) && isRecursiveCopy(['-rf']) && !isRecursiveCopy(['-f']))

// rm -r
fs = new MemFiles({ '/w/d/x': 'X', '/w/keep': 'K' })
status = await removeRecursive(['-rf', 'd', 'missing'], io(), { files: fs, cwd: '/w' })
check('rm -rf removes a tree and ignores a missing operand', status === 0 && !fs.nodes.has('/w/d/x') && read(fs, '/w/keep') === 'K')
i = io()
status = await removeRecursive(['-r', 'missing'], i, { files: fs, cwd: '/w' })
check('rm -r reports a missing operand', status === 1 && text(i.stderr).includes('No such file'))
i = io()
status = await removeRecursive(['-r', '/'], i, { files: fs, cwd: '/w' })
check('rm -r refuses /', status === 1 && fs.nodes.has('/w/keep'))
i = io()
status = await removeRecursive(['-r', '.', '../w/.'], i, { files: fs, cwd: '/w' })
check('rm -r refuses . and ..', status === 1 && fs.nodes.has('/w/keep'))
check('rm of / is caught before anything runs', removesRoot(['-rf', '/'], '/w') && removesRoot(['..', '-f'], '/') && !removesRoot(['-f', 'a'], '/w'))

// relativizeArgs leaves text operands alone
check('dirname keeps its path text', JSON.stringify(relativizeArgs('dirname', ['/a/b/c'], '/w')) === '["/a/b/c"]')
check('cut -d / keeps the delimiter', JSON.stringify(relativizeArgs('cut', ['-d', '/', '-f', '2', '/w/f'], '/w')) === '["-d","/","-f","2","f"]')
check('cut -d/ and --delimiter=/ keep the delimiter', JSON.stringify(relativizeArgs('cut', ['-d/', '--delimiter=/', '/w/f'], '/w')) === '["-d/","--delimiter=/","f"]')
check('sort -t / keeps the separator', JSON.stringify(relativizeArgs('sort', ['-t', '/', '-k2', '/w/f'], '/w')) === '["-t","/","-k2","f"]')
check('--field-separator / keeps the separator', JSON.stringify(relativizeArgs('sort', ['--field-separator', '/', '/w/f'], '/w')) === '["--field-separator","/","f"]')
check('file operands still become relative', JSON.stringify(relativizeArgs('cat', ['/w/a/f', '--file=/w/g'], '/w/a')) === '["f","--file=../g"]')
check('dd if= and of= values become relative', JSON.stringify(relativizeArgs('dd', ['if=/w/a', 'of=/w/b', 'bs=1'], '/w')) === '["if=a","of=b","bs=1"]')

if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
