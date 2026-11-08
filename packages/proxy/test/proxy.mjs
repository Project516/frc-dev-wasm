// Runs the Worker's fetch handler under Node with a stubbed upstream fetch.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import proxy, { MAVEN_REPOSITORIES } from '../src/index.js'

const env = { ALLOWED_ORIGINS: 'https://frcdev.project516.dev,https://frcdev-nightly.project516.dev' }
const IDE = 'https://frcdev.project516.dev'
const BASE = 'https://proxy.test'

let upstreamRequests = []
globalThis.fetch = async (url, init) => {
  upstreamRequests.push({ url: String(url), init })
  return new Response('upstream', { status: 200, headers: { 'set-cookie': 'a=b', 'content-type': 'application/java-archive' } })
}

async function call(pathname, { method = 'GET', origin = IDE, headers = {}, body } = {}) {
  upstreamRequests = []
  const request = new Request(BASE + pathname, { method, body, headers: { ...(origin ? { origin } : {}), ...headers } })
  return proxy.fetch(request, env)
}

let failures = 0
async function test(name, fn) {
  try {
    await fn()
    console.log(`ok   ${name}`)
  } catch (error) {
    failures++
    console.log(`FAIL ${name}\n     ${error.message}`)
  }
}

const PHOENIX = '/maven/maven.ctr-electronics.com/release/com/ctre/phoenix6/wpiapi-java/26.3.0/wpiapi-java-26.3.0.jar'

await test('forwards a vendor jar download with CORS headers', async () => {
  const response = await call(PHOENIX)
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('access-control-allow-origin'), IDE)
  assert.equal(response.headers.get('set-cookie'), null)
  assert.equal(upstreamRequests[0].url, `https:/${PHOENIX.slice('/maven'.length)}`)
})

await test('forwards what the WPILib extension reads: the vendordep marketplace and GradleRIO releases', async () => {
  for (const path of ['/maven/frcmaven.wpi.edu/artifactory/vendordeps/vendordep-marketplace/2026.json', '/maven/plugins.gradle.org/m2/edu/wpi/first/GradleRIO/maven-metadata.xml']) {
    const response = await call(path)
    assert.equal(response.status, 200, path)
    assert.equal(upstreamRequests.at(-1).url, `https:/${path.slice('/maven'.length)}`)
    assert.equal(upstreamRequests.at(-1).init.cf.cacheTtlByStatus['200-299'], 3600, 'files that change are cached for an hour')
  }
  await call(PHOENIX)
  assert.equal(upstreamRequests.at(-1).init.cf.cacheTtlByStatus['200-299'], 604800, 'released artifacts are cached for a week')
})

await test('allows the IDE on localhost', async () => {
  const response = await call(PHOENIX, { origin: 'http://localhost:5173' })
  assert.equal(response.headers.get('access-control-allow-origin'), 'http://localhost:5173')
})

await test('rejects requests without an allowed origin', async () => {
  assert.equal((await call(PHOENIX, { origin: null })).status, 403)
  assert.equal((await call(PHOENIX, { origin: 'https://example.com' })).status, 403)
  assert.equal((await call(PHOENIX, { origin: 'http://localhost.example.com' })).status, 403)
  assert.equal(upstreamRequests.length, 0)
})

await test('answers preflight requests', async () => {
  const response = await call(PHOENIX, { method: 'OPTIONS' })
  assert.equal(response.status, 204)
  assert.match(response.headers.get('access-control-allow-headers'), /authorization/)
})

for (const [name, pathname] of [
  ['an unlisted host', '/maven/example.com/release/a/b/1/b-1.jar'],
  ['a host that only starts like a listed one', '/maven/jitpack.io.example.com/a/b/1/b-1.jar'],
  ['a user name in front of another host', '/maven/maven.revrobotics.com@example.com/a/b/1/b-1.jar'],
  ['a path that climbs out of a repository', '/maven/maven.revrobotics.com/../example.com/a.jar'],
  ['an encoded climb', '/maven/maven.revrobotics.com/%2e%2e/example.com/a.jar'],
  ['a file that is not an artifact', '/maven/maven.revrobotics.com/index.html'],
  ['a query string', '/maven/maven.revrobotics.com/a/b/1/b-1.jar?x=1'],
  ['an unknown route', '/other/maven.revrobotics.com/a.jar']
]) {
  await test(`rejects ${name}`, async () => {
    const response = await call(pathname)
    assert.equal(response.status, 403)
    assert.equal(upstreamRequests.length, 0)
  })
}

await test('downloads Maven Central files from its mirror', async () => {
  await call('/maven/repo1.maven.org/maven2/org/dyn4j/dyn4j/5.0.2/dyn4j-5.0.2.jar')
  assert.equal(upstreamRequests[0].url, 'https://maven-central.storage-download.googleapis.com/maven2/org/dyn4j/dyn4j/5.0.2/dyn4j-5.0.2.jar')
})

await test('rejects uploads to a maven repository', async () => {
  assert.equal((await call(PHOENIX, { method: 'POST', body: 'x' })).status, 403)
})

await test('forwards git ref discovery with credentials', async () => {
  const response = await call('/git/gitlab.com/team/robot.git/info/refs?service=git-upload-pack', {
    headers: { authorization: 'Basic abc', cookie: 'c=d' }
  })
  assert.equal(response.status, 200)
  const { url, init } = upstreamRequests[0]
  assert.equal(url, 'https://gitlab.com/team/robot.git/info/refs?service=git-upload-pack')
  assert.equal(init.headers.get('authorization'), 'Basic abc')
  assert.equal(init.headers.get('cookie'), null)
})

