import {
  initialize,
  type IEditorOverrideServices,
  type IWorkbenchConstructionOptions
} from '@codingame/monaco-vscode-api'
import getAccessibilityServiceOverride from '@codingame/monaco-vscode-accessibility-service-override'
import getAuthenticationServiceOverride from '@codingame/monaco-vscode-authentication-service-override'
import getDialogsServiceOverride from '@codingame/monaco-vscode-dialogs-service-override'
import getEnvironmentServiceOverride from '@codingame/monaco-vscode-environment-service-override'
import getExplorerServiceOverride from '@codingame/monaco-vscode-explorer-service-override'
import getExtensionGalleryServiceOverride from '@codingame/monaco-vscode-extension-gallery-service-override'
import getExtensionServiceOverride from '@codingame/monaco-vscode-extensions-service-override'
import getFilesServiceOverride from '@codingame/monaco-vscode-files-service-override'
import getKeybindingsServiceOverride from '@codingame/monaco-vscode-keybindings-service-override'
import getLanguagesServiceOverride from '@codingame/monaco-vscode-languages-service-override'
import getLifecycleServiceOverride from '@codingame/monaco-vscode-lifecycle-service-override'
import getLogServiceOverride from '@codingame/monaco-vscode-log-service-override'
import getMarkersServiceOverride from '@codingame/monaco-vscode-markers-service-override'
import getModelServiceOverride from '@codingame/monaco-vscode-model-service-override'
import getNotificationServiceOverride from '@codingame/monaco-vscode-notifications-service-override'
import getOutlineServiceOverride from '@codingame/monaco-vscode-outline-service-override'
import getOutputServiceOverride from '@codingame/monaco-vscode-output-service-override'
import getPreferencesServiceOverride from '@codingame/monaco-vscode-preferences-service-override'
import getQuickAccessServiceOverride from '@codingame/monaco-vscode-quickaccess-service-override'
import getSecretStorageServiceOverride from '@codingame/monaco-vscode-secret-storage-service-override'
import getScmServiceOverride from '@codingame/monaco-vscode-scm-service-override'
import getSnippetsServiceOverride from '@codingame/monaco-vscode-snippets-service-override'
import getStatusBarServiceOverride from '@codingame/monaco-vscode-view-status-bar-service-override'
import getStorageServiceOverride from '@codingame/monaco-vscode-storage-service-override'
import getTerminalServiceOverride from '@codingame/monaco-vscode-terminal-service-override'
import getTextmateServiceOverride from '@codingame/monaco-vscode-textmate-service-override'
import getThemeServiceOverride from '@codingame/monaco-vscode-theme-service-override'
import getTimelineServiceOverride from '@codingame/monaco-vscode-timeline-service-override'
import getTitleBarServiceOverride from '@codingame/monaco-vscode-view-title-bar-service-override'
import getUserDataProfileServiceOverride from '@codingame/monaco-vscode-user-data-profile-service-override'
import getWalkThroughServiceOverride from '@codingame/monaco-vscode-walkthrough-service-override'
import getWelcomeServiceOverride from '@codingame/monaco-vscode-welcome-service-override'
import getWorkbenchServiceOverride from '@codingame/monaco-vscode-workbench-service-override'
import getWorkingCopyServiceOverride from '@codingame/monaco-vscode-working-copy-service-override'
import getConfigurationServiceOverride from '@codingame/monaco-vscode-configuration-service-override'
import { createIndexedDBProviders } from '@codingame/monaco-vscode-files-service-override'
import 'vscode/localExtensionHost'
import { replaceChatSetup } from './copilot/account'
import { copilotEnabled } from './copilot/enabled'
import { Worker } from './fakeWorker'
import { getSearchServices } from './searchService'
import { secretStorageProvider } from './secrets'
import { getWorkspaceTrustServices, setWorkspaceTrustedContext } from './workspaceTrust'
import { SHELL_PROFILE_TITLE } from './frc/terminal/profile'
import type { Uri } from 'vscode'
import { createWorkspaceProvider, mountWorkspace, workspaceFolder } from './workspace'

const workers: Partial<Record<string, Worker>> = {
  editorWorkerService: new Worker(
    new URL('monaco-editor/esm/vs/editor/editor.worker.js', import.meta.url),
    { type: 'module' }
  ),
  extensionHostWorkerMain: new Worker(
    new URL('@codingame/monaco-vscode-api/workers/extensionHost.worker', import.meta.url),
    { type: 'module' }
  ),
  TextMateWorker: new Worker(
    new URL('@codingame/monaco-vscode-textmate-service-override/worker', import.meta.url),
    { type: 'module' }
  ),
  OutputLinkDetectionWorker: new Worker(
    new URL('@codingame/monaco-vscode-output-service-override/worker', import.meta.url),
    { type: 'module' }
  ),
  LocalFileSearchWorker: new Worker(
    new URL('@codingame/monaco-vscode-search-service-override/worker', import.meta.url),
    { type: 'module' }
  )
}

