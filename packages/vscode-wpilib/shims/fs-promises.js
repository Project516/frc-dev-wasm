// fs/promises, which the extension imports from 2027 on. fs.js has what is already there.
import path from 'path-browserify'
import { promises } from './fs.js'

export const { readFile, writeFile, copyFile, stat, lstat, readdir, mkdir, unlink, rename, chmod } = promises

export const access = (file) => promises.stat(file).then(() => undefined)

async function copy(source, dest, filter) {
  if (filter && !(await filter(source, dest))) return
  if ((await stat(source)).isDirectory()) {
    await mkdir(dest)
    for (const name of await readdir(source)) await copy(path.join(source, name), path.join(dest, name), filter)
  } else await copyFile(source, dest)
}

// Files and folders, with the filter option of node's cp
export const cp = (source, dest, options) => copy(source, dest, options?.filter)

export default { readFile, writeFile, copyFile, stat, lstat, readdir, mkdir, unlink, rename, chmod, access, cp }
