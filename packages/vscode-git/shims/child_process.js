// Runs git through the IDE's git worker (frcDevWasmGit, set by the page), with the ChildProcess
// events and streams that VS Code's Git extension reads.
import { Buffer } from 'buffer'
import { EventEmitter } from 'events'
import { PassThrough, Writable } from 'readable-stream'

const host = () => {
  if (!globalThis.frcDevWasmGit) throw new Error('git is not available in this browser')
  return globalThis.frcDevWasmGit
}

let nextPid = 1000

export function spawn(command, args = [], options = {}) {
  const child = new EventEmitter()
  child.pid = nextPid++
  child.killed = false
  child.exitCode = null
  child.stdout = new PassThrough()
  child.stderr = new PassThrough()
  const controller = new AbortController()
  child.kill = () => {
    child.killed = true
    controller.abort()
    return true
  }
  const input = []
  let started = false
  const start = () => {
    if (started) return
    started = true
    host()
      .run({
        args: [...args],
        cwd: options.cwd,
        env: options.env ?? {},
        input: input.length ? new Uint8Array(Buffer.concat(input)) : undefined,
        signal: controller.signal,
        stdout: (bytes) => child.stdout.write(Buffer.from(bytes)),
        stderr: (bytes) => child.stderr.write(Buffer.from(bytes))
      })
      .then(
        (status) => {
          child.exitCode = status
          child.stdout.end()
          child.stderr.end()
          // After the streams have delivered their data, which they do on later ticks
          setTimeout(() => {
            child.emit('exit', status, null)
            child.emit('close', status, null)
          })
        },
        (error) => {
          // The Git extension shows the first line of stderr, and the rest under Show Command Output
          if (error?.stack) error.stderr ??= error.stack.startsWith(`${error.name}: `) ? error.stack : `${error.message}\n${error.stack}`
          child.stdout.end()
          child.stderr.end()
          child.emit('error', error)
        }
      )
  }
  // git reads its whole input before it runs. The extension writes and ends standard input right
  // after spawn when it has input, so git starts then, or on the next tick when nothing was written.
  let writing = false
  child.stdin = new Writable({
    write(chunk, encoding, done) {
      writing = true
      input.push(Buffer.from(chunk, encoding))
      done()
    },
    final(done) {
      start()
      done()
    }
  })
  const write = child.stdin.write.bind(child.stdin)
  const end = child.stdin.end.bind(child.stdin)
  child.stdin.write = (...args) => ((writing = true), write(...args))
  child.stdin.end = (...args) => ((writing = true), end(...args))
  queueMicrotask(() => writing || start())
  return child
}

export function exec(command, options, callback) {
  const done = typeof options === 'function' ? options : callback
  const [name, ...args] = command.split(' ')
  if (name !== 'git') {
    queueMicrotask(() => done?.(Object.assign(new Error(`${name}: command not found`), { code: 127 }), '', ''))
    return new EventEmitter()
  }
  const child = spawn(name, args, { stdio: ['ignore'] })
  const out = []
  const err = []
  child.stdout.on('data', (b) => out.push(b))
  child.stderr.on('data', (b) => err.push(b))
  child.on('exit', (status) => done?.(status ? Object.assign(new Error(`Command failed: ${command}`), { code: status }) : null, Buffer.concat(out).toString(), Buffer.concat(err).toString()))
  child.on('error', (error) => done?.(error, '', ''))
  return child
}

// Only used on Windows
export function execFile(file, args, callback) {
  queueMicrotask(() => callback?.(new Error(`${file}: command not found`), '', ''))
}

export default { spawn, exec, execFile }
