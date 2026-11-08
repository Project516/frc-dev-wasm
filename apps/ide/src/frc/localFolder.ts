import type * as vscode from 'vscode'
import { closeFolder, hasSavedFolder, localFoldersSupported, pendingFolder, pickLocalFolder } from '../workspace'
import { plainText } from '../plainText'

// The stock Open Folder and Close Folder commands are disabled in the browser, and Open Folder cannot use
// the native folder picker. The palette entries are our own commands, and the stock Open Folder id is
// taken over for its other callers.
export function registerLocalFolder(api: typeof vscode): void {
  const openFolder = async () => {
    if (!localFoldersSupported) {
      void api.window.showWarningMessage(
        'This browser cannot open folders from disk, so files stay in the in-browser workspace. Use a Chromium-based browser to open a local folder.'
      )
      return
    }
    const folder = await pickLocalFolder()
    if (folder) await api.commands.executeCommand('vscode.openFolder', api.Uri.file(folder.path))
  }
  api.commands.registerCommand('wpilib.openFolder', openFolder)
  api.commands.registerCommand('workbench.action.files.openFolder', openFolder)
  api.commands.registerCommand('wpilib.closeFolder', () => {
    if (hasSavedFolder()) closeFolder()
    else void api.window.showInformationMessage('The in-browser workspace is already open.')
  })

  if (pendingFolder) {
    const folder = pendingFolder
    void api.window
      .showInformationMessage(plainText(`The browser needs your permission to reopen the folder "${folder.name}".`), 'Reopen Folder')
      .then((choice) => (choice ? folder.reopen() : undefined))
  }
}
