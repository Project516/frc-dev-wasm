// Also run by test/vendor.mjs under Node, so no parameter properties.
// Vendor libraries are not served by this site. The browser downloads each jar from the vendor's
// maven repository, makes it run without native code (NativeStubber, in the compile JVM) and keeps
// the result in Cache Storage, keyed by repository host, library version and transform version.
import type { RuntimeAssets } from './assets.ts';
import { mayNeedTransform } from './classfile.ts';
import { pomDependencies, pomImports, pomManagedVersions, pomParent } from './pom.ts';
import { WPILIB_GROUP } from './years.ts';

/** A jar a project needs. `repos` are maven repository URLs without a trailing slash, in the order to try them. */
export interface VendorRequirement {
  group: string;
  artifact: string;
  version: string;
  repos: string[];
  /** A plain maven library or annotation processor. It is only transformed when it has native methods. Its dependencies are plain too. */
  plain?: boolean;
}

/** A transformed jar in the cache. */
export interface VendorJar {
  /** Unique per jar content source: the repository host and the coordinate. */
  key: string;
  /** File name, such as `wpiapi-java-26.3.0.jar`. */
  name: string;
  data(): Promise<Uint8Array>;
}

export interface VendorProgress {
  /** The jar that was just finished, or the first one at the start. */
  jar: string;
  /** Whether `jar` was downloaded just now, and not found in the cache. */
  downloaded: boolean;
  done: number;
  total: number;
}

export interface ResolveHooks {
  onProgress?: (progress: VendorProgress) => void;
  /** Problems that do not stop the build, such as a dependency that could not be downloaded. */
  onWarning?: (message: string) => void;
}

/** A jar the browser could not download. */
export class VendorDownloadError extends Error {
  readonly jar: string;
  readonly url: string;

  constructor(jar: string, url: string, reason: string) {
    super(`Could not download ${jar} from ${url}: ${reason}`);
    this.jar = jar;
    this.url = url;
  }
}

export interface StoredInfo {
  /** `group:artifact:version` of the jars this one needs. */
  deps: string[];
  /** When the jar was last used, in milliseconds since the epoch. */
  used: number;
}

/** Where transformed jars are kept. */
export interface JarStore {
  info(key: string): Promise<StoredInfo | undefined>;
  read(key: string): Promise<Uint8Array | undefined>;
  write(key: string, data: Uint8Array, info: StoredInfo): Promise<void>;
  /** Records that the jar was used, without rewriting it. */
  touch(key: string, used: number): Promise<void>;
}

export interface VendorLibrariesOptions {
  assets: RuntimeAssets;
  /** Makes a downloaded jar run without native code. Resolves to the same bytes when nothing needed to change. */
  transform(jar: Uint8Array, name: string): Promise<Uint8Array>;
  /** Base URL of the proxy for repositories that send no CORS headers. Empty or undefined for none. */
  proxy?: () => string | undefined;
  /** Defaults to Cache Storage, or memory where that is not available. */
  store?: JarStore;
  fetch?: typeof fetch;
}

export interface VendorIndex {
  vendordeps: Array<{ file: string; name: string; version: string; uuid: string; description?: string }>;
  /** Annotation processor `group:artifact` to the key in `bundled`. */
  processors: Record<string, string>;
  /** Jars the site serves itself: the WPILib vendordeps and the processors. */
  bundled: Record<string, { jar: string; deps: string[] } | null>;
  /** Dependencies of the vendor jars at build time, so they are known without a pom. */
  transitive: Record<string, string[]>;
}

interface Obtained {
  key: string;
  deps: string[];
  downloaded: boolean;
  warnings: string[];
}

export const CENTRAL = 'https://repo1.maven.org/maven2';
/** The repository GradleRIO adds to every project. */
export const FRCMAVEN_RELEASE = 'https://frcmaven.wpi.edu/artifactory/release';
export const VENDOR_CACHE_PREFIX = 'frc-vendor-jars-';
const DAY_MS = 24 * 60 * 60 * 1000;
const CONCURRENCY = 3;
const MAX_PARENTS = 5;
/** The cache host of jars the user imported from disk. */
const DISK_HOST = 'disk';
const SAFE_NAME = /^[A-Za-z0-9._+-]+$/;

export const coordinate = (r: { group: string; artifact: string; version: string }) => `${r.group}:${r.artifact}:${r.version}`;

