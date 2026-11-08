// The Authorization header for the host of a URL. Own keys only, so a host named `constructor` gets none.
export function authFor(auth: Record<string, string>, url: string): string | undefined {
  const host = new URL(url).host
  return Object.hasOwn(auth, host) ? auth[host] : undefined
}
