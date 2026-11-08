import {
  VendorDownloadError,
  VendorLibraries,
  type DiskJar,
  type JavaCompiler,
  type RuntimeAssets,
  type VendorJar,
  type VendorProgress,
  type VendorRequirement
} from '@frc-dev-wasm/runtime'
import type * as vscode from 'vscode'
import { plainText } from '../plainText'

export const IMPORT_VENDOR_JARS = 'wpilib.importVendorJars'
const IMPORT_TITLE = 'WPILib: Import Vendor Jars from Disk'
const PROXY_SETTING = 'vendorProxy'

export interface VendorSupport {
  // Downloads what the cache lacks. Progress shows in the status bar and downloads go to `write`.
  resolve(requirements: VendorRequirement[], write?: (text: string) => void): Promise<VendorJar[]>
  // The versions a BOM manages, for the platform() of a build.gradle
  managedVersions(bom: VendorRequirement): Promise<Map<string, string>>
  // Resolves in the background. A failure becomes a warning with an offer to import the jars from disk.
  prepare(requirements: VendorRequirement[]): Promise<void>
  importFromDisk(requirements: VendorRequirement[]): Promise<void>
}

export function createVendorSupport(
  api: typeof vscode,
  getAssets: () => Promise<RuntimeAssets>,
  getCompiler: () => Promise<JavaCompiler>,
  log: (text: string) => void
): VendorSupport {
  let warnedOffline = false
  let libraries: Promise<VendorLibraries> | undefined
  const get = () =>
    (libraries ??= getAssets().then(
      (assets) =>
        new VendorLibraries({
          assets,
          transform: async (jar, name) => (await getCompiler()).transformVendorJar(jar, name),
          proxy: () => api.workspace.getConfiguration('wpilib').get<string>(PROXY_SETTING)
        })
    ))

  const withProgress = <T>(title: string, task: (report: (message: string) => void) => Promise<T>) =>
    api.window.withProgress({ location: api.ProgressLocation.Window, title }, (progress) =>
      task((message) => progress.report({ message }))
    )

  const resolve: VendorSupport['resolve'] = async (requirements, write) => {
    if (requirements.length === 0) return []
    const lib = await get()
    try {
      return await withProgress('Vendor libraries', (report) =>
        lib.resolve(requirements, {
          onProgress: (p: VendorProgress) => {
            report(`${p.done} of ${p.total}: ${p.jar}`)
            if (p.downloaded) write?.(`Downloaded ${p.jar}\n`)
          },
          onWarning: (message) => (write ?? log)(`warning: ${message}\n`)
        })
      )
    } catch (error) {
      if (error instanceof VendorDownloadError) {
        throw new Error(`${error.message}. Run "${IMPORT_TITLE}" to use a copy from a WPILib install or a Gradle cache.`)
      }
      throw error
    }
  }

  return {
    resolve,

    managedVersions: async (bom) => (await get()).managedVersions(bom),

    async prepare(requirements) {
      try {
        await resolve(requirements)
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        log(`${message}\n`)
        if (warnedOffline) return
        warnedOffline = true
        const choice = await api.window.showWarningMessage(plainText(`WPILib: ${message}`), 'Import Jars...')
        if (choice) await api.commands.executeCommand(IMPORT_VENDOR_JARS)
      }
    },

    async importFromDisk(requirements) {
      if (requirements.length === 0) {
        void api.window.showInformationMessage('No robot project with vendor libraries is open.')
        return
      }
      const needed = requirements.map((r) => `${r.artifact}-${r.version}.jar`)
      const how = await api.window.showQuickPick(
        [
          { label: 'Choose jar files', folder: false },
          {
            label: 'Choose a folder to scan',
            description: 'Such as the maven folder of a WPILib install or ~/.gradle/caches',
            folder: true
          }
        ],
        { title: IMPORT_TITLE, placeHolder: `Needs ${needed.join(', ')}` }
      )
      if (!how) return
      const files = (await pickFromDisk(how.folder)).filter((f) => f.name.endsWith('.jar'))
      const disk: DiskJar[] = files.map((f) => ({ name: f.name, read: async () => new Uint8Array(await f.arrayBuffer()) }))
      const lib = await get()
      const { imported, missing } = await withProgress('Importing vendor jars', () => lib.importJars(requirements, disk))
      if (imported.length > 0) {
        void api.window.showInformationMessage(plainText(`Imported ${imported.join(', ')}.`))
      }
      if (missing.length > 0) {
        void api.window.showWarningMessage(plainText(`Still missing ${missing.join(', ')}. Pick a folder that has them, or connect to the network.`))
      } else if (imported.length === 0) {
        void api.window.showInformationMessage('All vendor jars are already available.')
      }
    }
  }
}

// The browser cannot read the disk without the user choosing, so this is the page's own file picker.
function pickFromDisk(folder: boolean): Promise<File[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.multiple = true
    if (folder) input.webkitdirectory = true
    else input.accept = '.jar'
    input.addEventListener('change', () => resolve([...(input.files ?? [])]))
    input.addEventListener('cancel', () => resolve([]))
    input.click()
  })
}