/** Letters, digits and `. _ + -` only, because these end up in URLs, cache keys and file names. A part is never `.` or `..`. */
export function isSafeCoordinate(r: { group: string; artifact: string; version: string }): boolean {
  return [r.group, r.artifact, r.version].every((part) => typeof part === 'string' && SAFE_NAME.test(part) && part !== '.' && part !== '..');
}

/** Throws a clear error when a coordinate from a vendordep could not be used in a URL. */
export function assertSafeCoordinate(r: { group: string; artifact: string; version: string }, source: string): void {
  if (!isSafeCoordinate(r)) {
    throw new Error(`${source}: "${r.group}:${r.artifact}:${r.version}" is not a valid maven coordinate. Only letters, digits and . _ + - are allowed.`);
  }
}

function parseCoordinate(key: string): { group: string; artifact: string; version: string } | undefined {
  const [group, artifact, version, extra] = key.split(':');
  const parsed = { group, artifact, version };
  return extra === undefined && version !== undefined && isSafeCoordinate(parsed) ? parsed : undefined;
}

const libraryOf = (r: { group: string; artifact: string }) => `${r.group}:${r.artifact}`;

const SPECIAL_QUALIFIERS: Record<string, number> = { dev: -1, rc: 1, snapshot: 2, final: 3, ga: 4, release: 5, sp: 6 };
const isNumber = (part: string) => /^\d+$/.test(part);

/** Orders versions as Gradle does: numeric parts by value, numbers above words, and dev < others < rc < snapshot < final < ga < release < sp. */
export function compareVersions(a: string, b: string): number {
  const split = (v: string) => v.split(/[.\-_+]|(?<=\d)(?=\D)|(?<=\D)(?=\d)/).filter(Boolean);
  const pa = split(a);
  const pb = split(b);
  for (let i = 0; i < Math.min(pa.length, pb.length); i++) {
    const [x, y] = [pa[i], pb[i]];
    if (x === y) continue;
    if (isNumber(x) && isNumber(y)) {
      const diff = BigInt(x) - BigInt(y);
      if (diff !== 0n) return diff > 0n ? 1 : -1;
      continue;
    }
    if (isNumber(x) !== isNumber(y)) return isNumber(x) ? 1 : -1;
    const [sx, sy] = [SPECIAL_QUALIFIERS[x.toLowerCase()], SPECIAL_QUALIFIERS[y.toLowerCase()]];
    if (sx !== undefined || sy !== undefined) {
      const diff = (sx ?? 0) - (sy ?? 0);
      if (diff !== 0) return Math.sign(diff);
    }
    return x < y ? -1 : 1;
  }
  if (pa.length === pb.length) return 0;
  const longer = pa.length > pb.length ? pa : pb;
  const sign = isNumber(longer[Math.min(pa.length, pb.length)]) ? 1 : -1;
  return pa.length > pb.length ? sign : -sign;
}

const fileName = (r: { artifact: string; version: string }, ext = 'jar') => `${r.artifact}-${r.version}.${ext}`;
const hostOf = (repo: string) => new URL(repo).host;
const storeKey = (host: string, coord: string) => `${host}|${coord}`;

export function mavenUrl(repo: string, r: { group: string; artifact: string; version: string }, ext = 'jar'): string {
  return `${repo}/${r.group.replaceAll('.', '/')}/${r.artifact}/${r.version}/${fileName(r, ext)}`;
}

/** The path a JVM sees a vendor jar at once it has been written with `Jvm.writeFile`. It ends with the jar's own file name, which ReduxLib's version check reads. */
export function vendorJarPath(jar: VendorJar): string {
  return `/str/frcdevwasm-vendor-${encodeURIComponent(jar.key).replaceAll('%', '~')}~${jar.name}`;
}

const isZip = (data: Uint8Array) => data.length > 4 && data[0] === 0x50 && data[1] === 0x4b && data[2] === 3 && data[3] === 4;

// One per season, by the index URL
const vendorIndexes = new Map<string, Promise<VendorIndex>>();

