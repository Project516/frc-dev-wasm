export interface Sink {
  write(data: Uint8Array | string): void
}

// What a command sees: the file descriptors it was started with
export interface Io {
  // Undefined when stdin is the terminal, which a command cannot wait on, so it reads end of file
  stdin: Uint8Array | undefined
  stdout: Sink
  stderr: Sink
  stdoutIsTty: boolean
}

const encoder = new TextEncoder()

export class Collector implements Sink {
  private chunks: Uint8Array[] = []

  write(data: Uint8Array | string): void {
    this.chunks.push(typeof data === 'string' ? encoder.encode(data) : data.slice())
  }

  bytes(): Uint8Array {
    const out = new Uint8Array(this.chunks.reduce((n, c) => n + c.length, 0))
    let at = 0
    for (const chunk of this.chunks) {
      out.set(chunk, at)
      at += chunk.length
    }
    return out
  }
}

export const nullSink: Sink = { write() {} }

// Writes to the terminal. A terminal wants \r\n, and a chunk can end in the middle of a UTF-8 character.
export class TtySink implements Sink {
  private readonly decoder = new TextDecoder()
  private readonly emit: (text: string) => void

  constructor(emit: (text: string) => void) {
    this.emit = emit
  }

  write(data: Uint8Array | string): void {
    const text = typeof data === 'string' ? data : this.decoder.decode(data, { stream: true })
    if (text) this.emit(text.replace(/\r?\n/g, '\r\n'))
  }
}
