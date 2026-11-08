// Reads the dependencies and repositories of a build.gradle. Gradle does not run here, so this
// understands the usual ways to write them and turns anything else into a warning.
// Also run by test/gradle.mjs under Node, so no imports of the browser and no parameter properties.
import { CENTRAL, isSafeCoordinate } from './vendor.ts';

export interface GradleDependency {
  /** The Gradle configuration, such as `implementation` or `annotationProcessor`. */
  configuration: string;
  group: string;
  artifact: string;
  /** Empty in a test configuration that leaves it to a platform or to what the other libraries bring. */
  version: string;
  /** `platform('group:artifact:version')`: a BOM that supplies versions, and is not on a classpath. Only in test configurations. */
  platform?: boolean;
}

/** What the `test` task of a build.gradle sets that the browser honors. */
export interface GradleTest {
  /** What the task runs tests with: `useJUnitPlatform()`, `useTestNG()`, or JUnit 4 when it says nothing. */
  framework: 'junit' | 'junit-platform' | 'testng';
  systemProperties: Record<string, string>;
  /** The settings of the task that are not applied, which `./gradlew test` warns about. */
  warnings: string[];
}

export interface GradleBuild {
  dependencies: GradleDependency[];
  test: GradleTest;
  /** https repositories in declaration order, without a trailing slash. */
  repositories: string[];
  warnings: string[];
}

