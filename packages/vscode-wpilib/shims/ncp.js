// ncp's recursive copy, with the filter option the WPILib extension passes
import path from 'path-browserify'
import { promises as fs } from './fs.js'

async function copy(source, dest, filter) {
  if (filter && !filter(source)) return
  if ((await fs.stat(source)).isDirectory()) {
    await fs.mkdir(dest)
    for (const name of await fs.readdir(source)) await copy(path.join(source, name), path.join(dest, name), filter)
  } else await fs.copyFile(source, dest)
}

export function ncp(source, dest, options, done) {
  if (typeof options === 'function') [options, done] = [{}, options]
  copy(source, dest, options?.filter).then(() => done(null), (error) => done([error]))
}
