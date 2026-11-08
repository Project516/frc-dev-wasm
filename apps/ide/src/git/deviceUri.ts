const DEVICE_PAGE = 'https://github.com/login/device'

// Only GitHub's own device page is opened. A proxy that answers for GitHub could send a phishing address.
export function deviceUri(uri: string): string {
  try {
    const url = new URL(uri)
    if (url.origin === 'https://github.com' && (url.pathname === '/login/device' || url.pathname.startsWith('/login/device/'))) return url.href
  } catch {
    // not an address
  }
  return DEVICE_PAGE
}