export function loadVendorIndex(assets: RuntimeAssets): Promise<VendorIndex> {
  const url = assets.url('vendor/index.json').href;
  let index = vendorIndexes.get(url);
  if (!index) {
    index = fetch(url).then((res) => {
      if (!res.ok) throw new Error(`vendor library index missing (${res.status}); run pnpm fetch-deps --year ${assets.year}`);
      return res.json() as Promise<VendorIndex>;
    });
    vendorIndexes.set(url, index);
    index.catch(() => vendorIndexes.delete(url));
  }
  return index;
}

/** Changes whenever the transform could produce a different jar, so cached jars from an older one are not used. */
export async function loadTransformVersion(assets: RuntimeAssets): Promise<string> {
  const res = await fetch(assets.url('vendor/transform.json'));
  if (!res.ok) throw new Error(`vendor transform version missing (${res.status}); run pnpm build:java --year ${assets.year}`);
  return ((await res.json()) as { version: string }).version;
}

const cacheUrl = (key: string) => new URL(`/__vendor-jars__/${encodeURIComponent(key)}.jar`, location.origin).href;
const usedUrl = (key: string) => `${cacheUrl(key)}.used`;
const usedOf = (res: Response | undefined) => Number(res?.headers.get('x-frc-used')) || 0;

export function memoryJarStore(): JarStore {
  const entries = new Map<string, { data: Uint8Array; info: StoredInfo }>();
  return {
    info: async (key) => entries.get(key)?.info,
    read: async (key) => entries.get(key)?.data,
    write: async (key, data, info) => void entries.set(key, { data, info }),
    touch: async (key, used) => {
      const entry = entries.get(key);
      if (entry) entry.info = { ...entry.info, used };
    },
  };
}

export function cacheStorageStore(version: string): JarStore {
  const open = () => caches.open(VENDOR_CACHE_PREFIX + version);
  return {
    async info(key) {
      const cache = await open();
      const res = await cache.match(cacheUrl(key));
      if (!res) return undefined;
      const deps = (res.headers.get('x-frc-deps') ?? '').split(',').filter(Boolean);
      return { deps, used: Math.max(usedOf(res), usedOf(await cache.match(usedUrl(key)))) };
    },
    async read(key) {
      const res = await (await open()).match(cacheUrl(key));
      return res ? new Uint8Array(await res.arrayBuffer()) : undefined;
    },
    async write(key, data, info) {
      const headers = {
        'content-type': 'application/java-archive',
        'x-frc-deps': info.deps.join(','),
        'x-frc-used': String(info.used),
        'x-frc-bytes': String(data.byteLength),
      };
      await (await open()).put(cacheUrl(key), new Response(data as BufferSource, { headers }));
    },
    // A tiny entry of its own, so using a jar does not rewrite it
    async touch(key, used) {
      await (await open()).put(usedUrl(key), new Response(null, { headers: { 'x-frc-used': String(used) } }));
    },
  };
}

/** Cache Storage, with memory for the session where it is missing (insecure origins) or refuses a write (quota, private mode). */
export function resilientJarStore(primary: JarStore | undefined): JarStore {
  const memory = memoryJarStore();
  const attempt = async <T>(task: (store: JarStore) => Promise<T>): Promise<T | undefined> => {
    if (!primary) return undefined;
    try {
      return await task(primary);
    } catch {
      return undefined;
    }
  };
  return {
    info: async (key) => (await attempt((s) => s.info(key))) ?? memory.info(key),
    read: async (key) => (await attempt((s) => s.read(key))) ?? memory.read(key),
    async write(key, data, info) {
      try {
        if (!primary) throw new Error('no Cache Storage');
        await primary.write(key, data, info);
      } catch {
        await memory.write(key, data, info);
      }
    },
    async touch(key, used) {
      await attempt((s) => s.touch(key, used));
      await memory.touch(key, used);
    },
  };
}

/**
 * Deletes the caches of transform versions not in `currentVersions` (one per season) and the jars not used for
 * `maxAgeMs`. Returns what was removed.
 */
