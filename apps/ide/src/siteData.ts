// Deletes what this app stores in the browser: projects, Git data, credentials, settings, workbench
// state and caches. Other apps on the same origin keep theirs, so only names this app uses go.
// It runs on the next page load, before the workbench opens its databases: an open database
// cannot be deleted.
const PARAM = 'clear-site-data'
const TOKEN_KEY = 'frc-dev-wasm.clear-site-data'
const DATABASES = ['frc-dev-wasm', 'vscode-', 'cjFS_']
// vscode-resource-cache- is the webviews' resource cache
const CACHES = ['frc-', 'vscode-resource-cache-']
const OPFS_ENTRIES = ['frc-dev-wasm']
const STORAGE_KEYS = ['frc-dev-wasm', 'DefaultOverridesCacheExists', 'monaco-parts-splash']

const owned = (prefixes: string[]) => (name: string) => prefixes.some((p) => name.startsWith(p))

export function requestClearSiteData(): void {
  const token = crypto.randomUUID()
  sessionStorage.setItem(TOKEN_KEY, token)
  const url = new URL(window.location.href)
  url.searchParams.set(PARAM, token)
  window.location.replace(url)
}

// The token must match the one this tab stored, so a shared link with the parameter does nothing.
export async function clearSiteDataIfRequested(): Promise<void> {
  const url = new URL(window.location.href)
  const token = url.searchParams.get(PARAM)
  if (token === null) return
  url.searchParams.delete(PARAM)
  history.replaceState(history.state, '', url)
  if (token !== sessionStorage.getItem(TOKEN_KEY)) return
  await clearSiteData()
}

async function clearSiteData(): Promise<void> {
  const databases = (await indexedDB.databases()).map((db) => db.name ?? '').filter(owned(DATABASES))
  await Promise.all(databases.map(deleteDatabase))
  const opfs = await navigator.storage?.getDirectory().catch(() => undefined)
  if (opfs) {
    const names: string[] = []
    for await (const name of opfs.keys()) if (owned(OPFS_ENTRIES)(name)) names.push(name)
    await Promise.all(names.map((name) => opfs.removeEntry(name, { recursive: true }).catch(() => {})))
  }
  const cacheNames = (await caches.keys()).filter(owned(CACHES))
  await Promise.all(cacheNames.map((name) => caches.delete(name)))
  for (const storage of [localStorage, sessionStorage]) {
    const keys = Array.from({ length: storage.length }, (_, i) => storage.key(i) ?? '').filter(owned(STORAGE_KEYS))
    for (const key of keys) storage.removeItem(key)
  }
}

function deleteDatabase(name: string): Promise<void> {
  return new Promise((resolve) => {
    const req = indexedDB.deleteDatabase(name)
    req.onsuccess = req.onerror = () => resolve()
  })
}
