// The class that the gversion Gradle plugin (com.peterabeles.gversion, 1.10.x) writes in createVersionFile,
// for teams that run it before compileJava. Pure, so test/gversion.mjs runs it under Node.

export interface GitResult {
  code: number;
  out: string;
}

/** Runs git in the project folder. Undefined when git is not available. */
export type GitRunner = (args: string[]) => Promise<GitResult | undefined>;

export interface GversionOptions {
  srcDir: string;
  classPackage: string;
  className: string;
  dateFormat: string;
  timeZone: string;
  indent: string;
  annotate: string;
  version?: string;
  /** build.gradle runs createVersionFile before compileJava, so an existing file is rewritten. */
  hooked: boolean;
}

export interface GversionProject {
  name: string;
  group: string;
  version: string;
}

export interface GversionFile {
  /** Relative to the Java source root. */
  path: string;
  /** Relative to the project folder, where Gradle writes it. */
  projectPath: string;
  source: string;
}

const DEFAULT_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";
const value = (text: string) => /^(?:["']([^"']*)["']|(?:project\.)?file\(\s*["']([^"']*)["']\s*\))/.exec(text.trim());
const ESCAPES: Record<string, string> = { t: '\t', n: '\n', r: '\r', b: '\b', f: '\f' };
const unescape = (text: string) => text.replace(/\\(.)/g, (_, c: string) => ESCAPES[c] ?? c);
const IDENTIFIER = /^[A-Za-z_$][\w$]*$/;

/** The gversion settings of a build.gradle (comments already stripped), or undefined without the plugin and its block. */
export function gversionOptions(gradle: string): GversionOptions | undefined {
  if (!/["']com\.peterabeles\.gversion["']/.test(gradle)) return undefined;
  const block = /(?<![\w.])gversion\s*\{([\s\S]*?)\}/.exec(gradle)?.[1];
  if (block === undefined) return undefined;
  const option = (key: string): string | undefined => {
    const match = new RegExp(`(?:^|\\n)\\s*${key}\\s*=?\\s*(\\S[^\\n]*)`).exec(block);
    const parsed = match && value(match[1]);
    return parsed ? unescape(parsed[1] ?? parsed[2]) : undefined;
  };
  const srcDir = option('srcDir');
  const classPackage = option('classPackage') ?? '';
  const className = option('className') ?? 'GVersion';
  // Only a parsed build.gradle decides where the file goes, so it stays inside the project
  if (srcDir === undefined || srcDir.startsWith('/') || srcDir.split(/[\\/]/).includes('..')) return undefined;
  if (!IDENTIFIER.test(className) || (classPackage && !classPackage.split('.').every((part) => IDENTIFIER.test(part)))) return undefined;
  const annotate = option('annotate') ?? (/(?:^|\n)\s*annotate\s*=?\s*true\b/.test(block) ? 'javax.annotation.Generated' : '');
  return {
    srcDir,
    classPackage,
    className,
    dateFormat: option('dateFormat') ?? DEFAULT_DATE_FORMAT,
    timeZone: option('timeZone') ?? 'UTC',
    indent: option('indent') ?? '\t',
    annotate,
    version: option('version'),
    hooked: /\bdependsOn\b[^\n]*\bcreateVersionFile\b/.test(gradle),
  };
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

function zone(timeZone: string): string {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone });
    return timeZone;
  } catch {
    return 'GMT'; // Java's TimeZone.getTimeZone answers GMT for an unknown id
  }
}

/** java.text.SimpleDateFormat for the usual letters, in a time zone. */
export function formatDate(date: Date, pattern: string, timeZone: string): string {
  const tz = zone(timeZone);
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    hourCycle: 'h23',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
  }).formatToParts(date);
  const part = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  const [year, month, day, hour, minute, second] = ['year', 'month', 'day', 'hour', 'minute', 'second'].map(part);
  const wall = Date.UTC(year, month - 1, day, hour, minute, second);
  const offset = Math.round((wall - Math.floor(date.getTime() / 1000) * 1000) / 60000);
  const weekday = new Date(wall).getUTCDay();
  const dayOfYear = Math.round((wall - Date.UTC(year, 0, 1)) / 86400000) + 1;
  const name = (style: 'short' | 'long') =>
    new Intl.DateTimeFormat('en-US', { timeZone: tz, timeZoneName: style }).formatToParts(date).find((p) => p.type === 'timeZoneName')?.value ?? tz;
  const pad = (n: number, width: number) => String(n).padStart(width, '0');
  const sign = offset < 0 ? '-' : '+';
  const hh = pad(Math.floor(Math.abs(offset) / 60), 2);
  const mm = pad(Math.abs(offset) % 60, 2);

  let out = '';
  for (let i = 0; i < pattern.length; ) {
    const c = pattern[i];
    if (c === "'") {
      if (pattern[i + 1] === "'") {
        out += "'";
        i += 2;
        continue;
      }
      const end = pattern.indexOf("'", i + 1);
      const close = end < 0 ? pattern.length : end;
      out += pattern.slice(i + 1, close).replaceAll("''", "'");
      i = close + 1;
      continue;
    }
    if (!/[A-Za-z]/.test(c)) {
      out += c;
      i++;
      continue;
    }
    let n = 1;
    while (pattern[i + n] === c) n++;
    i += n;
    switch (c) {
      case 'y':
      case 'Y':
        out += n === 2 ? pad(year % 100, 2) : pad(year, n);
        break;
      case 'M':
      case 'L':
        out += n >= 4 ? MONTHS[month - 1] : n === 3 ? MONTHS[month - 1].slice(0, 3) : pad(month, n);
        break;
      case 'd':
        out += pad(day, n);
        break;
      case 'D':
        out += pad(dayOfYear, n);
        break;
      case 'H':
        out += pad(hour, n);
        break;
      case 'k':
        out += pad(hour === 0 ? 24 : hour, n);
        break;
      case 'K':
        out += pad(hour % 12, n);
        break;
      case 'h':
        out += pad(hour % 12 === 0 ? 12 : hour % 12, n);
        break;
      case 'm':
        out += pad(minute, n);
        break;
      case 's':
        out += pad(second, n);
        break;
      case 'S':
        out += pad(date.getUTCMilliseconds(), n);
        break;
      case 'a':
        out += hour < 12 ? 'AM' : 'PM';
        break;
      case 'E':
        out += n >= 4 ? DAYS[weekday] : DAYS[weekday].slice(0, 3);
        break;
      case 'u':
        out += pad(weekday === 0 ? 7 : weekday, n);
        break;
      case 'z':
        out += name(n >= 4 ? 'long' : 'short');
        break;
      case 'Z':
        out += `${sign}${hh}${mm}`;
        break;
      case 'X':
        out += offset === 0 ? 'Z' : n === 1 ? `${sign}${hh}` : n === 2 ? `${sign}${hh}${mm}` : `${sign}${hh}:${mm}`;
        break;
      default:
        throw new Error(`date format letter ${c} is not supported`);
    }
  }
  return out;
}

