// Builds the lombok jar the site serves: the release from Maven Central with the handler classes that
// scripts/lombok/lombok-<version>-*.patch changes recompiled from lombok's sources. Used by fetch-deps.
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { unzipSync, zipSync } from 'fflate';

const PATCH_DIR = path.join(import.meta.dirname, 'lombok');
const HANDLERS = 'lombok/javac/handlers/';
const JAVAC_PACKAGES = ['api', 'code', 'comp', 'tree', 'util', 'main', 'model', 'processing', 'parser', 'file'];
// A name that only the patched classes contain
const MARKER = 'outermostMember';

const sha256 = (...parts) => parts.reduce((hash, part) => hash.update(part), createHash('sha256')).digest('hex');
const text = (bytes) => Buffer.from(bytes).toString('utf8');
const handlerEntry = (name) => `SCL.lombok/${HANDLERS}${name}.SCL.lombok`;
// The handler and its nested classes, not other handlers whose names start the same way
const isExtensionMethodHandler = (entry) => /^SCL\.lombok\/lombok\/javac\/handlers\/HandleExtensionMethod(\$[^/]+)?\.SCL\.lombok$/.test(entry);

// Applies a unified diff of one file. Each hunk must match its context exactly.
function applyPatch(source, patch, fail) {
  const lines = source.split('\n');
  const hunks = [];
  const patchLines = patch.split('\n');
  if (patchLines.at(-1) === '') patchLines.pop();
  for (const line of patchLines) {
    const header = line.match(/^@@ -(\d+)/);
    if (header) hunks.push({ at: Number(header[1]) - 1, old: [], next: [] });
    else if (hunks.length && !line.startsWith('\\')) {
      const body = line.slice(1);
      if (line[0] !== '+') hunks.at(-1).old.push(body);
      if (line[0] !== '-') hunks.at(-1).next.push(body);
    }
  }
  if (!hunks.length) fail('it has no hunks');
  let shift = 0;
  for (const [i, { at, old, next }] of hunks.entries()) {
    const start = at + shift;
    if (!old.every((line, j) => lines[start + j] === line)) fail(`hunk ${i + 1} does not match at line ${at + 1}`);
    lines.splice(start, old.length, ...next);
    shift += next.length - old.length;
  }
  return lines.join('\n');
}

/**
 * Writes the patched jar to `dest`, unless the one there was built from the same inputs.
 * `sourcesJar` and `jar` are the release's sources and binary, `javaHome` the JDK (release name `jdk`) that
 * compiles the handlers.
 */
export function patchLombok({ version, jar, sourcesJar, jdk, javaHome, dest, workDir }) {
  const patchFile = path.join(PATCH_DIR, `lombok-${version}-extension-method.patch`);
  const drop = 'remove the lombok patch: lombokWithFix in scripts/fetch-deps.mjs, scripts/patch-lombok.mjs and scripts/lombok/';
  const remake = `Make scripts/lombok/lombok-${version}-extension-method.patch for lombok ${version} (the diff of https://github.com/projectlombok/lombok/pull/4103 against its sources), or, if this lombok release includes the fix, ${drop}.`;
  if (!fs.existsSync(patchFile)) throw new Error(`There is no lombok patch for ${version}. ${remake}`);
  const patch = fs.readFileSync(patchFile, 'utf8');
  const stock = fs.readFileSync(jar);

  const key = sha256(stock, patch, fs.readFileSync(import.meta.filename), jdk);
  const keyFile = `${dest}.key`;
  if (fs.existsSync(dest) && fs.existsSync(keyFile) && fs.readFileSync(keyFile, 'utf8') === key) return;

  const entries = unzipSync(stock);
  const stockVisitor = entries[handlerEntry('HandleExtensionMethod$ExtensionMethodReplaceVisitor')];
  if (!stockVisitor) throw new Error(`lombok ${version} has no javac HandleExtensionMethod visitor. ${remake}`);
  if (Buffer.from(stockVisitor).includes(MARKER)) throw new Error(`lombok ${version} already has the fix, ${drop}.`);

  const sourceFile = text(unzipSync(fs.readFileSync(sourcesJar))[`${HANDLERS}HandleExtensionMethod.java`] ?? '');
  if (!sourceFile) throw new Error(`${path.basename(sourcesJar)} has no HandleExtensionMethod.java`);
  const patched = applyPatch(sourceFile, patch.slice(patch.indexOf('@@')), (why) => {
    throw new Error(`The lombok patch does not apply to lombok ${version}: ${why}. ${remake}`);
  });

  fs.rmSync(workDir, { recursive: true, force: true });
  const classes = path.join(workDir, 'classes');
  const out = path.join(workDir, 'out');
  const src = path.join(workDir, 'src', HANDLERS);
  fs.mkdirSync(out, { recursive: true });
  fs.mkdirSync(src, { recursive: true });
  try {
    // Lombok keeps its classes as SCL.lombok/<name>.SCL.lombok. Class<N>/ holds the ones for older javac.
    for (const [entry, data] of Object.entries(entries)) {
      const name = entry.match(/^SCL\.lombok\/(?!Class\d)(.+)\.SCL\.lombok$/)?.[1];
      if (!name || isExtensionMethodHandler(entry)) continue;
      fs.mkdirSync(path.dirname(path.join(classes, `${name}.class`)), { recursive: true });
      fs.writeFileSync(path.join(classes, `${name}.class`), data);
    }
    // Only lombok's own build reads @Provides, to write the META-INF/services entry that the jar has
    fs.writeFileSync(path.join(src, 'HandleExtensionMethod.java'), patched.replace(/^import lombok\.spi\.Provides;\n/m, '').replace(/^@Provides\n/m, ''));
    // No --release: javac refuses --add-exports with it, so the classes get the JDK's own version, like the season's other Java code.
    execFileSync(path.join(javaHome, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac'), [
      '-nowarn', '-proc:none', '-encoding', 'UTF-8',
      ...JAVAC_PACKAGES.flatMap((pkg) => ['--add-exports', `jdk.compiler/com.sun.tools.javac.${pkg}=ALL-UNNAMED`]),
      '-cp', [classes, jar].join(path.delimiter), '-d', out, path.join(src, 'HandleExtensionMethod.java'),
    ], { stdio: ['ignore', 'inherit', 'inherit'] });

    const built = fs.readdirSync(path.join(out, HANDLERS)).filter((f) => f.endsWith('.class'));
    if (!built.includes('HandleExtensionMethod.class') || built.some((f) => !f.startsWith('HandleExtensionMethod'))) {
      throw new Error(`javac wrote ${built.join(', ')}, not the classes of HandleExtensionMethod`);
    }
    // Every class of the handler is replaced, including nested ones the patch removes or adds
    const files = Object.fromEntries(Object.entries(entries).filter(([entry]) => !isExtensionMethodHandler(entry)));
    for (const file of built) files[handlerEntry(file.slice(0, -'.class'.length))] = fs.readFileSync(path.join(out, HANDLERS, file));
    if (!Buffer.from(files[handlerEntry('HandleExtensionMethod$ExtensionMethodReplaceVisitor')] ?? '').includes(MARKER)) {
      throw new Error('The patched ExtensionMethodReplaceVisitor lacks the patch');
    }
    const mtime = new Date(1980, 0, 1);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(`${dest}.part`, zipSync(files, { level: 9, mtime }));
    fs.renameSync(`${dest}.part`, dest);
    fs.writeFileSync(keyFile, key);
    console.log(`built ${path.basename(dest)} (${built.length} handler classes)`);
  } finally {
    fs.rmSync(workDir, { recursive: true, force: true });
  }
}
