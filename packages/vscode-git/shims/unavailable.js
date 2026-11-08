// Node modules VS Code's Git extension can do without in the browser: the askpass IPC server
// (http, crypto), telemetry and copy-on-write file copies. Using them fails, and the extension
// falls back as it does when they are missing.
const unavailable = (name) => () => {
  throw new Error(`${name} is not available in the browser`)
}
export const createServer = unavailable('http.createServer')
export const createHash = unavailable('crypto.createHash')
export const randomBytes = (size, done) => done(new Error('crypto.randomBytes is not available in the browser'))
export const cp = unavailable('@vscode/fs-copyfile')
export class TelemetryReporter {
  sendTelemetryEvent() {}
  sendTelemetryErrorEvent() {}
  sendRawTelemetryEvent() {}
  dispose() {
    return Promise.resolve()
  }
}
export const httpsOverHttp = unavailable('tunnel')
export default TelemetryReporter
