// An instance that cannot be created fails with the reason, not later in callMain
import { GitHost } from '../src/host.mjs'

const reason = new RangeError('WebAssembly.Instance(): Out of memory: Cannot allocate Wasm memory for new instance')
const createGit = async (opts) => {
  opts.FS = {}
  opts.callMain = () => 0
  opts.frcStartError = reason
  throw reason
}
let message
try {
  new GitHost(createGit).run({ args: ['status'], env: {}, cwd: '/', mounts: [], stdout() {}, stderr() {} })
} catch (e) {
  message = e.message
}
const ok = message === `git did not start: ${reason}`
console.log(`${ok ? 'ok  ' : 'FAIL'} a failed start names its reason${ok ? '' : `: ${message}`}`)
if (!ok) process.exitCode = 1