export async function pruneVendorCache(currentVersions: readonly string[], maxAgeMs: number): Promise<{ entries: number; bytes: number }> {
  const removed = { entries: 0, bytes: 0 };
  if (typeof caches === 'undefined') return removed;
  for (const name of await caches.keys()) {
    if (!name.startsWith(VENDOR_CACHE_PREFIX)) continue;
    const current = currentVersions.some((version) => name === VENDOR_CACHE_PREFIX + version);
    const cache = await caches.open(name);
    for (const request of await cache.keys()) {
      if (request.url.endsWith('.jar.used')) {
        if (current && (await cache.match(request.url.slice(0, -'.used'.length)))) continue;
        if (current) await cache.delete(request);
        continue;
      }
      const res = await cache.match(request);
      const used = Math.max(usedOf(res), usedOf(await cache.match(`${request.url}.used`)));
      if (current && Date.now() - used < maxAgeMs) continue;
      removed.entries++;
      removed.bytes += Number(res?.headers.get('x-frc-bytes')) || 0;
      if (current) {
        await cache.delete(request);
        await cache.delete(`${request.url}.used`);
      }
    }
    if (!current) await caches.delete(name);
  }
  return removed;
}

/** A jar on the user's disk, read only when it is wanted. */
export interface DiskJar {
  name: string;
  read(): Promise<Uint8Array>;
}

export class VendorLibraries {
  private readonly jobs = new Map<string, Promise<Obtained>>();
  private readonly proxiedHosts = new Set<string>();
  private store?: Promise<JarStore>;
  private running = 0;
  private readonly waiting: Array<() => void> = [];
  private readonly options: VendorLibrariesOptions;

  constructor(options: VendorLibrariesOptions) {
    this.options = options;
  }

  private getStore(): Promise<JarStore> {
    this.store ??= this.options.store
      ? Promise.resolve(this.options.store)
      : loadTransformVersion(this.options.assets).then((version) =>
          resilientJarStore(typeof caches === 'undefined' ? undefined : cacheStorageStore(version)),
        );
    this.store.catch(() => (this.store = undefined));
    return this.store;
  }

  /**
   * The transformed jars for the requirements and the jars they depend on, downloading those that
   * are not cached. When two versions of one library are wanted, the highest wins, as in Gradle, except
   * that the first vendordep requirement of a library (not `plain`) keeps its version. Returns each
   * requirement followed by its dependencies. Throws a VendorDownloadError when a required jar cannot
   * be downloaded. A dependency that cannot be downloaded is a warning.
   */
  async resolve(requirements: VendorRequirement[], hooks: ResolveHooks = {}): Promise<VendorJar[]> {
    for (const req of requirements) assertSafeCoordinate(req, 'vendordep');
    const store = await this.getStore();
    // null when the jar could not be downloaded
    const graph = new Map<string, { jar: VendorJar; deps: VendorRequirement[] } | null>();
    const progress = { done: 0, total: 0 };
    // Downloads run in parallel, so each report names its own jar
    const report = (jar: string, downloaded: boolean) => hooks.onProgress?.({ ...progress, jar, downloaded });
    const roots = requirements.filter((req) => !this.skipped(req));
    const required = new Set(roots.map(libraryOf));
    const pinned = new Map<string, string>();
    for (const req of roots) if (!req.plain && !pinned.has(libraryOf(req))) pinned.set(libraryOf(req), req.version);

    const visit = async (req: VendorRequirement): Promise<void> => {
      progress.total++;
      report(fileName(req), false);
      try {
        const obtained = await this.obtain(req, store);
        progress.done++;
        report(fileName(req), obtained.downloaded);
        obtained.warnings.forEach((w) => hooks.onWarning?.(w));
        const { key } = obtained;
        const deps = obtained.deps.flatMap((dep) => {
          const parsed = parseCoordinate(dep);
          return parsed && !this.skipped(parsed) ? [{ ...parsed, repos: req.repos, plain: req.plain }] : [];
        });
        graph.set(coordinate(req), { jar: { key, name: fileName(req), data: () => this.read(store, key) }, deps });
      } catch (error) {
        if (required.has(libraryOf(req))) throw error;
        hooks.onWarning?.(`${error instanceof Error ? error.message : String(error)}. Its classes are missing from the classpath.`);
        graph.set(coordinate(req), null);
      }
    };

    // The versions that the jars reachable with the previous choice ask for, until the choice stops changing
    const choose = (): Map<string, VendorRequirement> => {
      let chosen = new Map<string, VendorRequirement>();
      for (let pass = 0; pass < 50; pass++) {
        const next = new Map<string, VendorRequirement>();
        const offer = (req: VendorRequirement) => {
          const library = libraryOf(req);
          const pin = pinned.get(library);
          if (pin !== undefined && pin !== req.version) return;
          const current = next.get(library);
          if (!current || compareVersions(req.version, current.version) > 0) next.set(library, req);
        };
        const walked = new Set<string>();
        const walk = (req: VendorRequirement) => {
          const coord = coordinate(chosen.get(libraryOf(req)) ?? req);
          if (walked.has(coord)) return;
          walked.add(coord);
          for (const dep of graph.get(coord)?.deps ?? []) {
            offer(dep);
            walk(dep);
          }
        };
        roots.forEach(offer);
        roots.forEach(walk);
        const same = next.size === chosen.size && [...next].every(([library, req]) => chosen.get(library) === req);
        chosen = next;
        if (same) break;
      }
      return chosen;
    };

    // One level at a time, so versions are chosen before anything farther is downloaded
    let chosen = choose();
    for (;;) {
      const missing = [...chosen.values()].filter((req) => !graph.has(coordinate(req)));
      if (!missing.length) break;
      await Promise.all(missing.map(visit));
      chosen = choose();
    }

    // Depth first from the requirements, so the classpath does not depend on which download finished first
    const ordered: VendorJar[] = [];
    const emitted = new Set<string>();
    const emit = (req: VendorRequirement) => {
      const coord = coordinate(chosen.get(libraryOf(req)) ?? req);
      const node = graph.get(coord);
      if (!node || emitted.has(coord)) return;
      emitted.add(coord);
      ordered.push(node.jar);
      node.deps.forEach(emit);
    };
    roots.forEach(emit);
    return ordered;
  }

