// glob 9 and later: `glob(pattern, options)` resolves to the files
import { toRegExp, walk } from './glob.js'

export async function glob(pattern, options) {
  const re = toRegExp(pattern)
  const files = []
  await walk(options.cwd, '', files)
  return files.filter((f) => re.test(f))
}