await test('forwards a git push body', async () => {
  const response = await call('/git/gitlab.com/team/robot.git/git-receive-pack', {
    method: 'POST',
    body: 'pack',
    headers: { 'content-type': 'application/x-git-receive-pack-request' }
  })
  assert.equal(response.status, 200)
  assert.equal(upstreamRequests[0].init.method, 'POST')
})

await test('rejects git requests to other paths', async () => {
  assert.equal((await call('/git/gitlab.com/team/robot.git/raw/main/secrets')).status, 403)
  assert.equal((await call('/git/gitlab.com/api/v4/user')).status, 403)
})

await test('rejects git endpoints named only in the query', async () => {
  assert.equal((await call('/git/example.com/admin/delete?x=/git-upload-pack')).status, 403)
  assert.equal((await call('/git/example.com/api/x?y=/git-receive-pack', { method: 'POST', body: 'x' })).status, 403)
  assert.equal((await call('/git/gitlab.com/team/robot.git/git-upload-pack?x=1', { method: 'POST', body: 'x' })).status, 403)
  assert.equal((await call('/git/gitlab.com/team/robot.git/info/refs?service=git-upload-pack&x=1')).status, 403)
  assert.equal((await call('/git/gitlab.com/team/robot.git/info/refs')).status, 403)
  assert.equal(upstreamRequests.length, 0)
})

await test('rejects git hosts that are addresses, have a port or credentials', async () => {
  for (const host of ['127.0.0.1', '169.254.169.254', '[::1]', 'localhost', 'gitlab.com:8443', 'user:pw@evil.com', '0x7f.1']) {
    const response = await call(`/git/${host}/team/robot.git/info/refs?service=git-upload-pack`)
    assert.equal(response.status, 403, host)
  }
  assert.equal(upstreamRequests.length, 0)
})

await test('follows a git redirect only to the same host', async () => {
  const fetchBefore = globalThis.fetch
  try {
    const answers = [
      new Response(null, { status: 301, headers: { location: 'https://gitlab.com/team/renamed.git/info/refs?service=git-upload-pack' } }),
      new Response('refs', { status: 200 })
    ]
    globalThis.fetch = async (url, init) => {
      upstreamRequests.push({ url: String(url), init })
      return answers.shift()
    }
    const renamed = await call('/git/gitlab.com/team/robot.git/info/refs?service=git-upload-pack', { headers: { authorization: 'Basic abc' } })
    assert.equal(renamed.status, 200)
    assert.equal(upstreamRequests[1].url, 'https://gitlab.com/team/renamed.git/info/refs?service=git-upload-pack')
    assert.equal(upstreamRequests[0].init.redirect, 'manual')

    globalThis.fetch = async (url, init) => {
      upstreamRequests.push({ url: String(url), init })
      return new Response(null, { status: 302, headers: { location: 'https://evil.example/collect/info/refs?service=git-upload-pack' } })
    }
    const away = await call('/git/gitlab.com/team/robot.git/info/refs?service=git-upload-pack', { headers: { authorization: 'Basic abc' } })
    assert.equal(away.status, 502)
    assert.equal(upstreamRequests.length, 1)
  } finally {
    globalThis.fetch = fetchBefore
  }
})

await test('forwards the steps of GitHub device sign-in', async () => {
  for (const step of ['/login/device/code', '/login/oauth/access_token']) {
    const response = await call(`/github${step}`, {
      method: 'POST',
      body: 'client_id=x',
      headers: { accept: 'application/json', 'content-type': 'application/x-www-form-urlencoded', cookie: 'c=d' }
    })
    assert.equal(response.status, 200)
    const { url, init } = upstreamRequests[0]
    assert.equal(url, `https://github.com${step}`)
    assert.equal(init.headers.get('accept'), 'application/json')
    assert.equal(init.headers.get('cookie'), null)
  }
})

await test('rejects other GitHub requests', async () => {
  assert.equal((await call('/github/login/device/code')).status, 403)
  assert.equal((await call('/github/login/oauth/authorize', { method: 'POST', body: 'x' })).status, 403)
  assert.equal((await call('/github/login/device/code?x=1', { method: 'POST', body: 'x' })).status, 403)
  assert.equal((await call('/github/settings/tokens', { method: 'POST', body: 'x' })).status, 403)
})

// fetch-deps mirrors each season's marketplace into .cache/<year>/vendordeps. A new repository there needs adding.
const cache = path.resolve(import.meta.dirname, '../../../.cache')
const vendordeps = fs.existsSync(cache)
  ? fs.readdirSync(cache).filter((d) => /^\d{4}$/.test(d)).map((d) => path.join(cache, d, 'vendordeps')).filter((d) => fs.existsSync(d))
  : []
if (vendordeps.length) {
  await test('lists every repository in the vendor marketplace', async () => {
    const missing = new Set()
    for (const file of vendordeps.flatMap((dir) => fs.readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => path.join(dir, f)))) {
      const urls = JSON.parse(fs.readFileSync(file, 'utf8')).mavenUrls ?? []
      for (const url of urls) {
        const repository = url.endsWith('/') ? url : `${url}/`
        if (!MAVEN_REPOSITORIES.includes(repository)) missing.add(repository)
      }
    }
    assert.deepEqual([...missing], [])
  })
} else {
  console.log('skip marketplace check: run pnpm fetch-deps first')
}

if (failures) {
  console.log(`${failures} failed`)
  process.exit(1)
}
