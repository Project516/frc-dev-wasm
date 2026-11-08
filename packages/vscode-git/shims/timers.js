export const setImmediate = (fn, ...args) => setTimeout(fn, 0, ...args)
export const clearImmediate = (id) => clearTimeout(id)
const { setTimeout: set, clearTimeout: clear, setInterval, clearInterval } = globalThis
export { set as setTimeout, clear as clearTimeout, setInterval, clearInterval }
export default { setImmediate, clearImmediate, setTimeout: set, clearTimeout: clear, setInterval, clearInterval }
