import { getService, ICommandService, ISecretStorageService } from '@codingame/monaco-vscode-api/services'
import type { IDefaultAccount, IEntitlementsData } from '@codingame/monaco-vscode-api/vscode/vs/base/common/defaultAccount'
import { Emitter, Event } from '@codingame/monaco-vscode-api/vscode/vs/base/common/event'
import type { IDefaultAccountService } from '@codingame/monaco-vscode-api/vscode/vs/platform/defaultAccount/common/defaultAccount.service'
import { MANAGED_SETTINGS_FRESHNESS_NOT_REQUIRED } from '@codingame/monaco-vscode-api/vscode/vs/platform/policy/common/managedSettingsFreshness'
import { CommandsRegistry } from '@codingame/monaco-vscode-api/vscode/vs/platform/commands/common/commands'
import { GITHUB_TOKEN_KEY } from '../git/auth'

export const SIGN_IN_COMMAND = 'frc-dev-wasm.copilot.signIn'
export const SIGN_OUT_COMMAND = 'frc-dev-wasm.copilot.signOut'

const PROVIDER = { id: 'github', name: 'GitHub', enterprise: false }

// The account VS Code's Copilot status and chat read: the GitHub sign-in git and gh use, with the plan and
// usage GitHub reports for it.
export class GitHubAccountService implements IDefaultAccountService {
  declare readonly _serviceBrand: undefined
  private readonly changed = new Emitter<IDefaultAccount | null>()
  readonly onDidChangeDefaultAccount = this.changed.event
  currentDefaultAccount: IDefaultAccount | null = null
  private resolved: Promise<IDefaultAccount | null> | undefined

  readonly onDidChangePolicyData = Event.None
  readonly policyData = null
  readonly copilotTokenInfo = null
  readonly onDidChangeCopilotTokenInfo = Event.None
  readonly managedSettingsFetchStatus = null as never
  readonly managedSettingsFetchedAt = null
  readonly managedSettingsRawResponse = undefined
  readonly managedSettingsCompatibilityError = null
  readonly onDidChangeManagedSettingsCompatibilityError = Event.None
  readonly managedSettingsFreshness = MANAGED_SETTINGS_FRESHNESS_NOT_REQUIRED
  readonly onDidChangeManagedSettingsFreshness = Event.None

  constructor() {
    void getService(ISecretStorageService).then((secrets) =>
      secrets.onDidChangeSecret((key) => {
        if (key === GITHUB_TOKEN_KEY) void this.refresh()
      })
    )
  }

  getDefaultAccount(): Promise<IDefaultAccount | null> {
    return (this.resolved ??= this.resolve())
  }

  async refresh(): Promise<IDefaultAccount | null> {
    this.resolved = this.resolve()
    return this.resolved
  }

  private async resolve(): Promise<IDefaultAccount | null> {
    const token = await (await getService(ISecretStorageService)).get(GITHUB_TOKEN_KEY)
    let account: IDefaultAccount | null = null
    if (token) {
      const response = await fetch('https://api.github.com/copilot_internal/user', { headers: { authorization: `token ${token}` }, signal: AbortSignal.timeout(10_000) }).catch(() => undefined)
      const data = response?.ok ? ((await response.json()) as IEntitlementsData & { login?: string }) : null
      account = { authenticationProvider: PROVIDER, accountName: data?.login ?? 'GitHub', sessionId: 'github', enterprise: false, entitlementsData: data }
    }
    if (JSON.stringify(account) !== JSON.stringify(this.currentDefaultAccount)) {
      this.currentDefaultAccount = account
      this.changed.fire(account)
    }
    return account
  }

  getDefaultAccountAuthenticationProvider() {
    return PROVIDER
  }

  setDefaultAccountProvider(): void {}

  // Saving the token refreshes the account
  async signIn(): Promise<IDefaultAccount | null> {
    await (await getService(ICommandService)).executeCommand(SIGN_IN_COMMAND)
    return this.getDefaultAccount()
  }

  async signOut(): Promise<void> {
    await (await getService(ICommandService)).executeCommand(SIGN_OUT_COMMAND)
  }

  resolveGitHubUrl(path: string): string {
    return `https://github.com/${path}`
  }
}

// VS Code's Copilot setup installs the Copilot extension. Here signing in to GitHub is the whole setup.
export function replaceChatSetup(): void {
  for (const id of ['workbench.action.chat.triggerSetup', 'workbench.action.chat.triggerSetupSupportAnonymousAction']) {
    CommandsRegistry.registerCommand(id, async (accessor) => {
      const commands = accessor.get(ICommandService)
      // The extension registers the sign-in after startup, and the status item can be clicked first
      if (!CommandsRegistry.getCommand(SIGN_IN_COMMAND)) await Event.toPromise(Event.filter(CommandsRegistry.onDidRegisterCommand, (command) => command === SIGN_IN_COMMAND))
      return commands.executeCommand(SIGN_IN_COMMAND)
    })
  }
}
