import type * as vscode from 'vscode'

// The workspace side of packages/wpigui-tools/src/shell/files.js. Tools in iframes ask for
// file dialogs and send back files they wrote. Paths are workspace paths, such as /workspace/a.wpilog.
interface PickRequest {
  type: 'pick'
  id: number
  kind: 'open' | 'save' | 'folder'
  title: string
  path: string
  // Pairs of description and patterns, such as "DataLog Files", "*.wpilog".
  filters: string[]
  multiple: boolean
}

interface WriteRequest {
  type: 'write'
  path: string
  data: Uint8Array
}

interface ReadRequest {
  type: 'read'
  id: number
  paths: string[]
}

type ToolFileRequest = (PickRequest | WriteRequest | ReadRequest) & { frcToolFiles: true }

function dialogFilters(pairs: string[]): Record<string, string[]> | undefined {
  const filters: Record<string, string[]> = {}
  for (let i = 0; i + 1 < pairs.length; i += 2) {
    const extensions = pairs[i + 1].split(/\s+/).map((p) => p.replace(/^\*\.?/, '') || '*')
    if (!extensions.includes('*')) filters[pairs[i]] = extensions
  }
  return Object.keys(filters).length ? filters : undefined
}

async function pick(api: typeof vscode, request: PickRequest): Promise<{ path: string; data?: Uint8Array }[]> {
  const defaultUri = request.path ? api.Uri.file(request.path) : api.workspace.workspaceFolders?.[0]?.uri
  const filters = dialogFilters(request.filters)
  if (request.kind === 'save') {
    const uri = await api.window.showSaveDialog({ title: request.title, defaultUri, filters })
    return uri ? [{ path: uri.path }] : []
  }
  const uris =
    (await api.window.showOpenDialog({
      title: request.title,
      defaultUri,
      filters,
      canSelectFiles: request.kind === 'open',
      canSelectFolders: request.kind === 'folder',
      canSelectMany: request.multiple
    })) ?? []
  if (request.kind === 'folder') return uris.map((uri) => ({ path: uri.path }))
  return Promise.all(uris.map(async (uri) => ({ path: uri.path, data: await api.workspace.fs.readFile(uri) })))
}

// The file a tool names, if it is inside an open workspace folder. Joining resolves any `..` first.
function workspaceFile(api: typeof vscode, path: string): vscode.Uri | undefined {
  const uri = api.Uri.joinPath(api.Uri.file('/'), path)
  return api.workspace.getWorkspaceFolder(uri) ? uri : undefined
}

// Starts answering tool iframes. Files are only read and written inside the open workspace folders.
export function serveToolFiles(api: typeof vscode, onError: (message: string) => void): void {
  window.addEventListener('message', async (event: MessageEvent<ToolFileRequest>) => {
    const request = event.data
    if (event.origin !== window.location.origin || !request?.frcToolFiles || !event.source) return
    try {
      if (request.type === 'pick') {
        const files = await pick(api, request)
        const transfer = files.flatMap((f) => (f.data ? [f.data.buffer as ArrayBuffer] : []))
        event.source.postMessage({ frcToolFiles: true, type: 'picked', id: request.id, files }, { targetOrigin: event.origin, transfer })
      } else if (request.type === 'read') {
        const files: { path: string; data: Uint8Array }[] = []
        for (const path of request.paths) {
          const uri = workspaceFile(api, path)
          const data = uri && (await api.workspace.fs.readFile(uri).then(undefined, () => undefined))
          if (data) files.push({ path, data })
        }
        const transfer = files.map((f) => f.data.buffer as ArrayBuffer)
        event.source.postMessage({ frcToolFiles: true, type: 'picked', id: request.id, files }, { targetOrigin: event.origin, transfer })
      } else if (request.type === 'write') {
        const uri = workspaceFile(api, request.path)
        if (!uri) throw new Error(`${request.path} is outside the workspace`)
        await api.workspace.fs.writeFile(uri, request.data)
      }
    } catch (error) {
      onError(`File access for a tool failed: ${error instanceof Error ? error.message : String(error)}\n`)
      if (request.type === 'pick' || request.type === 'read') {
        event.source.postMessage({ frcToolFiles: true, type: 'picked', id: request.id, files: [] }, { targetOrigin: event.origin })
      }
    }
  })
}
