import { promises } from './fs.js'
export const { readFile, writeFile, stat, lstat, readdir, mkdir, unlink, rm, rename, realpath } = promises
export default promises
