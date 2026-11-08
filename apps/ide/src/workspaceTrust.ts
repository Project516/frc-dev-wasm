import type { IEditorOverrideServices } from '@codingame/monaco-vscode-api'
import { getService, IContextKeyService } from '@codingame/monaco-vscode-api/services'
import { Event } from '@codingame/monaco-vscode-api/vscode/vs/base/common/event'
import { SyncDescriptor } from '@codingame/monaco-vscode-api/vscode/vs/platform/instantiation/common/descriptors'
import { WorkspaceTrustUriResponse } from '@codingame/monaco-vscode-api/vscode/vs/platform/workspace/common/workspaceTrust'
import { IWorkspaceTrustRequestService } from '@codingame/monaco-vscode-api/vscode/vs/platform/workspace/common/workspaceTrust.service'

// The workspace is trusted (createWorkspaceProvider), so every request for trust is granted. VS Code's
// Git extension opens a repository only when its folder is trusted.
class WorkspaceTrustRequestService implements IWorkspaceTrustRequestService {
  declare readonly _serviceBrand: undefined
  readonly onDidInitiateOpenFilesTrustRequest = Event.None
  readonly onDidInitiateWorkspaceTrustRequest = Event.None
  readonly onDidInitiateWorkspaceTrustRequestOnStartup = Event.None
  readonly onDidInitiateResourcesTrustRequest = Event.None
  async completeOpenFilesTrustRequest() {}
  async requestOpenFilesTrust() {
    return WorkspaceTrustUriResponse.Open
  }
  async completeResourcesTrustRequest() {}
  async requestResourcesTrust() {
    return true
  }
  cancelWorkspaceTrustRequest() {}
  async completeWorkspaceTrustRequest() {}
  async requestWorkspaceTrust() {
    return true
  }
  requestWorkspaceTrustOnStartup() {}
}

export const getWorkspaceTrustServices = (): IEditorOverrideServices => ({
  [IWorkspaceTrustRequestService.toString()]: new SyncDescriptor(WorkspaceTrustRequestService, [], true)
})

// With workspace trust disabled, VS Code still sets this key, and the WPILib extension's commands are
// enabled only when it is true
export async function setWorkspaceTrustedContext(): Promise<void> {
  const contextKeys = await getService(IContextKeyService)
  contextKeys.createKey('isWorkspaceTrusted', true)
}
