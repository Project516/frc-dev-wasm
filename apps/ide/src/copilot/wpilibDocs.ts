// Reads WPILib's javadoc and docs sites, which both allow CORS

export type Get = (url: string) => Promise<string>

export interface Source {
  label: string
  javadoc: string
  docs: string
}

const JAVADOC = 'https://github.wpilib.org/allwpilib/docs/'
const DOCS = 'https://docs.wpilib.org/en/'
const MAX_TEXT = 20_000
const MAX_SUGGESTIONS = 10
const MAX_RESULTS = 8

export const SOURCES: Record<string, Source> = {
  '2026': { label: 'WPILib 2026', javadoc: `${JAVADOC}release/java/`, docs: `${DOCS}stable/` },
  '2027': { label: 'WPILib 2027 (alpha)', javadoc: `${JAVADOC}beta/java/`, docs: `${DOCS}latest/` }
}

export interface JavaType {
  p: string
  l: string
}
export interface JavaMember extends JavaType {
  c: string
  u?: string
}
interface JavaPackage {
  l: string
  m?: string
}
export interface SphinxIndex {
  docnames: string[]
  titles: string[]
  alltitles: Record<string, [number, string | null][]>
  terms: Record<string, number | number[]>
  titleterms: Record<string, number | number[]>
}

// A javadoc index is `xxxSearchIndex = [...];updateSearchResults();`
export function parseJavadocIndex<T>(text: string): T[] {
  const index = parseJson(text.slice(text.indexOf('['), text.lastIndexOf(']') + 1))
  if (!Array.isArray(index)) throw new Error('The javadoc search index is not a list, so its format changed.')
  return index
}

// A Sphinx index is `Search.setIndex({...})`
export function parseSphinxIndex(text: string): SphinxIndex {
  const index = parseJson(text.slice(text.indexOf('(') + 1, text.lastIndexOf(')')))
  if (!Array.isArray(index?.docnames) || !index.terms) throw new Error("The docs search index is not Sphinx's, so its format changed.")
  return index
}