  /** The versions that a BOM manages, by `group:artifact`, as a platform of a build.gradle gives them. */
  async managedVersions(bom: VendorRequirement): Promise<Map<string, string>> {
    const loaded = new Map<string, Map<string, string>>();
    const read = async (req: VendorRequirement, chain: string[]): Promise<Map<string, string>> => {
      assertSafeCoordinate(req, 'BOM');
      const id = coordinate(req);
      if (chain.includes(id)) throw new Error(`BOM import cycle: ${[...chain, id].join(' -> ')}`);
      const cached = loaded.get(id);
      if (cached) return cached;
      const { data, repo } = await this.download(req, 'pom', req.repos);
      const repos = [repo, ...req.repos.filter((r) => r !== repo)];
      const pom = new TextDecoder().decode(data);
      const parents: string[] = [];
      for (let child = pom; parents.length < MAX_PARENTS; ) {
        const parent = pomParent(child);
        if (!parent || !isSafeCoordinate(parent)) break;
        const found = await this.download({ ...parent, repos }, 'pom', repos).catch(() => undefined);
        if (!found) break;
        child = new TextDecoder().decode(found.data);
        parents.push(child);
      }
      const managed = pomManagedVersions(pom, parents);
      for (const imported of pomImports(pom, parents)) {
        const versions = await read({ ...imported, repos }, [...chain, id]);
        // Direct entries win over imports; between imports the first BOM wins, as in Maven.
        for (const [name, version] of versions) if (!managed.has(name)) managed.set(name, version);
      }
      loaded.set(id, managed);
      return managed;
    };
    return read(bom, []);
  }

  /** Transforms and caches the wanted jars among `disk` that are not cached yet, matching `artifact-version.jar` by file name. */
  async importJars(requirements: VendorRequirement[], disk: DiskJar[]): Promise<{ imported: string[]; missing: string[] }> {
    for (const req of requirements) assertSafeCoordinate(req, 'vendordep');
    const store = await this.getStore();
    const index = await loadVendorIndex(this.options.assets);
    const wanted = new Map<string, VendorRequirement>();
    const add = async (req: VendorRequirement) => {
      const coord = coordinate(req);
      if (wanted.has(coord) || this.skipped(req)) return;
      wanted.set(coord, req);
      const deps = (await this.findCached(req, store))?.info.deps ?? index.transitive[coord] ?? [];
      for (const dep of deps) {
        const parsed = parseCoordinate(dep);
        if (parsed) await add({ ...parsed, repos: req.repos, plain: req.plain });
      }
    };
    for (const req of requirements) await add(req);

    const byName = new Map(disk.map((jar) => [jar.name, jar]));
    const imported: string[] = [];
    const missing: string[] = [];
    for (const [coord, req] of wanted) {
      if (await this.findCached(req, store)) continue;
      const jar = byName.get(fileName(req));
      if (!jar) {
        missing.push(fileName(req));
        continue;
      }
      const bytes = await jar.read();
      if (!isZip(bytes)) throw new Error(`${jar.name} is not a jar file`);
      const data = req.plain && !mayNeedTransform(bytes) ? bytes : await this.options.transform(bytes, jar.name);
      await store.write(storeKey(DISK_HOST, coord), data, { deps: index.transitive[coord] ?? [], used: Date.now() });
      imported.push(jar.name);
    }
    return { imported, missing };
  }

