// Joins and normalizes: `.` and `..` are resolved, the result is absolute
export function resolvePath(cwd: string, path: string): string {
  const parts: string[] = []
  for (const part of `${path.startsWith('/') ? '' : `${cwd}/`}${path}`.split('/')) {
    if (part === '..') parts.pop()
    else if (part && part !== '.') parts.push(part)
  }
  return `/${parts.join('/')}`
}

export const dirname = (path: string) => path.slice(0, path.lastIndexOf('/')) || '/'
export const basename = (path: string) => path.slice(path.lastIndexOf('/') + 1)
export const join = (dir: string, name: string) => (dir === '/' ? `/${name}` : `${dir}/${name}`)

// The path to `to` as seen from `from`, both absolute
export function relativePath(from: string, to: string): string {
  const a = from.split('/').filter(Boolean)
  const b = to.split('/').filter(Boolean)
  let common = 0
  while (common < a.length && common < b.length && a[common] === b[common]) common++
  return [...a.slice(common).map(() => '..'), ...b.slice(common)].join('/') || '.'
}

// Commands whose operands are text, not file names
const TEXT_OPERANDS = new Set(['tr', 'seq', 'printf', 'expr', 'date', 'yes', 'test', 'true', 'false', 'basename', 'dirname'])

// Options whose value is text and not a file, per command: short letters and long names
const TEXT_OPTIONS: Record<string, { short: string; long: string[] }> = {
  sort: { short: 'tkS', long: ['field-separator', 'key', 'buffer-size'] },
  cut: { short: 'dfbc', long: ['delimiter', 'fields', 'bytes', 'characters', 'output-delimiter'] },
  paste: { short: 'd', long: ['delimiters'] },
  join: { short: 't12joe', long: ['field-separator'] },
  uniq: { short: 'fsw', long: ['skip-fields', 'skip-chars', 'check-chars'] },
  head: { short: 'nc', long: ['lines', 'bytes'] },
  tail: { short: 'ncs', long: ['lines', 'bytes', 'sleep-interval'] },
  split: { short: 'lbaCn', long: ['lines', 'bytes', 'suffix-length', 'line-bytes', 'number'] },
  fold: { short: 'w', long: ['width'] },
  fmt: { short: 'wpgPT', long: ['width'] },
  nl: { short: 'swnvibhf', long: ['number-separator', 'number-width'] },
  od: { short: 'AtNjw', long: ['address-radix', 'format', 'read-bytes', 'skip-bytes', 'width'] },
  expand: { short: 't', long: ['tabs'] },
  unexpand: { short: 't', long: ['tabs'] },
  shuf: { short: 'nei', long: ['head-count', 'echo', 'input-range'] },
  truncate: { short: 's', long: ['size'] },
  touch: { short: 'dt', long: ['date'] },
  mkdir: { short: 'm', long: ['mode'] },
  ls: { short: 'wIT', long: ['width', 'ignore', 'tabsize', 'block-size', 'time-style'] }
}

// The WASI host sees the working directory as its root, so an absolute file operand has to be made relative
export function relativizeArgs(command: string, args: string[], cwd: string): string[] {
  if (TEXT_OPERANDS.has(command)) return args
  const text = TEXT_OPTIONS[command] ?? { short: '', long: [] }
  const fix = (value: string) => (value.startsWith('/') ? relativePath(cwd, resolvePath(cwd, value)) : value)
  const out: string[] = []
  let literal = false
  let afterOption = false
  for (const arg of args) {
    if (afterOption) {
      out.push(arg)
      afterOption = false
    } else if (literal || !arg.startsWith('-') || arg === '-') {
      out.push(command === 'dd' && arg.includes('=') ? arg.replace(/^((?:if|of)=)(.*)$/, (_, key: string, value: string) => key + fix(value)) : fix(arg))
    } else if (arg === '--') {
      literal = true
      out.push(arg)
    } else if (arg.startsWith('--')) {
      const eq = arg.indexOf('=')
      const name = eq > 0 ? arg.slice(2, eq) : arg.slice(2)
      const isText = text.long.includes(name)
      if (eq > 0) out.push(isText ? arg : `${arg.slice(0, eq + 1)}${fix(arg.slice(eq + 1))}`)
      else {
        out.push(arg)
        afterOption = isText
      }
    } else {
      out.push(arg)
      // The first text option letter in a cluster takes the rest of it, or the next argument
      const at = [...arg.slice(1)].findIndex((c) => text.short.includes(c))
      afterOption = at >= 0 && at === arg.length - 2
    }
  }
  return out
}
