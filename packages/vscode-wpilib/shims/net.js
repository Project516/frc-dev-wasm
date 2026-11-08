// RioLog connects to a roboRIO over TCP, which a web page cannot open. Every connection fails.
import { EventEmitter } from 'events'

export class Socket extends EventEmitter {
  connect(...args) {
    const port = typeof args[0] === 'object' ? args[0].port : args[0]
    setTimeout(() => this.emit('error', Object.assign(new Error(`connect ECONNREFUSED port ${port}: no sockets in the browser`), { code: 'ECONNREFUSED' })))
    return this
  }
  setTimeout() {
    return this
  }
  setNoDelay() {
    return this
  }
  write() {
    return false
  }
  end() {
    return this
  }
  destroy() {
    return this
  }
}

export const connect = (...args) => new Socket().connect(...args)
export const createConnection = connect
