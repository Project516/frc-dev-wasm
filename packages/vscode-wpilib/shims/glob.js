// The glob calls of the WPILib extension: a pattern under `cwd`, files only, relative results
import path from 'path-browserify'
import { promises as fs } from './fs.js'

// Supports **, *, ? and {a,b}
export function toRegExp(pattern) {
  let re = ''
  let braces = 0
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i]
    if (c === '*' && pattern[i + 1] === '*') {
      i++
      if (pattern[i + 1] === '/') {
        i++
        re += '(?:.*/)?'
      } else re += '.*'
    } else if (c === '*') re += '[^/]*'
    else if (c === '?') re += '[^/]'
    else if (c === '{') {
      braces++
      re += '(?:'
    } else if (c === '}' && braces > 0) {
      braces--
      re += ')'
    } else if (c === ',' && braces > 0) re += '|'
    else re += c.replace(/[.+^$()|[\]\\]/g, '\\$&')
  }
  return new RegExp(`^${re}$`)
}

export async function walk(root, dir, out) {
  for (const name of await fs.readdir(path.join(root, dir))) {
    const rel = dir ? `${dir}/${name}` : name
    if ((await fs.stat(path.join(root, rel))).isDirectory()) await walk(root, rel, out)
    else out.push(rel)
  }
}

export default function glob(pattern, options, done) {
  const re = toRegExp(pattern)
  const files = []
  walk(options.cwd, '', files).then(
    () => done(null, files.filter((f) => re.test(f))),
    (error) => done(error)
  )
}
