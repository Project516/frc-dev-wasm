import type { IEditorOverrideServices } from '@codingame/monaco-vscode-api'
import { IDefaultAccountService } from '@codingame/monaco-vscode-api/vscode/vs/platform/defaultAccount/common/defaultAccount.service'
import { SyncDescriptor } from '@codingame/monaco-vscode-api/vscode/vs/platform/instantiation/common/descriptors'
import getChatServiceOverride, { ChatEntitlement } from '@codingame/monaco-vscode-chat-service-override'
import getMcpServiceOverride from '@codingame/monaco-vscode-mcp-service-override'
import getNotebookServiceOverride from '@codingame/monaco-vscode-notebook-service-override'
import getStorageServiceOverride from '@codingame/monaco-vscode-storage-service-override'
import { GitHubAccountService } from './account'

// The workbench services the Chat view needs
export function copilotServices(): IEditorOverrideServices {
  return {
    ...getStorageServiceOverride({
      forcedValues: {
        // Skips VS Code's Copilot setup, which installs the Copilot extension. The built-in provider signs in.
        'chat.setupContext': { entitlement: ChatEntitlement.Enterprise, registered: true, completed: true, installed: true }
      }
    }),
    ...getChatServiceOverride(),
    [IDefaultAccountService.toString()]: new SyncDescriptor(GitHubAccountService, [], true),
    // Chat starts MCP servers before each request, and its code block actions ask the notebook service about files
    ...getMcpServiceOverride(),
    ...getNotebookServiceOverride()
  }
}
