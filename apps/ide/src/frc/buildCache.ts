import type { CompileOptions, CompileResult, RuntimeAssets } from '@frc-dev-wasm/runtime'
import type * as vscode from 'vscode'

// Gradle's up-to-date check of compileJava: the classes of the last build stay in the project's build folder, so
// `./gradlew clean` removes them, with a hash of everything the compile read.
const DIR = 'build/frc-dev-wasm'
// gversion writes the time of each build. The classes keep the time of the build that compiled them.
const GVERSION = 'Automatically generated file containing build version information.'
const BUILD_TIME = /^.*\bBUILD_(DATE|UNIX_TIME)\b.*$/gm

const encoder = new TextEncoder()
const decoder = new TextDecoder()
const compilerHashes = new Map<string, Promise<Uint8Array>>()
const hex = (bytes: Uint8Array) => [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')

async function sha256(parts: Uint8Array[]): Promise<Uint8Array> {
  const all = new Uint8Array(parts.reduce((n, p) => n + p.byteLength, 0))
  let at = 0
  for (const part of parts) {
    all.set(part, at)
    at += part.byteLength
  }
  return new Uint8Array(await crypto.subtle.digest('SHA-256', all))
}

// The compile driver and the JVM are served at fixed names, so their bytes go into the key. javac comes with the JVM:
// CheerpJ is named by its loader URL, and java-web's worker changes with each release.
function compilerHash(assets: RuntimeAssets): Promise<Uint8Array> {
  let hash = compilerHashes.get(assets.year)
  if (!hash) {
    const files = [assets.url('frcdevwasm-tools.jar')]
    if (assets.jvmLoader.endsWith('/java-web.js')) files.push(new URL('java-web-worker.js', assets.jvmLoader))
    hash = Promise.all(
      files.map(async (file) => {
        const res = await fetch(file)
        if (!res.ok) throw new Error(`${file}: ${res.status}`)
        return new Uint8Array(await res.arrayBuffer())
      })
    ).then(sha256)
    compilerHashes.set(assets.year, hash)
    hash.catch(() => compilerHashes.delete(assets.year))
  }
  return hash
}

export async function compileKey(
  assets: RuntimeAssets,
  sources: Record<string, string | Uint8Array>,
  options: CompileOptions
): Promise<string> {
  const parts: Uint8Array[] = [
    await compilerHash(assets),
    encoder.encode(
      JSON.stringify([
        assets.year,
        assets.wpilibVersion,
        assets.jvmLoader,
        options.classpath ?? [],
        options.processors ?? [],
        (options.vendorJars ?? []).map((jar) => jar.key),
        (options.processorJars ?? []).map((jar) => jar.key)
      ])
    )
  ]
  for (const path of Object.keys(sources).sort()) {
    let data = sources[path]
    const text = typeof data === 'string' ? data : decoder.decode(data)
    if (text.includes(GVERSION)) data = text.replace(BUILD_TIME, '')
    const bytes = typeof data === 'string' ? encoder.encode(data) : data
    parts.push(encoder.encode(`\0${path}\0${bytes.byteLength}\0`), bytes)
  }
  return hex(await sha256(parts))
}

/** The result of the last build of `root`, when its key is `key`. */
export async function storedBuild(api: typeof vscode, root: vscode.Uri, key: string): Promise<CompileResult | undefined> {
  try {
    const info = JSON.parse(decoder.decode(await api.workspace.fs.readFile(api.Uri.joinPath(root, DIR, 'compileJava.json'))))
    if (info.key !== key) return undefined
    const jar = await api.workspace.fs.readFile(api.Uri.joinPath(root, DIR, 'compileJava.jar'))
    // Two builds that store at the same time can leave one's key with the other's jar
    if (info.jar !== hex(await sha256([jar]))) return undefined
    return { success: true, diagnostics: info.diagnostics ?? [], jar, millis: 0 }
  } catch {
    return undefined
  }
}

export async function storeBuild(api: typeof vscode, root: vscode.Uri, key: string, result: CompileResult): Promise<void> {
  if (!result.jar) return
  const dir = api.Uri.joinPath(root, DIR)
  const info = api.Uri.joinPath(dir, 'compileJava.json')
  try {
    await api.workspace.fs.createDirectory(dir)
    await api.workspace.fs.writeFile(api.Uri.joinPath(dir, 'compileJava.jar'), result.jar)
    await api.workspace.fs.writeFile(info, encoder.encode(JSON.stringify({ key, jar: hex(await sha256([result.jar])), diagnostics: result.diagnostics })))
  } catch {
    // The next build compiles again
  }
}
