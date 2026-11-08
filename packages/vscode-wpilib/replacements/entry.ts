// Serves the extension's own folder to the fs shim, then activates the extension
import type * as vscode from 'vscode'
import { mount } from 'fs'
import { activate as activateWpilib, deactivate } from 'vscode-wpilib-src/extension'
import { connectHost } from './host'

export async function activate(context: vscode.ExtensionContext) {
  const host = await connectHost()
  mount(context.extensionPath, host.extensionBase, host.extensionFiles)
  return activateWpilib(context)
}

export { deactivate }
