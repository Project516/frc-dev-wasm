import { getService, ISecretStorageService } from '@codingame/monaco-vscode-api/services'
import type * as vscode from 'vscode'
import { GitHubClient, GitHubError } from './github'
import { plainText } from '../plainText'
import { deviceUri } from './deviceUri'

// Same key layout as ExtensionContext.secrets uses for this extension
const secretKey = (name: string) => JSON.stringify({ extensionId: 'frc-dev-wasm.wpilib', key: name })
// Where the GitHub token is saved
export const GITHUB_TOKEN_KEY = secretKey('github-token')

const PROMPTS = {
  'rate-limit': 'GitHub limits requests without signing in to 60 per hour, and that limit is used up.',
  'not-found': 'The repository was not found. If it is private, signing in to GitHub may give access.',
  auth: 'GitHub rejected the saved token.',
  required: 'Pushing needs a GitHub sign-in.',
  cli: 'The GitHub CLI needs a GitHub sign-in for this command.',
  git: 'GitHub asked git to sign in.',
  login: ''
}
type Reason = keyof typeof PROMPTS

// The project's GitHub OAuth App, for device sign-in. A client ID is public.
const CLIENT_ID = 'Ov23lib1brfI76h4pjKs'
const SCOPES = ['repo', 'workflow', 'read:user', 'user:email']

interface DeviceCode {
  device_code: string
  user_code: string
  verification_uri: string
  expires_in: number
  interval: number
}

// GitHub's device flow, through the proxy: github.com/login answers no browser requests. Resolves to
// undefined when the user picks a token instead, or the proxy cannot do it.
async function deviceSignIn(api: typeof vscode, proxy: string, reason: string): Promise<string | undefined> {
  const post = async <T>(step: string, body: Record<string, string>): Promise<T> => {
    const response = await fetch(`${proxy}/github${step}`, {
      method: 'POST',
      headers: { accept: 'application/json', 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(body)
    })
    if (!response.ok) throw new Error(`GitHub sign-in failed: ${response.status}`)
    return (await response.json()) as T
  }
  // A proxy without the sign-in route leaves the token prompt
  const code = await post<DeviceCode>('/login/device/code', { client_id: CLIENT_ID, scope: SCOPES.join(' ') }).catch(() => undefined)
  if (!code) return undefined
  const page = deviceUri(code.verification_uri)
  const open = 'Copy Code and Open GitHub'
  const token = 'Use a Token Instead'
  const choice = await api.window.showInformationMessage(
    `Sign in to GitHub with the code ${code.user_code}`,
    { modal: true, detail: `${reason} Enter the code at ${page}. It works for ${Math.round(code.expires_in / 60)} minutes.`.trim() },
    open,
    token
  )
  if (choice === token) return undefined
  if (choice !== open) throw new Error('Cancelled')
  await api.env.clipboard.writeText(code.user_code).then(undefined, () => undefined)
  await api.env.openExternal(api.Uri.parse(page))
  return api.window.withProgress(
    { location: api.ProgressLocation.Notification, title: `Waiting for GitHub sign-in with the code ${code.user_code}`, cancellable: true },
    async (_progress, cancelled) => {
      let interval = code.interval
      const deadline = Date.now() + code.expires_in * 1000
      while (Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, interval * 1000))
        if (cancelled.isCancellationRequested) throw new Error('Cancelled')
        const result = await post<{ access_token?: string; error?: string; interval?: number }>('/login/oauth/access_token', {
          client_id: CLIENT_ID,
          device_code: code.device_code,
          grant_type: 'urn:ietf:params:oauth:grant-type:device_code'
        })
        if (result.access_token) return result.access_token
        if (result.error === 'slow_down') interval = result.interval ?? interval + 5
        else if (result.error !== 'authorization_pending') throw new Error(`GitHub sign-in failed: ${result.error}`)
      }
      throw new Error('The GitHub sign-in code expired')
    }
  )
}

export class GitHubAuth {
  readonly client = new GitHubClient(() => this.token)
  private token: string | undefined
  private loaded: Promise<void> | undefined

  constructor(private readonly api: typeof vscode) {}

  private load(): Promise<void> {
    this.loaded ??= getService(ISecretStorageService).then(
      async (secrets) => {
        this.token = await secrets.get(GITHUB_TOKEN_KEY)
      },
      () => undefined
    )
    return this.loaded
  }

  private async save(token: string | undefined): Promise<void> {
    this.token = token
    const secrets = await getService(ISecretStorageService)
    if (token) await secrets.set(GITHUB_TOKEN_KEY, token)
    else await secrets.delete(GITHUB_TOKEN_KEY)
  }

  // Signs in with GitHub's device flow, or a personal access token when the proxy cannot
  private async signIn(reason: Reason): Promise<boolean> {
    const proxy = this.api.workspace.getConfiguration('wpilib').get<string>('vendorProxy')?.trim().replace(/\/$/, '')
    let token: string | undefined
    try {
      token = proxy ? await deviceSignIn(this.api, proxy, PROMPTS[reason]) : undefined
    } catch (e) {
      if (e instanceof Error && e.message !== 'Cancelled') void this.api.window.showErrorMessage(plainText(e.message))
      return false
    }
    token ??= (
      await this.api.window.showInputBox({
        title: 'GitHub personal access token',
        prompt: `${PROMPTS[reason]} Create a token at github.com/settings/tokens. It needs read and write access to repository contents.`.trim(),
        password: true,
        ignoreFocusOut: true,
        validateInput: (v) => (v.trim() ? undefined : 'Enter a token')
      })
    )?.trim()
    if (!token) return false
    await this.save(token)
    return true
  }

  // Signs in first when there is no token and `ask` gives the reason
  async getToken(ask?: Reason): Promise<string | undefined> {
    await this.load()
    if (!this.token && ask) await this.signIn(ask)
    return this.token
  }

  // For gh auth login and the GitHub extension: stores the given token, or signs in
  async login(token?: string): Promise<boolean> {
    await this.load()
    if (!token) return this.signIn('login')
    await this.save(token)
    return true
  }

  async signOut(): Promise<boolean> {
    await this.load()
    const had = !!this.token
    await this.save(undefined)
    return had
  }

  // Runs a GitHub task. When the API needs a token (rate limit, a repository that may be private, a
  // rejected token) it signs in and retries once. If signing in is cancelled the original error is thrown.
  async run<T>(task: (client: GitHubClient) => Promise<T>, options: { requireToken?: boolean } = {}): Promise<T> {
    await this.load()
    if (options.requireToken && !this.token && !(await this.signIn('required'))) throw new Error('Cancelled')
    for (let attempt = 0; ; attempt++) {
      try {
        return await task(this.client)
      } catch (error) {
        if (!(error instanceof GitHubError) || attempt > 0) throw error
        const kind = error.kind === 'rate-limit' || error.kind === 'not-found' ? (this.token ? undefined : error.kind) : error.kind
        if (kind !== 'rate-limit' && kind !== 'not-found' && kind !== 'auth') throw error
        if (kind === 'auth') await this.save(undefined)
        if (!(await this.signIn(kind))) throw error
      }
    }
  }
}
