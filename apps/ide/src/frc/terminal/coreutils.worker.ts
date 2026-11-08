import { run, type Change, type Entry } from './wasi'

export interface WorkerRequest {
  module: WebAssembly.Module
  args: string[]
  env: string[]
  cwd: string
  stdin?: Uint8Array
  stdoutIsTty: boolean
  maxOutput?: number
  entries: Entry[]
}

export type WorkerMessage =
  | { type: 'output'; fd: 1 | 2; data: Uint8Array }
  | { type: 'done'; code: number; changes: Change[] }
  | { type: 'error'; message: string }

const send = (message: WorkerMessage, transfer: Transferable[] = []) => (self as unknown as Worker).postMessage(message, transfer)

self.onmessage = (event: MessageEvent<WorkerRequest>) => {
  try {
    const { code, changes } = run({
      ...event.data,
      onOutput: (fd, data) => send({ type: 'output', fd, data }, [data.buffer])
    })
    const buffers = new Set<Transferable>()
    for (const change of changes) if (change.op === 'write') buffers.add(change.data.buffer)
    send({ type: 'done', code, changes }, [...buffers])
  } catch (error) {
    send({ type: 'error', message: error instanceof Error ? error.message : String(error) })
  }
}
