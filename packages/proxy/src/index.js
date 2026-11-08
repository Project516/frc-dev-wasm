// Makes the requests a browser page cannot: downloads from vendor maven repositories that send no
// CORS headers, git requests, and GitHub's device sign-in.
//   /maven/<repository path>/<artifact>   GET or HEAD, repositories in MAVEN_REPOSITORIES only
//   /git/<host>/<repo>/<git endpoint>      what a git client sends to a CORS proxy
//   /github/login/device/code             POST, the two steps of GitHub's device flow
//   /github/login/oauth/access_token

// The mavenUrls of the WPILib vendor library marketplace, the repository GradleRIO adds to every
// project, and what the WPILib extension reads: the marketplace itself and GradleRIO's releases.
// Each ends with a slash, so a prefix match pins the host.
export const MAVEN_REPOSITORIES = [
  'https://3015rangerrobotics.github.io/pathplannerlib/repo/',
  'https://andymarkproductsoftware.github.io/amlib-vendordep/repo/2026/',
  'https://cdn.yassrobotics.com/',
  'https://dev.studica.com/maven/release/2026/',
  'https://docs.home.thethriftybot.com/',
  'https://frcmaven.wpi.edu/artifactory/littletonrobotics-mvn-release/',
  'https://frcmaven.wpi.edu/artifactory/release/',
  'https://frcmaven.wpi.edu/artifactory/sleipnirgroup-mvn-release/',
  'https://frcmaven.wpi.edu/artifactory/vendordeps/',
  'https://jitpack.io/',
  'https://maven.ctr-electronics.com/release/',
  'https://maven.photonvision.org/repository/internal/',
  'https://maven.photonvision.org/repository/snapshots/',
  'https://maven.reduxrobotics.com/',
  'https://maven.revrobotics.com/',
  'https://packages.lumynlabs.com/repo/',
  'https://plugins.gradle.org/m2/',
  'https://repo1.maven.org/maven2/',
  'https://shenzhen-robotics-alliance.github.io/maple-sim/vendordep/repos/releases/',
  'https://software.thethriftybot.com/maven/',
  'https://storage.googleapis.com/grapple-frc-maven/',
  'https://www.playingwithfusion.com/frc/maven/',
  'https://yet-another-software-suite.github.io/YAGSL/releases/',
  'https://yet-another-software-suite.github.io/YAMS/releases/'
]

// Maven Central blocks Cloudflare's shared outgoing addresses, so its files come from Google's copy.
const MIRRORS = { 'https://repo1.maven.org/maven2/': 'https://maven-central.storage-download.googleapis.com/maven2/' }

const ARTIFACT = /(\.(jar|pom|module|json|sha1|sha256|md5)|\/maven-metadata\.xml)$/
const GIT_SERVICES = ['git-upload-pack', 'git-receive-pack']
const GIT_HEADERS = ['accept', 'authorization', 'content-type', 'git-protocol']
const LOCALHOST = /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/
const GITHUB_LOGIN = ['/login/device/code', '/login/oauth/access_token']

const MARKETPLACE = 'https://frcmaven.wpi.edu/artifactory/vendordeps/'
// A public git host by name: no address literal, port or credentials, which would let the proxy reach
// anything else. URL parsing has already turned hex, octal and short IPv4 forms into dotted decimal.
const gitHost = (target) =>
  !target.username && !target.password && !target.port && target.hostname.includes('.') && !target.hostname.startsWith('[') && !/^[\d.]+$/.test(target.hostname)

// Only the three git smart HTTP endpoints, with nothing else in the query.
function gitEndpoint(target, method) {
  if (!gitHost(target)) return false
  if (method === 'GET') return target.pathname.endsWith('/info/refs') && GIT_SERVICES.some((s) => target.search === `?service=${s}`)
  return method === 'POST' && !target.search && GIT_SERVICES.some((s) => target.pathname.endsWith(`/${s}`))
}

const mutable = (target) => target.pathname.endsWith('/maven-metadata.xml') || target.href.startsWith(MARKETPLACE)

function allowedOrigin(origin, env) {
  if (!origin) return undefined
  const allowed = (env.ALLOWED_ORIGINS ?? '').split(',').map((o) => o.trim())
  return allowed.includes(origin) || LOCALHOST.test(origin) ? origin : undefined
}

