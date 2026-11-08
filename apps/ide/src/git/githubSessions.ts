import type * as vscode from 'vscode'
import type { TerminalGit } from './controller'

// The GitHub sign-in VS Code's GitHub extension asks for, with the token gh and git push use. VS Code's
// own provider signs in through a redirect this site cannot receive.
export function registerGitHubSessions(api: typeof vscode, git: TerminalGit): vscode.Disposable {
  const changed = new api.EventEmitter<vscode.AuthenticationProviderAuthenticationSessionsChangeEvent>()
  let account: { token: string; login: string } | undefined
  const sessionFor = async (token: string, scopes: readonly string[]): Promise<vscode.AuthenticationSession> => {
    if (account?.token !== token) {
      const user = await fetch('https://api.github.com/user', { headers: { Authorization: `token ${token}` } }).then(
        (r) => (r.ok ? (r.json() as Promise<{ login: string }>) : undefined),
        () => undefined
      )
      account = { token, login: user?.login ?? 'GitHub' }
    }
    return { id: 'github', accessToken: token, account: { id: account.login, label: account.login }, scopes }
  }
  const sessions = async (scopes: readonly string[] = []) => {
    const token = await git.token(false)
    return token ? [await sessionFor(token, scopes)] : []
  }
  return api.authentication.registerAuthenticationProvider(
    'github',
    'GitHub',
    {
      onDidChangeSessions: changed.event,
      getSessions: (scopes) => sessions(scopes),
      async createSession(scopes) {
        if (!(await git.login())) throw new Error('Cancelled')
        const [session] = await sessions(scopes)
        changed.fire({ added: [session], removed: [], changed: [] })
        return session
      },
      async removeSession() {
        const removed = await sessions()
        await git.logout()
        changed.fire({ added: [], removed, changed: [] })
      }
    },
    { supportsMultipleAccounts: false }
  )
}
