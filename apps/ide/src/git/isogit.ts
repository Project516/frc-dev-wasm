import { Buffer } from 'buffer'
import type { HttpClient } from 'isomorphic-git'
import type { Git } from './github'

// isomorphic-git is loaded on first use so it stays out of the startup bundle.
export async function loadIsoGit(): Promise<{ git: Git; http: HttpClient }> {
  globalThis.Buffer ??= Buffer
  const [git, http] = await Promise.all([import('isomorphic-git'), import('isomorphic-git/http/web')])
  return { git, http: http.default }
}
