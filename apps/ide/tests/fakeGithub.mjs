// An in-process stand-in for the GitHub REST endpoints the IDE uses, backed by a real repository.
// Objects it creates are built here, separately from the client code, so matching ids mean the
// client sends what GitHub would need to recreate the commits.
import fs from 'node:fs'
import { join } from 'node:path'
import * as git from 'isomorphic-git'

const encoder = new TextEncoder()

const utc = (seconds) => new Date(seconds * 1000).toISOString().replace('.000Z', 'Z')

function offsetOf(date) {
  const match = /([+-])(\d\d):(\d\d)$/.exec(date)
  return match ? `${match[1]}${match[2]}${match[3]}` : '+0000'
}

export async function createFakeGitHub(dir, { owner = 'test', repo = 'robot' } = {}) {
  const server = join(dir, 'server')
  fs.mkdirSync(server, { recursive: true })
  await git.init({ fs, dir: server, defaultBranch: 'main' })
  const requests = []
  let limited = false

  async function commit(files, message, { timezoneOffset = -480, timestamp } = {}) {
    for (const [path, value] of Object.entries(files)) {
      const full = join(server, path)
      fs.mkdirSync(join(full, '..'), { recursive: true })
      if (value === null) {
        fs.rmSync(full)
        await git.remove({ fs, dir: server, filepath: path })
        continue
      }
      const { content, mode } = typeof value === 'string' ? { content: value } : value
      fs.writeFileSync(full, content, { mode: mode ?? 0o644 })
      fs.chmodSync(full, mode ?? 0o644)
      await git.add({ fs, dir: server, filepath: path })
    }
    const person = { name: 'Server User', email: 'server@example.com', timestamp: timestamp ?? Math.floor(Date.now() / 1000), timezoneOffset }
    return git.commit({ fs, dir: server, message, author: person, committer: person })
  }

  const head = () => git.resolveRef({ fs, dir: server, ref: 'refs/heads/main' })

  async function apiCommit(oid) {
    const { commit: c } = await git.readCommit({ fs, dir: server, oid })
    const person = (p) => ({ name: p.name, email: p.email, date: utc(p.timestamp) })
    return {
      sha: oid,
      commit: { author: person(c.author), committer: person(c.committer), message: c.message, tree: { sha: c.tree } },
      parents: c.parent.map((sha) => ({ sha }))
    }
  }

  async function listTree(treeOid, prefix = '') {
    const { tree } = await git.readTree({ fs, dir: server, oid: treeOid })
    const out = []
    for (const e of tree) {
      out.push({ path: prefix + e.path, mode: e.mode, type: e.type, sha: e.oid })
      if (e.type === 'tree') out.push(...(await listTree(e.oid, `${prefix}${e.path}/`)))
    }
    return out
  }

  // Commit text as GitHub's servers build it from a POST body
  function commitText(body) {
    const when = (p) => `${Math.floor(Date.parse(p.date) / 1000)} ${offsetOf(p.date)}`
    let text = `tree ${body.tree}\n`
    for (const parent of body.parents) text += `parent ${parent}\n`
    text += `author ${body.author.name} <${body.author.email}> ${when(body.author)}\n`
    text += `committer ${body.committer.name} <${body.committer.email}> ${when(body.committer)}\n\n${body.message}`
    return text
  }

  const json = (data, status = 201) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
  const notFound = () => json({ message: 'Not Found' }, 404)

  async function handleApi(method, path, query, body, headers) {
    const base = `/repos/${owner}/${repo}`
    if (!path.startsWith(base)) return notFound()
    const rest = path.slice(base.length)
    if (rest === '') return json({ default_branch: 'main', private: false }, 200)
    let m
    if (method === 'GET' && (m = /^\/commits\/([^/]+)$/.exec(rest))) {
      const tip = await git.resolveRef({ fs, dir: server, ref: `refs/heads/${decodeURIComponent(m[1])}` }).catch(() => undefined)
      return tip ? json(await apiCommit(tip), 200) : notFound()
    }
    if (method === 'GET' && (m = /^\/git\/trees\/(\w+)$/.exec(rest))) {
      return json({ sha: m[1], tree: await listTree(m[1]), truncated: false }, 200)
    }
    if (method === 'GET' && (m = /^\/git\/blobs\/(\w+)$/.exec(rest))) {
      const { blob } = await git.readBlob({ fs, dir: server, oid: m[1] })
      return new Response(blob, { status: 200 })
    }
    if (method === 'GET' && rest === '/git/ref/heads/main') return json({ object: { sha: await head() } }, 200)
    if (method === 'GET' && (m = /^\/compare\/(\w+)\.\.\.(.+)$/.exec(rest))) {
      const [, baseOid] = m
      const tip = await head()
      const known = await git.readCommit({ fs, dir: server, oid: baseOid }).then(() => true, () => false)
      if (!known) return notFound()
      if (baseOid === tip) return json({ status: 'identical', total_commits: 0, commits: [] }, 200)
      if (await git.isDescendent({ fs, dir: server, oid: tip, ancestor: baseOid, depth: -1 })) {
        const commits = []
        for (let oid = tip; oid !== baseOid; oid = (await git.readCommit({ fs, dir: server, oid })).commit.parent[0]) commits.unshift(await apiCommit(oid))
        return json({ status: 'ahead', total_commits: commits.length, commits }, 200)
      }
      const behind = await git.isDescendent({ fs, dir: server, oid: baseOid, ancestor: tip, depth: -1 })
      return json({ status: behind ? 'behind' : 'diverged', total_commits: 0, commits: [] }, 200)
    }
    if (method === 'POST' && rest === '/git/blobs') {
      const sha = await git.writeBlob({ fs, dir: server, blob: Buffer.from(body.content, body.encoding === 'base64' ? 'base64' : 'utf8') })
      return json({ sha })
    }
    if (method === 'POST' && rest === '/git/trees') {
      const sha = await git.writeTree({ fs, dir: server, tree: body.tree.map((e) => ({ path: e.path, mode: e.mode, oid: e.sha, type: e.type })) })
      return json({ sha })
    }
    if (method === 'POST' && rest === '/git/commits') {
      const sha = await git.writeObject({ fs, dir: server, type: 'commit', format: 'content', object: encoder.encode(commitText(body)) })
      return json({ sha })
    }
    if (method === 'PATCH' && rest === '/git/refs/heads/main') {
      const tip = await head()
      const ok = body.force || (await git.isDescendent({ fs, dir: server, oid: body.sha, ancestor: tip, depth: -1 }))
      if (!ok) return json({ message: 'Update is not a fast forward' }, 422)
      await git.writeRef({ fs, dir: server, ref: 'refs/heads/main', value: body.sha, force: true })
      await git.checkout({ fs, dir: server, ref: 'main', force: true })
      return json({ object: { sha: body.sha } }, 200)
    }
    if (method === 'POST' && rest === '/git/refs') {
      await git.writeRef({ fs, dir: server, ref: body.ref, value: body.sha, force: true })
      return json({ ref: body.ref })
    }
    return json({ message: `fake GitHub has no ${method} ${rest}` }, 500)
  }

  const realFetch = globalThis.fetch
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(String(input))
    const method = init.method ?? 'GET'
    if (url.host === 'api.github.com') {
      requests.push(`${method} ${url.pathname}${url.search}`)
      if (limited) {
        return new Response(JSON.stringify({ message: 'API rate limit exceeded' }), {
          status: 403,
          headers: { 'x-ratelimit-remaining': '0', 'x-ratelimit-reset': String(Math.floor(Date.now() / 1000) + 3600) }
        })
      }
      return handleApi(method, url.pathname, url.searchParams, init.body ? JSON.parse(init.body) : undefined, init.headers)
    }
    if (url.host === 'raw.githubusercontent.com') {
      requests.push(`RAW ${url.pathname}`)
      const [, o, r, sha, ...path] = url.pathname.split('/')
      if (o !== owner || r !== repo) return new Response('', { status: 404 })
      try {
        const { blob } = await git.readBlob({ fs, dir: server, oid: sha, filepath: path.map(decodeURIComponent).join('/') })
        return new Response(blob, { status: 200 })
      } catch {
        return new Response('', { status: 404 })
      }
    }
    return realFetch(input, init)
  }

  return {
    owner,
    repo,
    server,
    requests,
    commit,
    head,
    setRateLimited: (value) => (limited = value),
    apiRequests: () => requests.filter((r) => !r.startsWith('RAW ')).length,
    close: () => (globalThis.fetch = realFetch)
  }
}