  // Already on the robot classpath, so a copy from a vendor would clash.
  private skipped(req: { group: string; artifact: string }): boolean {
    return this.options.assets.provided.has(`${req.group}:${req.artifact}`);
  }

  private async read(store: JarStore, key: string): Promise<Uint8Array> {
    const data = await store.read(key);
    if (!data) throw new Error(`${key} is no longer in the vendor jar cache. Build again to download it.`);
    return data;
  }

  // Only repositories of the project itself are consulted, so another project's vendordep cannot
  // decide what a coordinate means here. Jars the user imported are available to every project.
  private async findCached(req: VendorRequirement, store: JarStore): Promise<{ key: string; info: StoredInfo } | undefined> {
    const coord = coordinate(req);
    for (const host of [...new Set(req.repos.map(hostOf)), DISK_HOST]) {
      const key = storeKey(host, coord);
      const info = await store.info(key);
      if (info) return { key, info };
    }
    return undefined;
  }

  // The jar's cache key and dependencies, from the cache or after downloading it. Concurrent callers
  // share one job, and a finished job is forgotten so the cache is checked again next time.
  private obtain(req: VendorRequirement, store: JarStore): Promise<Obtained> {
    const coord = coordinate(req);
    const running = this.jobs.get(coord);
    // Only the caller that started the download reports it
    if (running) return running.then((obtained) => ({ ...obtained, downloaded: false }));
    const job = this.cachedOrInstall(req, store, coord);
    this.jobs.set(coord, job);
    const forget = () => void this.jobs.delete(coord);
    job.then(forget, forget);
    return job;
  }

  private async cachedOrInstall(req: VendorRequirement, store: JarStore, coord: string): Promise<Obtained> {
    const cached = await this.findCached(req, store);
    if (cached) {
      // Used jars stay; the ones nobody uses get pruned
      if (Date.now() - cached.info.used > DAY_MS) await store.touch(cached.key, Date.now()).catch(() => undefined);
      return { key: cached.key, deps: cached.info.deps, downloaded: false, warnings: [] };
    }
    await this.acquire();
    try {
      const { data: jar, repo } = await this.download(req, 'jar', req.repos);
      const { deps, warnings } = await this.dependenciesOf(req, coord, repo);
      const data = req.plain && !mayNeedTransform(jar) ? jar : await this.options.transform(jar, fileName(req));
      const key = storeKey(hostOf(repo), coord);
      await store.write(key, data, { deps, used: Date.now() });
      return { key, deps, downloaded: true, warnings };
    } finally {
      this.release();
    }
  }

  private async acquire(): Promise<void> {
    if (this.running >= CONCURRENCY) await new Promise<void>((resolve) => this.waiting.push(resolve));
    this.running++;
  }

  private release(): void {
    this.running--;
    this.waiting.shift()?.();
  }

  // From the index, or the jar's pom. Throws when the pom could not be read, because a jar cached
  // without its dependencies would stay broken until pruned. A jar with no pom has none.
  private async dependenciesOf(req: VendorRequirement, coord: string, repo: string): Promise<{ deps: string[]; warnings: string[] }> {
    const known = (await loadVendorIndex(this.options.assets)).transitive[coord];
    if (known) return { deps: known, warnings: [] };
    const found = await this.download(req, 'pom', [repo, ...req.repos.filter((r) => r !== repo)]).catch((error: unknown) => {
      if (error instanceof PomNotFound) return undefined;
      throw error;
    });
    const pom = found ? new TextDecoder().decode(found.data) : '';
    if (!pom.includes('<project')) return { deps: [], warnings: [] };
    const wpilibGroup = WPILIB_GROUP[this.options.assets.year];
    const deps: string[] = [];
    const warnings: string[] = [];
    for (const d of await this.pomDependenciesWithParents(req, pom, repo)) {
      if ((wpilibGroup && d.group.startsWith(wpilibGroup)) || this.skipped(d)) continue;
      const name = `${d.group}:${d.artifact}`;
      if (!isSafeCoordinate({ ...d, version: '0' })) {
        warnings.push(`${name} needed by ${fileName(req)} is not a valid maven coordinate, so it is not downloaded`);
      } else if (!d.version || !SAFE_NAME.test(d.version)) {
        warnings.push(`${name} needed by ${fileName(req)} has no fixed version (${d.version || 'inherited from a parent pom'}), so it is not downloaded`);
      } else {
        deps.push(coordinate(d));
      }
    }
    return { deps, warnings };
  }