/** The upstream URL for a proxy request, or undefined when the request is not allowed. */
export function upstream(url, method) {
  const [, route, ...rest] = url.pathname.split('/')
  let target
  try {
    target = new URL(`https://${rest.join('/')}${url.search}`)
  } catch {
    return undefined
  }
  if (route === 'maven') {
    const ok =
      (method === 'GET' || method === 'HEAD') &&
      !url.search &&
      ARTIFACT.test(target.pathname) &&
      MAVEN_REPOSITORIES.some((repository) => target.href.startsWith(repository))
    if (!ok) return undefined
    for (const [repository, mirror] of Object.entries(MIRRORS)) {
      if (target.href.startsWith(repository)) return { kind: 'maven', target: new URL(mirror + target.href.slice(repository.length)) }
    }
    return { kind: 'maven', target }
  }
  if (route === 'git' && gitEndpoint(target, method)) {
    return { kind: 'git', target }
  }
  const login = `/${rest.join('/')}`
  if (route === 'github' && method === 'POST' && !url.search && GITHUB_LOGIN.includes(login)) {
    return { kind: 'login', target: new URL(`https://github.com${login}`) }
  }
  return undefined
}

const MAX_REDIRECTS = 5

async function send(route, request, headers) {
  if (route.kind === 'maven') {
    return fetch(route.target, {
      method: request.method,
      headers,
      redirect: 'follow',
      // Released artifacts never change, so the edge cache answers repeat downloads. Version lists
      // and the vendordep marketplace do change.
      cf: { cacheEverything: true, cacheTtlByStatus: { '200-299': mutable(route.target) ? 3600 : 604800, 404: 60, '500-599': 0 } }
    })
  }
  // Git and sign-in requests can carry a token, so a redirect is followed only to the same host, as
  // a renamed repository's ref discovery needs. A POST body cannot be sent twice.
  let target = route.target
  for (let i = 0; ; i++) {
    const response = await fetch(target, {
      method: request.method,
      headers,
      body: request.method === 'POST' ? request.body : undefined,
      redirect: 'manual'
    })
    const location = response.headers.get('location')
    if (request.method !== 'GET' || response.status < 300 || response.status > 399 || !location || i === MAX_REDIRECTS) return response
    const next = new URL(location, target)
    if (next.host !== target.host || !gitEndpoint(next, 'GET')) return new Response('Redirected to another host\n', { status: 502 })
    target = next
  }
}

export default {
  async fetch(request, env) {
    const origin = allowedOrigin(request.headers.get('origin'), env)
    if (!origin) return new Response('This proxy only serves the FRC IDE\n', { status: 403 })
    const cors = {
      'access-control-allow-origin': origin,
      'access-control-allow-methods': 'GET, HEAD, POST, OPTIONS',
      'access-control-allow-headers': GIT_HEADERS.join(', '),
      'access-control-expose-headers': '*',
      'access-control-max-age': '86400',
      vary: 'origin'
    }
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors })

    const route = upstream(new URL(request.url), request.method)
    if (!route) return new Response('Not an allowed maven, git or sign-in request\n', { status: 403, headers: cors })
    // The zone's rate limiting rule covers only the custom domain, not the pages.dev address, so limit here too
    if (env.LIMITER && route.kind !== 'maven') {
      const { success } = await env.LIMITER.limit({ key: request.headers.get('cf-connecting-ip') ?? '' })
      if (!success) return new Response('Too many requests\n', { status: 429, headers: cors })
    }

    const headers = new Headers({ 'user-agent': 'frc-dev-wasm-proxy' })
    if (route.kind === 'git') {
      for (const name of GIT_HEADERS) {
        const value = request.headers.get(name)
        if (value) headers.set(name, value)
      }
      headers.set('user-agent', 'git/2.0 (frc-dev-wasm-proxy)')
    }
    if (route.kind === 'login') {
      for (const name of ['accept', 'content-type']) {
        const value = request.headers.get(name)
        if (value) headers.set(name, value)
      }
    }
    const response = await send(route, request, headers)
    const result = new Response(response.body, response)
    result.headers.delete('set-cookie')
    for (const [name, value] of Object.entries(cors)) result.headers.set(name, value)
    return result
  }
}