interface GitValues {
  revision: string;
  sha: string;
  branch: string;
  date: string;
  dirty: number;
}

// The same commands as the plugin, each with its fallback
async function readGit(git: GitRunner | undefined): Promise<GitValues> {
  const output = async (args: string[], fallback: string): Promise<string> => {
    const result = await git?.(args).catch(() => undefined);
    const text = result?.out.trim() ?? '';
    return result && text && result.code === 0 ? text : fallback;
  };
  const dirty = await git?.(['diff', '--quiet', '--ignore-submodules=dirty']).catch(() => undefined);
  return {
    dirty: dirty ? dirty.code : -1,
    revision: await output(['rev-list', '--no-show-signature', '--count', 'HEAD'], '-1'),
    sha: await output(['rev-parse', 'HEAD'], 'UNKNOWN'),
    branch: await output(['rev-parse', '--abbrev-ref', 'HEAD'], 'UNKNOWN'),
    date: await output(['show', '--no-show-signature', '-s', '--format=%cI', 'HEAD'], 'UNKNOWN'),
  };
}

export interface GversionResult {
  file: GversionFile;
  warnings: string[];
}

/** What createVersionFile writes. `git` supplies the repository values; without it they are the plugin's fallbacks. */
export async function generateGversionFile(
  options: GversionOptions,
  project: GversionProject,
  git: GitRunner | undefined,
  now: Date = new Date(),
): Promise<GversionResult> {
  const warnings: string[] = [];
  const values = await readGit(git);
  const format = (date: Date) => {
    try {
      return formatDate(date, options.dateFormat, options.timeZone);
    } catch (error) {
      warnings.push(`gversion: ${(error as Error).message}, so the default format is used`);
      return formatDate(date, DEFAULT_DATE_FORMAT, options.timeZone);
    }
  };
  let gitDate = values.date;
  if (gitDate !== 'UNKNOWN') {
    const parsed = new Date(gitDate);
    gitDate = Number.isNaN(parsed.getTime()) ? 'UNKNOWN' : format(parsed);
  }
  const { indent, className } = options;
  const lines = [
    ...(options.classPackage ? [`package ${options.classPackage};`, ''] : []),
    ...(options.annotate ? [`import ${options.annotate};`, ''] : []),
    '/**',
    ' * Automatically generated file containing build version information.',
    ' */',
    ...(options.annotate ? ['@Generated("com.peterabeles.GVersion")'] : []),
    `public final class ${className} {`,
    `${indent}public static final String MAVEN_GROUP = "${project.group}";`,
    `${indent}public static final String MAVEN_NAME = "${project.name}";`,
    `${indent}public static final String VERSION = "${options.version ?? project.version}";`,
    `${indent}public static final int GIT_REVISION = ${values.revision};`,
    `${indent}public static final String GIT_SHA = "${values.sha}";`,
    `${indent}public static final String GIT_DATE = "${gitDate}";`,
    `${indent}public static final String GIT_BRANCH = "${values.branch}";`,
    `${indent}public static final String BUILD_DATE = "${format(now)}";`,
    `${indent}public static final long BUILD_UNIX_TIME = ${now.getTime()}L;`,
    `${indent}public static final int DIRTY = ${values.dirty};`,
    '',
    `${indent}private ${className}(){}`,
    '}',
    '',
  ];
  return { file: { ...gversionPaths(options), source: lines.join('\n') }, warnings };
}

/** Where the class goes, in the Java source root and in the project folder. */
export function gversionPaths(options: GversionOptions): { path: string; projectPath: string } {
  const packagePath = options.classPackage ? `${options.classPackage.replaceAll('.', '/')}/` : '';
  const dir = options.srcDir.replace(/^(\.\/)+/, '').replace(/\/+$/, '');
  const fileName = `${packagePath}${options.className}.java`;
  const root = 'src/main/java';
  const inRoot = dir === root || dir.startsWith(`${root}/`);
  return { path: inRoot ? `${dir.slice(root.length + 1)}${dir.length > root.length ? '/' : ''}${fileName}` : fileName, projectPath: `${dir ? `${dir}/` : ''}${fileName}` };
}