  // A dependency with no version, or one that is a property, takes it from a parent pom: read the parents then.
  private async pomDependenciesWithParents(req: VendorRequirement, pom: string, repo: string): Promise<ReturnType<typeof pomDependencies>> {
    const incomplete = (deps: ReturnType<typeof pomDependencies>) => deps.some((d) => !d.version || !SAFE_NAME.test(d.version));
    const repos = [repo, ...req.repos.filter((r) => r !== repo)];
    let deps = pomDependencies(pom);
    const parents: string[] = [];
    for (let child = pom; incomplete(deps) && parents.length < MAX_PARENTS; ) {
      const parent = pomParent(child);
      if (!parent || !isSafeCoordinate(parent)) break;
      const found = await this.download({ ...parent, repos }, 'pom', repos).catch(() => undefined);
      if (!found) break;
      child = new TextDecoder().decode(found.data);
      parents.push(child);
      deps = pomDependencies(pom, parents);
    }
    return deps;
  }

  /** The first repository that has the file. Throws PomNotFound when none has a pom and none failed otherwise. */
  private async download(
    req: VendorRequirement,
    ext: 'jar' | 'pom',
    repos: string[],
  ): Promise<{ data: Uint8Array; repo: string }> {
    const failures: string[] = [];
    let first = '';
    let onlyMissing = true;
    for (const repo of repos) {
      const url = mavenUrl(repo, req, ext);
      first ||= url;
      try {
        const data = await this.get(url);
        if (!data) {
          failures.push(`not found at ${repo}`);
        } else if (ext === 'jar' && !isZip(data)) {
          failures.push(`${repo} did not answer with a jar file`);
          onlyMissing = false;
        } else {
          return { data, repo };
        }
      } catch (error) {
        failures.push(error instanceof Error ? error.message : String(error));
        onlyMissing = false;
      }
    }
    if (ext === 'pom' && onlyMissing) throw new PomNotFound();
    throw new VendorDownloadError(fileName(req, ext), first, failures.join('; ') || 'no repository to try');
  }

  /** The body of a successful request, undefined for 404. Falls back to the proxy when the browser blocks the direct request. */
  private async get(url: string): Promise<Uint8Array | undefined> {
    const fetcher = this.options.fetch ?? fetch;
    const host = new URL(url).host;
    const proxy = this.options.proxy?.()?.trim().replace(/\/$/, '');
    if (!this.proxiedHosts.has(host)) {
      try {
        const res = await fetcher(url);
        if (res.ok) return new Uint8Array(await res.arrayBuffer());
        if (res.status === 404) return undefined;
        throw new Error(`HTTP ${res.status}`);
      } catch (error) {
        // A network error is how the browser reports a missing CORS header
        if (!(error instanceof TypeError)) throw error;
        if (proxy) this.proxiedHosts.add(host);
      }
    }
    if (!proxy) throw new Error('the browser could not reach it (offline, or the server does not allow requests from web pages), and no vendor proxy is set');
    if (!url.startsWith('https://')) throw new Error('only https repositories can be reached through the vendor proxy');
    try {
      const res = await fetcher(`${proxy}/maven/${url.slice('https://'.length)}`);
      if (res.ok) return new Uint8Array(await res.arrayBuffer());
      if (res.status === 404) return undefined;
      throw new Error(`the vendor proxy answered HTTP ${res.status}`);
    } catch (error) {
      if (error instanceof TypeError) throw new Error(`neither the server nor the vendor proxy ${proxy} could be reached. Check the network connection`);
      throw error;
    }
  }
}

class PomNotFound extends Error {}
