import { parseGitHubRepo } from '../../git/objects'
import type { ShellHost } from './builtins'
import { runGh } from './ghcli'
import { Collector, type Io } from './io'

const decoder = new TextDecoder()

export function gh(host: ShellHost, args: string[], io: Io, cwd: string, signal: AbortSignal): Promise<number> {
  // The answer of a git command in the working directory, or undefined when it fails
  const git = async (gitArgs: string[]): Promise<string | undefined> => {
    const out = new Collector()
    const code = await host.git(gitArgs, { out: (text) => out.write(text), err: () => {} }, cwd, signal)
    return code === 0 ? decoder.decode(out.bytes()).trim() : undefined
  }
  return runGh(args, {
    fetch: (url, init) => fetch(url, { ...init, signal }),
    token: host.githubToken,
    login: host.githubLogin,
    logout: host.githubLogout,
    async defaultRepo() {
      const remotes = ((await git(['remote', '-v'])) ?? '').split('\n').map((line) => line.split(/\s+/))
      const origin = remotes.find((r) => r[0] === 'origin') ?? remotes[0]
      return origin?.[1] ? parseGitHubRepo(origin[1]) : undefined
    },
    currentBranch: async () => (await git(['branch', '--show-current'])) || undefined,
    stdin: io.stdin,
    out: (text) => io.stdout.write(text),
    err: (text) => io.stderr.write(text)
  })
}
