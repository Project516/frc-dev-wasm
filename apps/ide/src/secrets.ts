import type { ISecretStorageProvider } from '@codingame/monaco-vscode-api'

const KEY = 'frc-dev-wasm.secrets'

// Without a provider the workbench keeps secrets in memory, so sign-ins end on reload. Like VS Code's
// own web build without a server key, they are kept unencrypted in localStorage.
export const secretStorageProvider: ISecretStorageProvider = {
  type: 'persisted',
  get: async (key) => read()[key],
  async set(key, value) {
    write({ ...read(), [key]: value })
  },
  async delete(key) {
    const { [key]: _, ...rest } = read()
    write(rest)
  },
  keys: async () => Object.keys(read())
}

function read(): Record<string, string> {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? '{}') as Record<string, string>
  } catch {
    return {}
  }
}

function write(secrets: Record<string, string>): void {
  if (Object.keys(secrets).length) localStorage.setItem(KEY, JSON.stringify(secrets))
  else localStorage.removeItem(KEY)
}
