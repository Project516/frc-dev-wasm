import { promises as fs } from './fs.js'

// The workbench creates parent folders as well
export default (dir) => fs.mkdir(dir).then(() => dir)
