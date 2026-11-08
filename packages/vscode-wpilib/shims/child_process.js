// No processes in the browser. The modules that start Gradle, the JDK and the tools are replaced, so
// this only answers what is left.
const unavailable = (command) => new Error(`${command}: starting programs is not available in the browser`)

export function exec(command, ...args) {
  const done = args.pop()
  queueMicrotask(() => done(unavailable(command), '', ''))
}

export function execFile(file, ...args) {
  const done = args.pop()
  queueMicrotask(() => done(unavailable(file), '', ''))
}
