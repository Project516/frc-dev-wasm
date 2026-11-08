import type { Completion } from './interactive'

export interface LineEditorOptions {
  write(text: string): void
  columns(): number
  prompt(): { text: string; width: number }
  history: string[]
  complete(line: string, cursor: number): Promise<Completion>
  onEof(): void
}

const KEYS = /\x1b\[[0-9;?]*[ -/]*[@-~]|\x1bO.|\x1b[\s\S]|[\s\S]/gu
const MAX_HISTORY = 500

function commonPrefix(items: string[]): string {
  let prefix = items[0] ?? ''
  for (const item of items) while (!item.startsWith(prefix)) prefix = prefix.slice(0, -1)
  return prefix
}

// Line editing for a terminal that has no shell behind it: cursor keys, history, tab completion
export class LineEditor {
  private line = ''
  private cursor = 0
  // Rows between the first row of the prompt and the cursor, for redrawing
  private row = 0
  private active = false
  private historyIndex = 0
  private draft = ''
  private afterTab = false

  constructor(private readonly options: LineEditorOptions) {}

  start(): void {
    this.line = ''
    this.cursor = 0
    this.row = 0
    this.historyIndex = this.options.history.length
    this.active = true
    this.draw()
  }

  stop(): void {
    this.active = false
  }

  get idle(): boolean {
    return !this.active
  }

  private draw(): void {
    const { text, width } = this.options.prompt()
    const cols = Math.max(1, this.options.columns())
    const total = width + this.line.length
    let out = (this.row > 0 ? `\x1b[${this.row}A` : '') + `\r${text}${this.line}\x1b[J`
    // Text that ends exactly at the right edge leaves the cursor waiting to wrap
    if (total > 0 && total % cols === 0) out += ' \r'
    const endRow = Math.floor(total / cols)
    const at = width + this.cursor
    const row = Math.floor(at / cols)
    if (endRow > row) out += `\x1b[${endRow - row}A`
    out += `\x1b[${(at % cols) + 1}G`
    this.row = row
    this.options.write(out)
  }

  private set(line: string, cursor = line.length): void {
    this.line = line
    this.cursor = cursor
    this.draw()
  }

  private wordStart(from: number): number {
    let i = from
    while (i > 0 && /\s/.test(this.line[i - 1])) i--
    while (i > 0 && !/\s/.test(this.line[i - 1])) i--
    return i
  }

  private wordEnd(from: number): number {
    let i = from
    while (i < this.line.length && /\s/.test(this.line[i])) i++
    while (i < this.line.length && !/\s/.test(this.line[i])) i++
    return i
  }

  private recall(index: number): void {
    const { history } = this.options
    if (this.historyIndex === history.length) this.draft = this.line
    this.historyIndex = Math.min(Math.max(index, 0), history.length)
    this.set(this.historyIndex === history.length ? this.draft : history[this.historyIndex])
  }

  private accept(): string {
    const line = this.line
    this.cursor = this.line.length
    this.draw()
    this.options.write('\r\n')
    this.active = false
    const { history } = this.options
    if (line.trim() && history[history.length - 1] !== line) {
      history.push(line)
      if (history.length > MAX_HISTORY) history.splice(0, history.length - MAX_HISTORY)
    }
    return line
  }

  private async tab(again: boolean): Promise<void> {
    const { start, candidates } = await this.options.complete(this.line, this.cursor)
    if (candidates.length === 0) return
    const typed = this.line.slice(start, this.cursor)
    const common = commonPrefix(candidates)
    if (common.length > typed.length) {
      this.set(this.line.slice(0, start) + common + this.line.slice(this.cursor), start + common.length)
      return
    }
    if (candidates.length < 2 || !again) return
    const names = candidates.map((c) => c.trimEnd().replace(/\\(.)/g, '$1'))
    const width = Math.max(...names.map((n) => n.length)) + 2
    const perRow = Math.max(1, Math.floor(this.options.columns() / width))
    let out = '\r\n'
    for (let i = 0; i < names.length; i += perRow) {
      out += `${names.slice(i, i + perRow).map((n) => n.padEnd(width)).join('').trimEnd()}\r\n`
    }
    this.options.write(out)
    this.row = 0
    this.draw()
  }

  // Handles typed or pasted data. When Enter accepts a line, the editor goes idle until start()
  // and the keys after it come back in `rest`.
  async input(data: string): Promise<{ line?: string; rest: string }> {
    const keys = data.match(KEYS) ?? []
    for (const [i, key] of keys.entries()) {
      if (!this.active) return { rest: keys.slice(i).join('') }
      const again = this.afterTab
      this.afterTab = key === '\t'
      if (key === '\t') {
        await this.tab(again)
        continue
      }
      if (key === '\r' || key === '\n') return { line: this.accept(), rest: keys.slice(i + 1).join('') }
      else if (key === '\x7f' || key === '\b') {
        if (this.cursor > 0) this.set(this.line.slice(0, this.cursor - 1) + this.line.slice(this.cursor), this.cursor - 1)
      } else if (key === '\x1b[3~' || (key === '\x04' && this.line)) {
        if (this.cursor < this.line.length) this.set(this.line.slice(0, this.cursor) + this.line.slice(this.cursor + 1), this.cursor)
      } else if (key === '\x04') this.options.onEof()
      else if (key === '\x03') {
        this.options.write('^C\r\n')
        this.start()
      } else if (key === '\x1b[D' || key === '\x02') this.moveTo(this.cursor - 1)
      else if (key === '\x1b[C' || key === '\x06') this.moveTo(this.cursor + 1)
      else if (key === '\x1b[1;5D' || key === '\x1bb') this.moveTo(this.wordStart(this.cursor))
      else if (key === '\x1b[1;5C' || key === '\x1bf') this.moveTo(this.wordEnd(this.cursor))
      else if (key === '\x1b[H' || key === '\x1b[1~' || key === '\x01' || key === '\x1bOH') this.moveTo(0)
      else if (key === '\x1b[F' || key === '\x1b[4~' || key === '\x05' || key === '\x1bOF') this.moveTo(this.line.length)
      else if (key === '\x1b[A' || key === '\x10') this.recall(this.historyIndex - 1)
      else if (key === '\x1b[B' || key === '\x0e') this.recall(this.historyIndex + 1)
      else if (key === '\x0b') this.set(this.line.slice(0, this.cursor))
      else if (key === '\x15') this.set(this.line.slice(this.cursor), 0)
      else if (key === '\x17') {
        const start = this.wordStart(this.cursor)
        this.set(this.line.slice(0, start) + this.line.slice(this.cursor), start)
      } else if (key === '\x0c') {
        this.options.write('\x1b[2J\x1b[3J\x1b[H')
        this.row = 0
        this.draw()
      } else if (key >= ' ' && key !== '\x7f' && !key.startsWith('\x1b')) {
        this.set(this.line.slice(0, this.cursor) + key + this.line.slice(this.cursor), this.cursor + key.length)
      }
    }
    return { rest: '' }
  }

  private moveTo(cursor: number): void {
    this.cursor = Math.min(Math.max(cursor, 0), this.line.length)
    this.draw()
  }
}