function parseJson(text: string) {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

const qualified = (type: JavaType) => `${type.p}.${type.l}`

// The first of these that matches anything wins: qualified name, name, name of a nested class
export function findTypes(types: JavaType[], name: string): JavaType[] {
  const wanted = name.trim().toLowerCase()
  const tiers = [
    (t: JavaType) => qualified(t).toLowerCase() === wanted,
    (t: JavaType) => t.l.toLowerCase() === wanted,
    (t: JavaType) => t.l.toLowerCase().split('.').pop() === wanted
  ]
  for (const matches of tiers) {
    const found = types.filter(matches)
    if (found.length) return found
  }
  return []
}

export function suggestTypes(types: JavaType[], name: string): string[] {
  const wanted = (name.split('.').pop() ?? name).trim().toLowerCase()
  if (!wanted) return []
  return types
    .filter((t) => t.l.toLowerCase().includes(wanted) || wanted.includes(t.l.toLowerCase()))
    .map(qualified)
    .sort((a, b) => a.length - b.length || a.localeCompare(b))
    .slice(0, MAX_SUGGESTIONS)
}

// The page ids of the members called `member` in a class
export function memberAnchors(members: JavaMember[], type: JavaType, member: string): string[] {
  const wanted = member.trim().toLowerCase()
  return members
    .filter((m) => m.p === type.p && m.c === type.l && m.l.split('(')[0].toLowerCase() === wanted)
    .map((m) => m.u ?? m.l)
}

export function typeUrl(root: string, type: JavaType, module?: string): string {
  return `${root}${module ? `${module}/` : ''}${type.p.replaceAll('.', '/')}/${type.l}.html`
}

const SKIP = new Set(['SCRIPT', 'STYLE', 'NAV', 'HEADER', 'FOOTER', 'NOSCRIPT', 'BUTTON', 'SVG', 'IFRAME'])
const BLOCK = new Set(['P', 'DIV', 'SECTION', 'ARTICLE', 'UL', 'OL', 'LI', 'TR', 'TABLE', 'PRE', 'DL', 'DT', 'DD', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BLOCKQUOTE', 'BR'])

// Page text with the page's own layout, without its navigation
export function elementText(root: Element): string {
  let out = ''
  const walk = (node: Node, pre: boolean) => {
    if (node.nodeType === 3) {
      const text = (node.textContent ?? '').replaceAll(' ', ' ')
      out += pre ? text : text.replace(/\s+/g, ' ')
      return
    }
    if (node.nodeType !== 1) return
    const el = node as Element
    if (SKIP.has(el.tagName.toUpperCase()) || el.classList.contains('headerlink')) return
    const block = BLOCK.has(el.tagName)
    if (block) out += '\n'
    el.childNodes.forEach((child, i) => {
      if (i > 0 && (el.tagName === 'TR' || el.tagName === 'THEAD') && child.nodeType === 1) out += ' | '
      walk(child, pre || el.tagName === 'PRE')
    })
    if (block) out += '\n'
  }
  walk(root, false)
  return out
    .split('\n')
    .map((line) => line.replace(/[ \t]+/g, ' ').trim())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

export function capText(text: string): string {
  return text.length > MAX_TEXT ? `${text.slice(0, MAX_TEXT)}\n\n(cut after ${MAX_TEXT} characters)` : text
}

function parse(html: string): Document {
  return new DOMParser().parseFromString(html, 'text/html')
}

// Stems differ from the word: Sphinx indexes "scheduler" as "schedul"
function variants(word: string): string[] {
  const out = new Set([word])
  for (const suffix of ['s', 'es', 'ing', 'ed', 'er', 'ers', 'e', 'ly']) {
    if (word.length > suffix.length + 2 && word.endsWith(suffix)) out.add(word.slice(0, -suffix.length))
  }
  return [...out]
}

const docsOf = (value: number | number[] | undefined) => (value === undefined ? [] : Array.isArray(value) ? value : [value])

export interface DocHit {
  docname: string
  title: string
  score: number
}

// Documents that match the most query words first, then by where the words are
export function searchSphinx(index: SphinxIndex, query: string, limit = MAX_RESULTS): DocHit[] {
  const words = [...new Set(query.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean))]
  if (!words.length) return []
  const found = new Map<number, { words: number; score: number }>()
  const sectionTitles = Object.entries(index.alltitles).map(([title, at]) => [title.toLowerCase(), at] as const)
  for (const word of words) {
    const scores = new Map<number, number>()
    const add = (doc: number, score: number) => scores.set(doc, Math.max(scores.get(doc) ?? 0, score))
    index.titles.forEach((title, doc) => title.toLowerCase().includes(word) && add(doc, 10))
    for (const [title, at] of sectionTitles) if (title.includes(word)) for (const [doc] of at) add(doc, 6)
    for (const v of variants(word)) {
      for (const doc of docsOf(index.titleterms[v])) add(doc, 8)
      for (const doc of docsOf(index.terms[v])) add(doc, 1)
    }
    for (const [doc, score] of scores) {
      const have = found.get(doc) ?? { words: 0, score: 0 }
      found.set(doc, { words: have.words + 1, score: have.score + score })
    }
  }
  return [...found]
    .sort(([, a], [, b]) => b.words - a.words || b.score - a.score)
    .slice(0, limit)
    .map(([doc, { score }]) => ({ docname: index.docnames[doc], title: index.titles[doc] || index.docnames[doc], score }))
}

// A page of the docs site: a docname, a path or an address on it
export function docsPageUrl(base: string, page: string): string {
  const host = new URL(base).hostname
  if (/^https?:\/\//i.test(page)) {
    const url = new URL(page)
    if (url.hostname !== host) throw new Error(`Only pages on ${host} can be read.`)
    return url.href
  }
  const name = page.trim().replace(/^\/+/, '').replace(/\.(html|rst)$/, '')
  return `${base}${name}.html`
}

const indexes = new Map<string, Promise<unknown>>()
function cached<T>(url: string, load: () => Promise<T>): Promise<T> {
  let promise = indexes.get(url) as Promise<T> | undefined
  if (!promise) {
    promise = load()
    indexes.set(url, promise)
    promise.catch(() => indexes.delete(url))
  }
  return promise
}
const javadocIndex = <T>(get: Get, root: string, file: string) => cached(root + file, async () => parseJavadocIndex<T>(await get(root + file)))

const notFound = (types: JavaType[], name: string, source: Source) => {
  const near = suggestTypes(types, name)
  return (
    `No class named ${name} in the ${source.label} javadoc.` +
    (near.length ? ` Closest names:\n${near.join('\n')}` : '') +
    (source.label.includes('2027') ? '\nIn 2027 many classes moved from edu.wpi.first to org.wpilib.' : '')
  )
}

// The class page of `name` (Class, package.Class, Class.member or Class#member) as text
export async function lookupApi(get: Get, source: Source, name: string): Promise<string> {
  const root = source.javadoc
  const types = await javadocIndex<JavaType>(get, root, 'type-search-index.js')
  const hash = name.indexOf('#')
  let className = hash < 0 ? name : name.slice(0, hash)
  let member = hash < 0 ? '' : name.slice(hash + 1)
  let matches = findTypes(types, className)
  const dot = className.lastIndexOf('.')
  if (!matches.length && hash < 0 && dot > 0) {
    matches = findTypes(types, className.slice(0, dot))
    if (matches.length) {
      member = className.slice(dot + 1)
      className = className.slice(0, dot)
    }
  }
  if (!matches.length) return notFound(types, name, source)
  if (matches.length > 1) return `${matches.length} classes match ${className} in the ${source.label} javadoc. Ask again with the full name:\n${matches.map(qualified).join('\n')}`

  const type = matches[0]
  const packages = await javadocIndex<JavaPackage>(get, root, 'package-search-index.js')
  const url = typeUrl(root, type, packages.find((p) => p.l === type.p)?.m)
  const document = parse(await get(url))
  const main = document.querySelector('main') ?? document.body
  const heading = `${qualified(type)} (${source.label} javadoc, ${url})`
  let note = ''
  if (member) {
    const members = await javadocIndex<JavaMember>(get, root, 'member-search-index.js')
    const anchors = memberAnchors(members, type, member)
    const sections = [...main.querySelectorAll('section.detail')].filter((s) => anchors.includes(s.id))
    if (sections.length) return capText(`${heading}\n\n${sections.map(elementText).join('\n\n')}`)
    note = `\n(${member} was not found in ${type.l}; the class page follows.)`
  }
  return capText(`${heading}${note}\n\n${elementText(main)}`)
}

// A search of the docs, or one page of them as text
export async function lookupDocs(get: Get, source: Source, input: { query?: string; page?: string }): Promise<string> {
  const base = source.docs
  if (input.page) {
    let url = docsPageUrl(base, input.page)
    let document = parse(await get(url))
    // Moved pages answer with a redirect page
    const refresh = document.querySelector('meta[http-equiv="refresh" i]')?.getAttribute('content')?.match(/url=(.+)$/i)?.[1]
    if (refresh) {
      url = docsPageUrl(base, new URL(refresh, url).href)
      document = parse(await get(url))
    }
    const main = document.querySelector('div[role=main], .document, article')
    if (!main) return `No page content at ${url}.`
    return capText(`${url} (${source.label} docs)\n\n${elementText(main)}`)
  }
  if (!input.query) throw new Error('wpilib_docs needs query or page.')
  const index = await cached(`${base}searchindex.js`, async () => parseSphinxIndex(await get(`${base}searchindex.js`)))
  const hits = searchSphinx(index, input.query)
  if (!hits.length) return `No pages of the ${source.label} docs match "${input.query}".`
  return `${source.label} docs matching "${input.query}":\n${hits.map((h) => `${h.title} - ${base}${h.docname}.html`).join('\n')}`
}
