// Small Node modules VS Code's Git extension imports one or two functions from
export const fileURLToPath = (url) => decodeURIComponent(new URL(url).pathname)
export const parse = (text) => Object.fromEntries(new URLSearchParams(text))
export const stringify = (object) => new URLSearchParams(object).toString()
const custom = Symbol.for('nodejs.util.promisify.custom')
export const promisify = (fn) => fn[custom] ?? ((...args) => new Promise((resolve, reject) => fn(...args, (error, value) => (error ? reject(error) : resolve(value)))))
promisify.custom = custom
export const inspect = (value) => JSON.stringify(value)
export const deprecate = (fn) => fn
const { TextDecoder, TextEncoder } = globalThis
export { TextDecoder, TextEncoder }
export function inherits(ctor, superCtor) {
  Object.setPrototypeOf(ctor.prototype, superCtor.prototype)
  Object.setPrototypeOf(ctor, superCtor)
}
const { URL } = globalThis
export { URL }
