import type * as vscode from 'vscode'

export function workspaceRoots(api: typeof vscode): vscode.Uri[] {
  return api.workspace.workspaceFolders?.map((f) => f.uri) ?? [api.Uri.file('/workspace')]
}

async function isDirectory(api: typeof vscode, uri: vscode.Uri): Promise<boolean> {
  return api.workspace.fs.stat(uri).then((s) => !!(s.type & api.FileType.Directory), () => false)
}

export async function childFolders(api: typeof vscode, root: vscode.Uri): Promise<vscode.Uri[]> {
  const entries = await api.workspace.fs.readDirectory(root).then((e) => e, () => [])
  return entries.filter(([name, type]) => type & api.FileType.Directory && !name.startsWith('.')).map(([name]) => api.Uri.joinPath(root, name))
}

// Repositories are the workspace folders and their direct subfolders that contain .git
export async function findRepositories(api: typeof vscode): Promise<vscode.Uri[]> {
  const found: vscode.Uri[] = []
  for (const root of workspaceRoots(api)) {
    for (const dir of [root, ...(await childFolders(api, root))]) {
      if (await isDirectory(api, api.Uri.joinPath(dir, '.git'))) found.push(dir)
    }
  }
  return found
}
