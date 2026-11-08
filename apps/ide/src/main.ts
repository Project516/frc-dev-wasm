import '@codingame/monaco-vscode-configuration-editing-default-extension'
import '@codingame/monaco-vscode-groovy-default-extension'
import '@codingame/monaco-vscode-ini-default-extension'
import '@codingame/monaco-vscode-java-default-extension'
import '@codingame/monaco-vscode-json-default-extension'
import '@codingame/monaco-vscode-markdown-basics-default-extension'
import '@codingame/monaco-vscode-search-result-default-extension'
import '@codingame/monaco-vscode-shellscript-default-extension'
import '@codingame/monaco-vscode-theme-defaults-default-extension'
import '@codingame/monaco-vscode-theme-seti-default-extension'
import '@codingame/monaco-vscode-xml-default-extension'
import '@codingame/monaco-vscode-yaml-default-extension'
import { registerFrcExtension } from './frc/extension'
import { createWpilibServices } from './frc/wpilibServices'
import { openWorkspace, startWorkbench } from './setup'
import { clearSiteDataIfRequested } from './siteData'
import { loadVscodeWpilib } from './frc/vscodeWpilib'

await clearSiteDataIfRequested()
const folder = await openWorkspace()
const vscodeWpilib = await loadVscodeWpilib(folder)
await startWorkbench(folder)
await registerFrcExtension(createWpilibServices, vscodeWpilib)