window.MonacoEnvironment = {
  getWorkerUrl(_, label) {
    return workers[label]?.url.toString()
  },
  getWorkerOptions(_, label) {
    return workers[label]?.options
  }
}

const extensionsGallery = {
  serviceUrl: 'https://open-vsx.org/vscode/gallery',
  itemUrl: 'https://open-vsx.org/vscode/item',
  resourceUrlTemplate: 'https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}',
  extensionUrlTemplate: 'https://open-vsx.org/vscode/gallery/{publisher}/{name}/latest',
  controlUrl: '',
  nlsBaseUrl: ''
}

const options: IWorkbenchConstructionOptions = {
  enableWorkspaceTrust: false,
  secretStorageProvider,
  windowIndicator: { label: 'FRC Dev', tooltip: '', command: '' },
  configurationDefaults: {
    'window.title': 'FRC Dev${separator}${dirty}${activeEditorShort}',
    'window.titleSeparator': ': ',
    'workbench.colorTheme': 'Default Dark Modern',
    'workbench.iconTheme': 'vs-seti',
    'workbench.startupEditor': 'welcomePage',
    'editor.fontSize': 14,
    'editor.wordBasedSuggestions': 'off',
    'files.autoSave': 'afterDelay',
    'files.simpleDialog.enable': true,
    'terminal.integrated.gpuAcceleration': 'off',
    'terminal.integrated.defaultProfile.linux': SHELL_PROFILE_TITLE,
    'terminal.integrated.defaultProfile.osx': SHELL_PROFILE_TITLE,
    'terminal.integrated.defaultProfile.windows': SHELL_PROFILE_TITLE,
    'telemetry.telemetryLevel': 'off',
    'extensions.autoUpdate': false,
    'extensions.autoCheckUpdates': false
  },
  productConfiguration: {
    nameShort: 'FRC Dev',
    nameLong: 'FRC Dev',
    extensionsGallery,
    // As in VS Code, links to GitHub open without asking, which sign-in relies on
    linkProtectionTrustedDomains: ['https://github.com', 'https://*.github.com']
  }
}

const services: IEditorOverrideServices = {
  ...getAuthenticationServiceOverride(),
  ...getLogServiceOverride(),
  ...getExtensionServiceOverride({ enableWorkerExtensionHost: true }),
  ...getFilesServiceOverride(),
  ...getConfigurationServiceOverride(),
  ...getModelServiceOverride(),
  ...getNotificationServiceOverride(),
  ...getDialogsServiceOverride(),
  ...getKeybindingsServiceOverride(),
  ...getTextmateServiceOverride(),
  ...getThemeServiceOverride(),
  ...getLanguagesServiceOverride(),
  ...getPreferencesServiceOverride(),
  ...getSnippetsServiceOverride(),
  ...getOutlineServiceOverride(),
  ...getMarkersServiceOverride(),
  ...getTimelineServiceOverride(),
  ...getSearchServices(),
  ...getWorkspaceTrustServices(),
  ...getScmServiceOverride(),
  ...getSecretStorageServiceOverride(),
  ...getExplorerServiceOverride(),
  ...getOutputServiceOverride(),
  ...getTerminalServiceOverride(),
  ...getStatusBarServiceOverride(),
  ...getTitleBarServiceOverride(),
  ...getAccessibilityServiceOverride(),
  ...getStorageServiceOverride(),
  ...getLifecycleServiceOverride(),
  ...getEnvironmentServiceOverride(),
  ...getWorkingCopyServiceOverride(),
  ...getUserDataProfileServiceOverride(),
  ...getWelcomeServiceOverride(),
  ...getWalkThroughServiceOverride(),
  ...getExtensionGalleryServiceOverride({ webOnly: true }),
  ...getWorkbenchServiceOverride(),
  ...getQuickAccessServiceOverride({
    isKeybindingConfigurationVisible: () => true,
    shouldUseGlobalPicker: () => true
  })
}

// Mounts the browser workspace and resolves to the folder the workbench opens
export async function openWorkspace(): Promise<Uri> {
  await createIndexedDBProviders()
  return mountWorkspace()
}

export async function startWorkbench(folder: Uri): Promise<void> {
  // VS Code's chat is a large part of the workbench, so it loads only for those who turned Copilot on
  const copilot = copilotEnabled() ? (await import('./copilot/services')).copilotServices() : {}
  const container = document.createElement('div')
  // Fixed to the visible viewport; 100vh overflows behind mobile and toolbar-shrunk viewports.
  container.style.cssText = 'position:fixed;inset:0;overflow:hidden'
  document.body.replaceChildren(container)
  await initialize({ ...services, ...copilot }, container, { ...options, workspaceProvider: createWorkspaceProvider(folder) }, { userHome: workspaceFolder.with({ path: '/' }) })
  await setWorkspaceTrustedContext()
  if (copilotEnabled()) replaceChatSetup()
}
