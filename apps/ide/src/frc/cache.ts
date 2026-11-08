// Caches of the FRC tools. The workspace (`frc-dev-wasm-files`), the workbench state and
// saved credentials are never read or deleted here.
import { WPILIB_YEARS, pruneVendorCache } from '@frc-dev-wasm/runtime'

// CheerpJ's persistent `/files/` mount
const CHEERPJ_DB = 'cjFS_/files/'
const CHEERPJ_STORE = 'files'
const APP_DIR = '/frcdevwasm/'
const BUILD_OUTPUT = [`${APP_DIR}build.jar`, `${APP_DIR}vendor-out.jar`, `${APP_DIR}robot`]
const SWING_DIR = `${APP_DIR}swing/`
const SWING_MAX_AGE_MS = 60 * 24 * 60 * 60 * 1000
const VENDOR_MAX_AGE_MS = 60 * 24 * 60 * 60 * 1000
const RUNTIME = new URL(`${import.meta.env.BASE_URL}runtime/`, window.location.href)

export interface Removed {
  entries: number
  bytes: number
}

interface Entry {
  key: string
  bytes: number
  modified: number
}

// CheerpJ keeps a record per path. A directory record lists its children as `/<name>`.
interface FsRecord {
  type: 'file' | 'dir'
  contents: { byteLength?: number; size?: number } | string[]
  lastModified?: number
}

const request = <T>(req: IDBRequest<T>) =>
  new Promise<T>((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })

async function openCheerpjFiles(): Promise<IDBDatabase | undefined> {
  const databases = await indexedDB.databases()
  if (!databases.some((db) => db.name === CHEERPJ_DB)) return undefined
  return request(indexedDB.open(CHEERPJ_DB))
}

// Entries under the app folder, which is small. Everything else in the mount is left alone.
async function listEntries(db: IDBDatabase): Promise<Entry[]> {
  const store = db.transaction(CHEERPJ_STORE).objectStore(CHEERPJ_STORE)
  const range = IDBKeyRange.bound(APP_DIR, `${APP_DIR}\uffff`)
  const entries: Entry[] = []
  await new Promise<void>((resolve, reject) => {
    const cursor = store.openCursor(range)
    cursor.onerror = () => reject(cursor.error)
    cursor.onsuccess = () => {
      const row = cursor.result
      if (!row) return resolve()
      const value = row.value as FsRecord
      const size = value.type === 'file' && !Array.isArray(value.contents) ? (value.contents.byteLength ?? value.contents.size ?? 0) : 0
      // Unknown age counts as new
      entries.push({ key: String(row.key), bytes: size, modified: value.lastModified ? value.lastModified * 1000 : Date.now() })
      row.continue()
    }
  })
  return entries
}

// Deletes the entries and drops them from their parent directory records, in one transaction.
async function removeEntries(db: IDBDatabase, entries: Entry[]): Promise<Removed> {
  if (entries.length === 0) return { entries: 0, bytes: 0 }
  const tx = db.transaction(CHEERPJ_STORE, 'readwrite')
  const store = tx.objectStore(CHEERPJ_STORE)
  const keys = new Set(entries.map((e) => e.key))
  const done = new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error)
  })
  for (const key of keys) store.delete(key)
  const children = new Map<string, Set<string>>()
  for (const key of keys) {
    const slash = key.lastIndexOf('/')
    const parent = key.slice(0, slash)
    if (keys.has(parent)) continue
    children.set(parent, (children.get(parent) ?? new Set()).add(key.slice(slash)))
  }
  for (const [parent, names] of children) {
    const record = (await request(store.get(parent))) as FsRecord | undefined
    if (!record || !Array.isArray(record.contents)) continue
    record.contents = record.contents.filter((name) => !names.has(name))
    store.put(record, parent)
  }
  await done
  return { entries: entries.length, bytes: entries.reduce((sum, e) => sum + e.bytes, 0) }
}

const isUnder = (key: string, path: string) => key === path || key.startsWith(`${path}/`)

// Safe to run at any time: removes only what is stale. The next build recreates build output.
export async function cleanStaleCaches({ hasRobotProject }: { hasRobotProject: boolean }): Promise<Removed> {
  const [files, vendor] = await Promise.all([
    cleanStaleFiles(hasRobotProject),
    cleanStaleVendorJars().catch(() => ({ entries: 0, bytes: 0 }))
  ])
  return { entries: files.entries + vendor.entries, bytes: files.bytes + vendor.bytes }
}

// Transformed vendor jars from an older transform, and the ones no project has used for a long time.
// Each season has its transform version. One the site does not serve (404) has no use for its jars.
async function cleanStaleVendorJars(): Promise<Removed> {
  const versions: string[] = []
  for (const year of WPILIB_YEARS) {
    const response = await fetch(new URL(`${year}/vendor/transform.json`, RUNTIME))
    if (response.status === 404) continue
    if (!response.ok) return { entries: 0, bytes: 0 }
    versions.push(((await response.json()) as { version: string }).version)
  }
  return pruneVendorCache(versions, VENDOR_MAX_AGE_MS)
}

async function cleanStaleFiles(hasRobotProject: boolean): Promise<Removed> {
  const db = await openCheerpjFiles()
  if (!db) return { entries: 0, bytes: 0 }
  try {
    const entries = await listEntries(db)
    const stale = new Set<Entry>()
    // Build output is one fixed path for all projects, so it is stale once none is left
    if (!hasRobotProject) {
      for (const entry of entries) if (BUILD_OUTPUT.some((path) => isUnder(entry.key, path))) stale.add(entry)
    }
    // A Swing folder is unused when nothing in it changed for a long time
    const folderOf = (key: string) => key.slice(SWING_DIR.length).split('/')[0]
    const newest = new Map<string, number>()
    for (const entry of entries) {
      if (!entry.key.startsWith(SWING_DIR)) continue
      const id = folderOf(entry.key)
      newest.set(id, Math.max(newest.get(id) ?? 0, entry.modified))
    }
    const cutoff = Date.now() - SWING_MAX_AGE_MS
    for (const entry of entries) {
      if (entry.key.startsWith(SWING_DIR) && (newest.get(folderOf(entry.key)) ?? 0) < cutoff) stale.add(entry)
    }
    return await removeEntries(db, [...stale])
  } finally {
    db.close()
  }
}

// Removes everything the tools cached. Cache Storage is cleared too, as it only ever holds copies.
// The browser's HTTP cache, which holds the downloaded runtime files, is not reachable from a page.
export async function clearCaches(): Promise<Removed> {
  const removed: Removed = { entries: 0, bytes: 0 }
  const db = await openCheerpjFiles()
  if (db) {
    try {
      const result = await removeEntries(db, await listEntries(db))
      removed.entries += result.entries
      removed.bytes += result.bytes
    } finally {
      db.close()
    }
  }
  for (const name of await caches.keys()) {
    if (await caches.delete(name)) removed.entries++
  }
  return removed
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
