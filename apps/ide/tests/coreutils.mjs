// Runs the coreutils WASI binary through the in-memory WASI host, without a browser.
// Needs pnpm build:terminal first.
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { relativizeArgs } from '../src/frc/terminal/paths.ts'
import { run } from '../src/frc/terminal/wasi.ts'

const wasm = join(dirname(fileURLToPath(import.meta.url)), '../public/runtime/terminal/coreutils.wasm')
if (!existsSync(wasm)) {
  console.log('skip: coreutils.wasm is not built')
  process.exit(0)
}
const module = new WebAssembly.Module(readFileSync(wasm))
const encoder = new TextEncoder()
const decoder = new TextDecoder()

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok || !detail ? '' : `: ${detail}`}`)
  if (!ok) failures.push(name)
}

const file = (path, text, extra = {}) => ({ path, dir: false, size: text.length, mtime: 0, data: encoder.encode(text), ...extra })
const dir = (path) => ({ path, dir: true, size: 0, mtime: 0 })
const base = [dir('/'), dir('/workspace'), file('/workspace/a.txt', 'one\ntwo\nthree\n'), file('/workspace/b.txt', 'beta\nalpha\n')]

function exec(args, { entries = base, cwd = '/workspace', stdin, tty = false, maxOutput } = {}) {
  const out = { 1: '', 2: '' }
  const result = run({
    module,
    args: [args[0], ...relativizeArgs(args[0], args.slice(1), cwd)],
    env: ['HOME=/workspace', 'COLUMNS=80'],
    cwd,
    stdin: stdin === undefined ? undefined : encoder.encode(stdin),
    stdoutIsTty: tty,
    maxOutput,
    entries: structuredClone(entries),
    onOutput: (fd, data) => (out[fd] += decoder.decode(data))
  })
  return { ...result, stdout: out[1], stderr: out[2] }
}

let r = exec(['cat', 'a.txt'])
check('cat reads a relative path', r.code === 0 && r.stdout === 'one\ntwo\nthree\n', JSON.stringify(r))
r = exec(['cat', '/workspace/b.txt'])
check('cat reads an absolute path', r.stdout === 'beta\nalpha\n', JSON.stringify(r))
r = exec(['cat', 'missing'])
check('cat reports a missing file', r.code === 1 && r.stderr.includes('No such file'), JSON.stringify(r))
r = exec(['sort'], { stdin: 'b\na\nc\n' })
check('sort reads stdin', r.stdout === 'a\nb\nc\n', JSON.stringify(r))
r = exec(['wc', '-l', 'a.txt'])
check('wc counts lines', r.stdout.trim().startsWith('3'), JSON.stringify(r))
r = exec(['head', '-n', '1', 'a.txt'])
check('head takes the first line', r.stdout === 'one\n', JSON.stringify(r))
r = exec(['tac', 'a.txt'])
check('tac reverses lines', r.stdout === 'three\ntwo\none\n', JSON.stringify(r))
r = exec(['ls'])
check('ls lists the working directory', r.stdout.split(/\s+/).filter(Boolean).join(',') === 'a.txt,b.txt', JSON.stringify(r))
r = exec(['ls', '-l', 'a.txt'])
check('ls -l shows the size', / 14 /.test(r.stdout), JSON.stringify(r))
r = exec(['ls'], { tty: true })
check('ls to a terminal still lists files', r.stdout.includes('a.txt') && r.stdout.includes('b.txt'), JSON.stringify(r))

r = exec(['mkdir', '-p', 'x/y'])
check('mkdir -p creates both levels', JSON.stringify(r.changes.map((c) => `${c.op}:${c.path}`)) === '["mkdir:/workspace/x","mkdir:/workspace/x/y"]', JSON.stringify(r.changes))
r = exec(['cp', 'a.txt', 'c.txt'])
check('cp writes a new file', r.changes.length === 1 && r.changes[0].op === 'write' && r.changes[0].path === '/workspace/c.txt' && decoder.decode(r.changes[0].data) === 'one\ntwo\nthree\n', JSON.stringify(r.changes))
r = exec(['rm', 'a.txt'], { entries: base.map((e) => (e.path === '/workspace/a.txt' ? { ...e, data: undefined } : e)) })
check('rm deletes a stub', JSON.stringify(r.changes) === '[{"op":"delete","path":"/workspace/a.txt"}]', JSON.stringify(r.changes))
r = exec(['touch', 'new.txt'])
check('touch creates an empty file', r.changes.length === 1 && r.changes[0].op === 'write' && r.changes[0].data.length === 0, JSON.stringify(r.changes))
r = exec(['truncate', '-s', '3', 'a.txt'])
check('truncate rewrites the file', r.changes.length === 1 && decoder.decode(r.changes[0].data) === 'one', JSON.stringify(r.changes))
r = exec(['tee', 'copy.txt'], { stdin: 'hello' })
check('tee writes stdin to a file and stdout', r.stdout === 'hello' && decoder.decode(r.changes[0].data) === 'hello', JSON.stringify(r))
r = exec(['rm', '-r', 'tree'], { entries: [...base, dir('/workspace/tree'), { ...file('/workspace/tree/f.txt', 'f'), data: undefined }] })
check('rm -r removes only the top directory', JSON.stringify(r.changes) === '[{"op":"delete","path":"/workspace/tree"}]', JSON.stringify(r.changes))
r = exec(['seq', '3'])
check('seq runs without files', r.stdout === '1\n2\n3\n', JSON.stringify(r))
r = exec(['yes'])
check('yes stops at the output limit', r.code === 141 && r.stdout.length >= 16 * 1024 * 1024, `${r.code} ${r.stdout.length}`)
r = exec(['sha256sum', 'a.txt'])
check('sha256sum hashes a file', /^[0-9a-f]{64} /.test(r.stdout), JSON.stringify(r))

const tree = [...base, dir('/workspace/tree'), file('/workspace/tree/f.txt', 'f'), dir('/workspace/tree/sub'), file('/workspace/tree/sub/g.txt', 'gg')]
r = exec(['ls', '-R', 'tree'], { entries: tree })
check('ls -R lists nested directories', r.code === 0 && r.stdout.includes('sub') && r.stdout.includes('g.txt'), JSON.stringify(r))
r = exec(['ls', '-la'], { entries: tree })
check('ls -la shows entries', r.code === 0 && r.stdout.includes('tree') && r.stdout.includes('a.txt'), JSON.stringify(r))
// mv is a shell built-in now, so a binary built without it is fine; when it is there, check the order of changes
r = exec(['mv', 'tree', 'moved'], { entries: tree })
if (r.code === 0) {
  const ops = r.changes.map((c) => c.op)
  check('changes put writes before deletes', ops.lastIndexOf('write') < ops.indexOf('delete') && ops.includes('delete'), JSON.stringify(ops))
}
r = exec(['yes'], { maxOutput: 1000 })
check('the output limit can be set', r.code === 141 && r.stdout.length >= 1000 && r.stdout.length < 100_000, `${r.code} ${r.stdout.length}`)
r = exec(['rm', '-rf', 'tree'], { entries: tree.map((e) => (e.dir ? e : { ...e, data: undefined })) })
check('rm -rf removes a tree', r.code === 0 && r.changes.length === 1, JSON.stringify(r))
r = exec(['cut', '-d', 'e', '-f', '1', 'a.txt'])
check('cut splits fields', r.stdout === 'on\ntwo\nthr\n', JSON.stringify(r))
r = exec(['uniq', '-c'], { stdin: 'a\na\nb\n' })
check('uniq -c counts', r.stdout.includes('2 a') && r.stdout.includes('1 b'), JSON.stringify(r))
r = exec(['tail', '-n', '1', 'a.txt'])
check('tail takes the last line', r.stdout === 'three\n', JSON.stringify(r))
r = exec(['cat', '../workspace/a.txt'])
check('cat follows .. out of the working directory', r.stdout.startsWith('one'), JSON.stringify(r))
r = exec(['cat', '/workspace/a.txt'], { cwd: '/workspace/tree', entries: [dir('/'), dir('/workspace'), dir('/workspace/tree'), file('/workspace/a.txt', 'abs')] })
check('cat reads an absolute path from a subdirectory', r.stdout === 'abs', JSON.stringify(r))
r = exec(['date', '+%Y'])
check('date formats', /^\d{4}\n$/.test(r.stdout), JSON.stringify(r))
r = exec(['basename', '/a/b/c.txt'])
check('basename keeps its text operand', r.stdout === 'c.txt\n', JSON.stringify(r))

if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