const CONFIGURATIONS = new Set([
  'implementation',
  'api',
  'compileOnly',
  'runtimeOnly',
  'annotationProcessor',
  'testImplementation',
  'testCompileOnly',
  'testRuntimeOnly',
  'testAnnotationProcessor',
]);
// Dependency notations that need more than a coordinate.
const UNSUPPORTED = new Set(['project', 'files', 'fileTree', 'platform', 'enforcedPlatform', 'gradleApi', 'localGroovy', 'kotlin']);
const PROJECT_FILE = /["']\/?([\w./-]+\.(?:json|properties|txt))["']/g;
const MODIFIERS = '(?:(?:def|final|var|static|int|long|double|boolean|[A-Z]\\w*(?:<[^>]*>)?)\\s+)*';
const ASSIGNMENT = new RegExp(`^${MODIFIERS}([\\w.]+)\\s*=(?!=)\\s*([\\s\\S]+)$`);
const JITPACK_URL = 'https://jitpack.io';

// Comment markers inside string literals, such as the glob '**/*.java', are not comments.
export function stripComments(gradle: string): string {
  let out = '';
  for (let i = 0; i < gradle.length; ) {
    const c = gradle[i];
    if (c === '"' || c === "'") {
      const triple = gradle.startsWith(c.repeat(3), i);
      const quote = triple ? c.repeat(3) : c;
      let end = i + quote.length;
      while (end < gradle.length && !gradle.startsWith(quote, end)) end += gradle[end] === '\\' ? 2 : 1;
      end = Math.min(end + quote.length, gradle.length);
      out += gradle.slice(i, end);
      i = end;
    } else if (gradle.startsWith('//', i)) {
      while (i < gradle.length && gradle[i] !== '\n') i++;
    } else if (gradle.startsWith('/*', i)) {
      const end = gradle.indexOf('*/', i + 2);
      i = end < 0 ? gradle.length : end + 2;
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/** Files in the project folder that the script reads, such as `vendordeps/AdvantageKit.json`. Paths use `/` and are relative to the project root. */
export function referencedFiles(gradle: string): string[] {
  return [...new Set([...gradle.matchAll(PROJECT_FILE)].map((m) => m[1]))];
}

/** `key=value` lines of a gradle.properties file. */
export function parseProperties(text: string): Map<string, string> {
  const props = new Map<string, string>();
  for (const line of text.split(/\r?\n/)) {
    const m = /^\s*([^\s#!=:]+)\s*[=:]\s*(.*?)\s*$/.exec(line);
    if (m) props.set(m[1], m[2]);
  }
  return props;
}

/**
 * The libraries and repositories that `gradle` (with comments removed) declares. `files` holds the
 * text of the project files the script reads, by path relative to the project root.
 */
export function parseGradleBuild(gradle: string, files: ReadonlyMap<string, string>): GradleBuild {
  const build: GradleBuild = { dependencies: [], test: { framework: 'junit', systemProperties: {}, warnings: [] }, repositories: [], warnings: [] };
  const all = statements(gradle);
  const evaluator = new Evaluator(files, parseProperties(files.get('gradle.properties') ?? ''));

  for (const { text } of all) {
    const m = ASSIGNMENT.exec(text);
    if (!m) continue;
    const name = m[1].replace(/^(?:(?:project|rootProject)\.)?ext\./, '');
    if (!name.includes('.')) evaluator.define(name, m[2]);
  }

  const warn = (text: string, why: string) => build.warnings.push(`build.gradle: ${why}: ${oneLine(text)}`);
  const repos: RepositoryEntry[] = [];
  for (const { text, blocks, opens } of all) {
    const top = blocks.at(-1);
    if (blocks.includes('buildscript') || blocks.includes('pluginManagement') || blocks.includes('publishing')) continue;
    if (top === 'dependencies') {
      if (!ASSIGNMENT.test(text)) dependency(text, evaluator, build, warn);
    } else if (TEST_TASK.test(blocks[0] ?? '')) {
      // A nested block is named by its header, except the options of useJUnitPlatform { }
      const unread = blocks.length === 1 ? !testSetting(text, evaluator, build.test) : blocks.length === 2 && /^useJUnit(?:Platform|Jupiter)$/.test(top!);
      if (unread) build.test.warnings.push(`build.gradle: test setting not applied: ${oneLine(text)}`);
    } else if (top === 'repositories') {
      if (text === 'maven' && opens) repos.push({ text });
      else repos.push(repository(text, evaluator));
    } else if (top === 'maven' && blocks.at(-2) === 'repositories') {
      const url = /^url\b\s*=?\s*([\s\S]+)$/.exec(text)?.[1];
      if (url === undefined) continue;
      const value = evaluator.evaluate(url);
      repos.at(-1)![typeof value === 'string' ? 'url' : 'why'] = typeof value === 'string' ? value : `cannot read repository${evaluator.reason()}`;
    }
  }
  for (const repo of repos) {
    if (repo.url) addRepository(repo.url, build, (why) => warn(repo.text, why));
    else if (repo.why !== '') warn(repo.text, repo.why ?? 'maven repository without a url');
  }
  return build;
}

/**
 * The `exclude` patterns of the main Java source set (`sourceSets.main.java { exclude '...' }` and the nested
 * forms), relative to src/main/java, and warnings for its other settings, which are not applied.
 */
export function sourceExcludes(gradle: string): { patterns: string[]; warnings: string[] } {
  const patterns: string[] = [];
  const warnings: string[] = [];
  for (const { text, blocks, opens } of statements(gradle)) {
    const path = blocks.join('.');
    const oneLiner = blocks.length === 0 && /^sourceSets\.main\.java\.exclude\b/.test(text);
    if (path === 'sourceSets.main.java' || oneLiner) {
      const m = /^(?:sourceSets\.main\.java\.)?exclude\b\s*\(?\s*([\s\S]*?)\s*\)?$/.exec(text);
      const values = m ? [...m[1].matchAll(/["']([^"']+)["']/g)].map((v) => v[1]) : [];
      if (values.length) patterns.push(...values);
      else warnings.push(`build.gradle: source set setting not applied: ${oneLine(text)}`);
    } else if (!opens && (path === 'sourceSets' || path === 'sourceSets.main' || path.startsWith('sourceSets.main.') || (blocks.length === 0 && /^sourceSets\.main\b/.test(text)))) {
      warnings.push(`build.gradle: source set setting not applied: ${oneLine(text)}`);
    }
  }
  return { patterns, warnings };
}

// Gradle's Ant-style patterns: ** for any directories, * and ? within a name, and a trailing / for everything below
export function matchesPattern(path: string, pattern: string): boolean {
  const glob = pattern.endsWith('/') ? `${pattern}**` : pattern;
  let re = '';
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === '*' && glob[i + 1] === '*') {
      re += glob[i + 2] === '/' ? '(?:.*/)?' : '.*';
      i += glob[i + 2] === '/' ? 2 : 1;
    } else if (c === '*') re += '[^/]*';
    else if (c === '?') re += '[^/]';
    else re += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${re}$`).test(path);
}

// test { }, tasks.test { }, tasks.named('test') { }, tasks.withType(Test) { }
const TEST_TASK = /^(?:tasks\.)?test$|^tasks\.(?:named|getByName)\(\s*["']test["']\s*\)$|^tasks\.withType\(\s*Test(?:\.class)?\s*\)(?:\.configureEach)?$/;

/** Applies a statement of the test task, and returns whether it did. */
function testSetting(text: string, evaluator: Evaluator, test: GradleTest): boolean {
  if (/^useJUnitPlatform\b|^useJUnitJupiter\b/.test(text)) test.framework = 'junit-platform';
  else if (/^useTestNG\b/.test(text)) test.framework = 'testng';
  else if (/^useJUnit\b/.test(text)) test.framework = 'junit';
  else {
    const property = /^systemProperty\b\s*\(?([\s\S]+?)\)?$/.exec(text)?.[1];
    if (property === undefined) return false;
    const [key, value] = splitTop(property, ',').map((part) => evaluator.evaluate(part));
    if (typeof key !== 'string' || value === undefined || typeof value === 'object') return false;
    test.systemProperties[key] = String(value);
  }
  return true;
}

function oneLine(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

function dependency(text: string, evaluator: Evaluator, build: GradleBuild, warn: (text: string, why: string) => void): void {
  const m = /^([A-Za-z_]\w*)\s*([\s\S]*)$/.exec(text);
  if (!m) return;
  const [, configuration] = m;
  let args = m[2].trim();
  if (args.startsWith('(') && matching(args, 0) === args.length - 1) args = args.slice(1, -1).trim();
  if (!args) return;
  // wpi.java.deps.* and wpi.java.vendor.* are what the WPILib and vendordep handling already provides
  if (/^wpi\./.test(args)) return;
  if (!CONFIGURATIONS.has(configuration)) {
    if (/^[`'"]|^(?:group|name)\s*:/.test(args)) warn(text, `unknown configuration ${configuration}`);
    return;
  }
  const head = /^([A-Za-z_]\w*)\s*\(/.exec(args)?.[1];
  const isTest = configuration.startsWith('test');
  if (isTest && (head === 'platform' || head === 'enforcedPlatform')) {
    const inner = args.slice(args.indexOf('(') + 1, matching(args, args.indexOf('(')));
    const value = evaluator.evaluate(inner);
    const [group, artifact, version] = typeof value === 'string' ? value.split(':') : [];
    if (group && artifact && version && isSafeCoordinate({ group, artifact, version })) build.dependencies.push({ configuration, group, artifact, version, platform: true });
    else warn(text, `cannot read the platform${evaluator.reason()}`);
    return;
  }
  if (head && UNSUPPORTED.has(head)) return warn(text, `${head}(...) is not supported`);

  const add = (coordinate: string[] | undefined) => {
    if (!coordinate) return;
    const [group, artifact, version] = coordinate;
    if (!version && !isTest) return warn(text, 'no version');
    if (version && /[+,\[\]()]|^latest\./.test(version)) return warn(text, `dynamic version ${version} is not supported`);
    if (!isSafeCoordinate({ group, artifact, version: version || '0' })) return warn(text, 'not a valid maven coordinate');
    build.dependencies.push({ configuration, group, artifact, version: version ?? '' });
  };
  const parts = splitTop(args, ',');
  if (/^\w+\s*:(?!:)/.test(parts[0])) {
    const named = new Map<string, unknown>();
    for (const part of parts) {
      const kv = /^(\w+)\s*:\s*([\s\S]+)$/.exec(part);
      if (kv) named.set(kv[1], evaluator.evaluate(kv[2]));
    }
    const { group, name, version } = Object.fromEntries(named);
    if (typeof group !== 'string' || typeof name !== 'string') return warn(text, `cannot read dependency${evaluator.reason()}`);
    if (named.get('classifier') || named.get('ext')) return warn(text, 'classifiers are not supported');
    add([group, name, version === undefined ? '' : String(version)]);
    return;
  }
  for (const part of parts) {
    const value = evaluator.evaluate(part);
    if (typeof value !== 'string') return warn(text, `cannot read dependency${evaluator.reason()}`);
    const coordinate = value.split(':');
    if (coordinate.length > 3) return warn(text, 'classifiers are not supported');
    if (coordinate[2]?.includes('@')) {
      const [version, ext] = coordinate[2].split('@');
      if (ext !== 'jar') return warn(text, `@${ext} artifacts are not supported`);
      coordinate[2] = version;
    }
    add(coordinate);
  }
}

interface RepositoryEntry {
  text: string;
  url?: string;
  /** Why it is not used. Empty when that is not worth a warning. */
  why?: string;
}

function repository(text: string, evaluator: Evaluator): RepositoryEntry {
  if (text === 'mavenCentral()') return { text, url: CENTRAL };
  if (text === 'mavenLocal()') return { text, why: '' };
  if (text === 'google()') return { text, url: 'https://dl.google.com/dl/android/maven2' };
  if (text === 'jcenter()') return { text, why: 'jcenter is no longer available, so it is ignored' };
  const m = /^maven(?:\s*\(|\s+)([\s\S]*?)\)?$/.exec(text);
  if (m) {
    // maven("url"), maven(url = "url"), maven url: "url"
    const url = evaluator.evaluate(/^(?:url\s*[:=]\s*)?([\s\S]+)$/.exec(m[1])?.[1] ?? '');
    return typeof url === 'string' ? { text, url } : { text, why: `cannot read repository${evaluator.reason()}` };
  }
  return { text, why: 'this repository notation is not supported' };
}

function addRepository(url: string, build: GradleBuild, fail: (why: string) => void): void {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return fail(`${url} is not a repository address`);
  }
  if (parsed.protocol !== 'https:') return fail(`${url} is ignored because only https repositories can be used from the browser`);
  const normalized = (parsed.host === 'www.jitpack.io' ? JITPACK_URL : parsed.href).replace(/\/$/, '');
  if (!build.repositories.includes(normalized)) build.repositories.push(normalized);
}

interface Statement {
  text: string;
  /** The headers of the enclosing blocks, outermost first. */
  blocks: string[];
  /** The statement is the header of a block that follows. */
  opens?: boolean;
}

/** End of the string literal that starts at `start`. Handles triple quotes, escapes and `${...}`. */
function stringEnd(src: string, start: number): number {
  const quote = src[start];
  const close = src.startsWith(quote.repeat(3), start) ? quote.repeat(3) : quote;
  let i = start + close.length;
  while (i < src.length && !src.startsWith(close, i)) {
    if (src[i] === '\\') i += 2;
    else if (quote === '"' && src[i] === '$' && src[i + 1] === '{') i = (matching(src, i + 1) ?? src.length) + 1;
    else i++;
  }
  return Math.min(i + close.length, src.length);
}

/** Index of the bracket that closes the one at `open`, skipping strings. */
function matching(src: string, open: number): number | undefined {
  const pairs: Record<string, string> = { '(': ')', '[': ']', '{': '}' };
  const stack: string[] = [];
  for (let i = open; i < src.length; ) {
    const c = src[i];
    if (c === '"' || c === "'") {
      i = stringEnd(src, i);
      continue;
    }
    if (pairs[c]) stack.push(pairs[c]);
    else if (stack.length && c === stack.at(-1)) {
      stack.pop();
      if (!stack.length) return i;
    }
    i++;
  }
  return undefined;
}

/** Splits at `separator` outside strings and brackets. */
function splitTop(src: string, separator: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let from = 0;
  for (let i = 0; i < src.length; ) {
    const c = src[i];
    if (c === '"' || c === "'") {
      i = stringEnd(src, i);
      continue;
    }
    if ('([{'.includes(c)) depth++;
    else if (')]}'.includes(c)) depth--;
    else if (c === separator && depth === 0) {
      parts.push(src.slice(from, i).trim());
      from = i + 1;
    }
    i++;
  }
  parts.push(src.slice(from).trim());
  return parts;
}

/** The statements of a script at every nesting level, with the blocks around each. */
function statements(src: string): Statement[] {
  const out: Statement[] = [];
  const blocks: string[] = [];
  let current = '';
  let depth = 0;
  const flush = () => {
    const text = current.trim();
    if (text) out.push({ text, blocks: [...blocks] });
    current = '';
  };
  for (let i = 0; i < src.length; ) {
    const c = src[i];
    if (c === '"' || c === "'") {
      const end = stringEnd(src, i);
      current += src.slice(i, end);
      i = end;
      continue;
    }
    i++;
    if (c === '(' || c === '[') {
      depth++;
    } else if (c === ')' || c === ']') {
      depth--;
    } else if (c === '{' && depth > 0) {
      depth++;
    } else if (c === '}' && depth > 0) {
      depth--;
    } else if (c === '{') {
      const header = current.trim();
      if (header) out.push({ text: header, blocks: [...blocks], opens: true });
      blocks.push(header);
      current = '';
      continue;
    } else if (c === '}') {
      flush();
      blocks.pop();
      continue;
    } else if (depth === 0 && c === ';') {
      flush();
      continue;
    } else if (depth === 0 && c === '\n') {
      const next = /^\s*(\S)/.exec(src.slice(i))?.[1];
      if (!/[,+\\=.&|]$/.test(current.trimEnd()) && next !== '.') flush();
      else current += ' ';
      continue;
    }
    current += c;
  }
  flush();
  return out;
}

class FileRef {
  readonly path: string;

  constructor(path: string) {
    this.path = path.replace(/^\/+/, '').replace(/\/+$/, '');
  }
}

// `project`, `rootProject`, `ext`: members are the script's variables.
const SCOPE = Symbol('scope');
type Value = string | number | boolean | null | FileRef | typeof SCOPE | Value[] | { [key: string]: Value };

/** Evaluates the small part of Groovy that dependency coordinates are built from. */
class Evaluator {
  private readonly vars = new Map<string, Value>([
    ['projectDir', new FileRef('')],
    ['rootDir', new FileRef('')],
    ['project', SCOPE],
    ['rootProject', SCOPE],
    ['ext', SCOPE],
    ['this', SCOPE],
  ]);
  private readonly files: ReadonlyMap<string, string>;
  private readonly props: ReadonlyMap<string, string>;
  private why = '';
  private src = '';
  private pos = 0;

  constructor(files: ReadonlyMap<string, string>, props: ReadonlyMap<string, string>) {
    this.files = files;
    this.props = props;
  }

  define(name: string, expr: string): void {
    const value = this.evaluate(expr);
    if (value !== undefined) this.vars.set(name, value);
  }

  /** Why the last evaluation failed, for a warning. */
  reason(): string {
    return this.why ? ` (${this.why})` : '';
  }

  evaluate(expr: string): Value | undefined {
    this.why = '';
    return this.run(expr);
  }

  // Nested evaluations, such as `${...}` in a string, keep the first reason.
  private run(expr: string): Value | undefined {
    const saved = [this.src, this.pos] as const;
    this.src = expr.trim();
    this.pos = 0;
    let value: Value | undefined;
    try {
      value = this.sum();
      this.skip();
      if (this.pos < this.src.length) value = this.fail(`cannot read ${this.src.slice(this.pos)}`);
    } finally {
      [this.src, this.pos] = saved;
    }
    return value === SCOPE ? undefined : value;
  }

  private fail(why: string): undefined {
    this.why ||= why;
    return undefined;
  }

  private skip(): void {
    while (this.pos < this.src.length && /\s/.test(this.src[this.pos])) this.pos++;
  }

  private eat(token: string): boolean {
    this.skip();
    if (!this.src.startsWith(token, this.pos)) return false;
    this.pos += token.length;
    return true;
  }

  private sum(): Value | undefined {
    let left = this.postfix();
    while (this.eat('+')) {
      const right = this.postfix();
      if (left === undefined || right === undefined) left = undefined;
      else if (typeof left === 'number' && typeof right === 'number') left = left + right;
      else left = text(left) + text(right);
    }
    return left;
  }

  private postfix(): Value | undefined {
    let value = this.primary();
    for (;;) {
      if (this.eat('?.') || this.eat('.')) {
        const name = this.identifier();
        if (!name) return this.fail('cannot read member name');
        this.skip();
        value = this.src[this.pos] === '(' ? this.call(value, name, this.args()) : this.member(value, name);
      } else if (this.eat('[')) {
        const index = this.sum();
        if (!this.eat(']')) return this.fail('missing ]');
        value = typeof index === 'string' || typeof index === 'number' ? this.member(value, String(index)) : undefined;
      } else {
        return value;
      }
    }
  }

  private identifier(): string {
    this.skip();
    const m = /^[A-Za-z_$][\w$]*/.exec(this.src.slice(this.pos));
    this.pos += m ? m[0].length : 0;
    return m ? m[0] : '';
  }

  private args(): Array<Value | undefined> {
    const list: Array<Value | undefined> = [];
    this.eat('(');
    if (this.eat(')')) return list;
    do list.push(this.sum());
    while (this.eat(','));
    if (!this.eat(')')) this.fail('missing )');
    return list;
  }

  private primary(): Value | undefined {
    this.skip();
    const c = this.src[this.pos];
    if (c === '"' || c === "'") {
      const end = stringEnd(this.src, this.pos);
      const raw = this.src.slice(this.pos, end);
      this.pos = end;
      const triple = raw.startsWith(c.repeat(3)) && raw.length >= 6;
      const body = raw.slice(triple ? 3 : 1, raw.endsWith(triple ? c.repeat(3) : c) ? -(triple ? 3 : 1) : undefined);
      return c === '"' ? this.interpolate(body) : unescapeGroovy(body);
    }
    if (c === '(') {
      this.pos++;
      const value = this.sum();
      if (!this.eat(')')) return this.fail('missing )');
      return value;
    }
    if (c === '[') return this.collection();
    const number = /^\d+(?:\.\d+)?/.exec(this.src.slice(this.pos))?.[0];
    if (number) {
      this.pos += number.length;
      return Number(number);
    }
    const name = this.identifier();
    if (!name) return this.fail(`cannot read ${this.src.slice(this.pos) || 'an empty value'}`);
    if (name === 'true' || name === 'false') return name === 'true';
    if (name === 'new') {
      let type = this.identifier();
      while (this.eat('.')) type += `.${this.identifier()}`;
      return this.construct(type, this.args());
    }
    this.skip();
    if (this.src[this.pos] === '(') return this.call(SCOPE, name, this.args());
    return this.member(SCOPE, name);
  }

  // [a: '1', 'b': '2'] is a map and ['x', 'y'] a list
  private collection(): Value | undefined {
    this.eat('[');
    const map: { [key: string]: Value } = {};
    const list: Value[] = [];
    let isMap = false;
    while (!this.eat(']')) {
      const key = /^(?:([A-Za-z_]\w*)|"([^"]*)"|'([^']*)')\s*:/.exec(this.src.slice(this.pos).trimStart());
      if (key) {
        this.skip();
        this.pos += key[0].length;
        isMap = true;
      }
      const value = this.sum();
      if (value === undefined) return undefined;
      if (key) map[key[1] ?? key[2] ?? key[3]] = value;
      else list.push(value);
      if (!this.eat(',') && this.src[this.pos] !== ']') return this.fail('cannot read a list');
    }
    return isMap ? map : list;
  }

  private member(target: Value | undefined, name: string): Value | undefined {
    if (target === undefined) return undefined;
    if (target === SCOPE) {
      const known = this.vars.get(name) ?? this.props.get(name);
      return known === undefined ? this.fail(`unknown variable ${name}`) : known;
    }
    if (target instanceof FileRef) {
      if (name === 'text') return this.read(target);
      return ['absolutePath', 'path', 'canonicalPath'].includes(name) ? target.path : this.fail(`cannot read .${name}`);
    }
    if (target !== null && typeof target === 'object' && !Array.isArray(target) && name in target) return target[name];
    return this.fail(`${name} not found`);
  }

  private call(target: Value | undefined, name: string, args: Array<Value | undefined>): Value | undefined {
    if (target === undefined || args.some((a) => a === undefined)) return undefined;
    const [first] = args;
    if (target === SCOPE) {
      if (name === 'file' && typeof first === 'string') return new FileRef(first);
      if (name === 'uri' && typeof first === 'string') return first;
      if (['findProperty', 'property', 'getProperty'].includes(name) && typeof first === 'string') {
        const prop = this.vars.get(first) ?? this.props.get(first);
        return prop === undefined ? this.fail(`unknown property ${first}`) : prop;
      }
    } else if (target instanceof FileRef) {
      if (['getAbsolutePath', 'getPath', 'getCanonicalPath', 'toString'].includes(name)) return target.path;
      if (name === 'getText') return this.read(target);
      if (name === 'file' && typeof first === 'string') return new FileRef(`${target.path}/${first}`);
    } else if (typeof target === 'string') {
      if (name === 'trim') return target.trim();
      if (name === 'toString') return target;
    } else if (target instanceof JsonSlurper) {
      if (name === 'parseText' && typeof first === 'string') return this.parse(first);
      if (name === 'parse' && first instanceof FileRef) {
        const body = this.read(first);
        return typeof body === 'string' ? this.parse(body) : undefined;
      }
    }
    return this.fail(`cannot call ${name}()`);
  }

  private construct(type: string, args: Array<Value | undefined>): Value | undefined {
    if (args.some((a) => a === undefined)) return undefined;
    if (/(^|\.)JsonSlurper$/.test(type)) return new JsonSlurper() as unknown as Value;
    if (type === 'File' || type === 'java.io.File') {
      const parts = args.map((a) => (a instanceof FileRef ? a.path : typeof a === 'string' ? a : undefined));
      return parts.length > 0 && parts.every((p) => p !== undefined) ? new FileRef(parts.join('/')) : this.fail('cannot read File');
    }
    return this.fail(`cannot create ${type}`);
  }

  private read(file: FileRef): string | undefined {
    const body = this.files.get(file.path);
    return body === undefined ? this.fail(`${file.path} not found`) : body;
  }

  private parse(json: string): Value | undefined {
    try {
      return JSON.parse(json) as Value;
    } catch {
      return this.fail('not valid JSON');
    }
  }

  // "$name", "$a.b" and "${expression}"
  private interpolate(body: string): string | undefined {
    let out = '';
    for (let i = 0; i < body.length; ) {
      if (body[i] === '\\') {
        out += unescapeGroovy(body.slice(i, i + 2));
        i += 2;
      } else if (body[i] === '$' && body[i + 1] === '{') {
        const end = matching(body, i + 1);
        if (end === undefined) return this.fail('missing }');
        const value = this.run(body.slice(i + 2, end));
        if (value === undefined) return undefined;
        out += text(value);
        i = end + 1;
      } else if (body[i] === '$' && /[A-Za-z_]/.test(body[i + 1] ?? '')) {
        const path = /^[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*/.exec(body.slice(i + 1))![0];
        const value = this.run(path);
        if (value === undefined) return undefined;
        out += text(value);
        i += 1 + path.length;
      } else {
        out += body[i++];
      }
    }
    return out;
  }
}

class JsonSlurper {}

function text(value: Value): string {
  return value instanceof FileRef ? value.path : String(value);
}

function unescapeGroovy(body: string): string {
  return body.replace(/\\([ntr'"\\$])/g, (_, c: string) => ({ n: '\n', t: '\t', r: '\r' })[c] ?? c);
}

const GRADLERIO = /\bid\s*\(?\s*["'](org\.wpilib|edu\.wpi\.first)\.GradleRIO["']\s*\)?(?:\s*version\s*\(?\s*["']((\d{4})\.[^"']*)["'])?/;

/**
 * The WPILib season of a build.gradle: the year its GradleRIO plugin version starts with. Without a version
 * there, the plugin id tells: `edu.wpi.first.GradleRIO` up to 2026, `org.wpilib.GradleRIO` from 2027.
 */
export function gradleRioYear(gradleText: string): string | undefined {
  const match = GRADLERIO.exec(stripComments(gradleText));
  if (!match) return undefined;
  return match[3] ?? (match[1] === 'org.wpilib' ? '2027' : '2026');
}

/** The GradleRIO plugin version of a build.gradle, when it is written out. */
export function gradleRioVersion(gradleText: string): string | undefined {
  return GRADLERIO.exec(stripComments(gradleText))?.[2];
}

/** The ROBOT_MAIN_CLASS of a build.gradle, which GradleRIO puts in the jar's manifest. */
export function robotMainClass(gradleText: string): string | undefined {
  return /def\s+ROBOT_MAIN_CLASS\s*=\s*["']([^"']+)["']/.exec(stripComments(gradleText))?.[1];
}

/** A HAL simulation extension that build.gradle adds with `wpi.sim.add...()`. */
export interface SimExtension {
  /** GradleRIO's display name, such as `Sim GUI`. */
  name: string;
  /** The library, such as `halsim_gui`. */
  artifact: string;
  defaultEnabled: boolean;
}

// GradleRIO's SimulationExtension: method, display name, library
const SIM_EXTENSIONS: Record<string, [string, string]> = {
  addGui: ['Sim GUI', 'halsim_gui'],
  addDriverstation: ['Use Real DriverStation', 'halsim_ds_socket'],
  addWebsocketsServer: ['Sim Websockets Server', 'halsim_ws_server'],
  addWebsocketsClient: ['Sim Websockets Client', 'halsim_ws_client'],
  addXRPClient: ['Sim XRP Client', 'halsim_xrp'],
};

/** The `wpi.sim` settings of a build.gradle: extensions in the order added, and environment variables. */
export function simulationSettings(gradleText: string): { extensions: SimExtension[]; environment: Record<string, string> } {
  const gradle = stripComments(gradleText);
  const extensions: SimExtension[] = [];
  for (const m of gradle.matchAll(/wpi\.sim\.(add\w+)\(\s*\)(\s*\.defaultEnabled\s*=\s*true)?/g)) {
    const known = SIM_EXTENSIONS[m[1]];
    if (known) extensions.push({ name: known[0], artifact: known[1], defaultEnabled: !!m[2] });
  }
  const environment: Record<string, string> = {};
  for (const m of gradle.matchAll(/wpi\.sim\.envVar\(\s*["']([^"']*)["']\s*,\s*["']([^"']*)["']\s*\)/g)) environment[m[1]] = m[2];
  return { extensions, environment };
}
