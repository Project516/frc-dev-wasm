// A Linux-like process for VS Code's Git extension in the browser
const process = {
  env: {},
  platform: 'linux',
  arch: 'wasm32',
  version: 'v24.0.0',
  versions: {},
  argv: [],
  execPath: '/usr/bin/node',
  cwd: () => '/',
  nextTick: (fn, ...args) => queueMicrotask(() => fn(...args)),
  on() {},
  once() {},
  off() {},
  emitWarning() {}
}
export default process
