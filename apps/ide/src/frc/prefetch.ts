import type * as vscode from 'vscode'
import { plainText } from '../plainText'

const RUNTIME_BASE = new URL(`${import.meta.env.BASE_URL}runtime/`, window.location.href)
const FIXED_FILES = ['wpilib/templates.zip', 'wpilib/examples.zip', 'wpilib/commands.zip']
const CONCURRENCY = 4

/** Runs `callback` when the page is idle, or after `timeout`. Safari has no requestIdleCallback. */
export function whenIdle(callback: () => void, timeout: number): void {
  if (typeof requestIdleCallback === 'function') requestIdleCallback(callback, { timeout })
  else setTimeout(callback, Math.min(timeout, 1000))
}

async function listFiles(runtime: URL): Promise<string[] | undefined> {
  const response = await fetch(new URL('manifest.json', runtime))
  if (!response.ok || !response.headers.get('content-type')?.includes('json')) return undefined
  const manifest: { classpath: string[] } = await response.json()
  return [...FIXED_FILES, ...manifest.classpath]
}

// Warms the HTTP cache with the files the season's runtime loads later. Progress shows in the status bar.
export function prefetchRuntime(api: typeof vscode, year: Promise<string>): Promise<void> {
  const start = () =>
    api.window.withProgress(
      { location: api.ProgressLocation.Window, title: 'Downloading FRC runtime' },
      async (progress) => {
        const runtime = new URL(`${await year}/`, RUNTIME_BASE)
        const files = await listFiles(runtime)
        if (files == null) return
        let done = 0
        const queue = [...files]
        const worker = async () => {
          for (let file = queue.shift(); file != null; file = queue.shift()) {
            const response = await fetch(new URL(file, runtime))
            if (!response.ok) throw new Error(`${file}: ${response.status}`)
            await response.arrayBuffer()
            progress.report({ message: `${++done} of ${files.length}` })
          }
        }
        await Promise.all(Array.from({ length: CONCURRENCY }, worker))
      }
    ).then(undefined, (error: unknown) => {
      void api.window.showWarningMessage(
        plainText(`Could not download the FRC runtime, so build and simulation will not work yet. ${String(error)}`)
      )
    })
  // Idle time, so the download does not compete with workbench startup
  return new Promise((resolve) => whenIdle(() => void start().then(resolve), 10_000))
}
