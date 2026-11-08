// Builds the shim and tools jars of each WPILib season with the JDK that fetch-deps downloaded for it,
// into runtime/<year>/. `--year <y>` limits it to one season. With --test, also compiles and runs the
// desktop tests; test class names after --test run alone, e.g. `node build.mjs --test PwmTest`.
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { WPILIB_YEARS } from '../runtime/src/years.ts';

// Per season: the Java release its code targets, its shim and tests (relative to this folder), and the
// generator's package prefix.
const SEASONS = {
  2026: { release: '17', shim: 'shim/src', shimTests: 'shim/test/java', halTests: 'test/src', root: 'edu.wpi.first.' },
  2027: { release: '25', shim: 'shim-2027/src', shimTests: 'shim-2027/test/java', halTests: 'test-2027/src', root: 'org.wpilib.' },
};

const HERE = import.meta.dirname;
const ROOT = path.resolve(HERE, '..', '..');
const RUNTIME_ROOT = path.join(ROOT, 'apps', 'ide', 'public', 'runtime');
const run = (cmd, args, cwd) => execFileSync(cmd, args, { stdio: 'inherit', cwd });

const args = process.argv.slice(2);
const years = args.flatMap((arg, i) => (args[i - 1] === '--year' ? [arg] : []));
for (const year of years) if (!SEASONS[year]) throw new Error(`WPILib ${year} is not one of ${Object.keys(SEASONS).join(', ')}`);
const plain = args.filter((arg, i) => arg !== '--year' && args[i - 1] !== '--year');
const testAt = plain.indexOf('--test');
const onlyTests = testAt >= 0 ? plain.slice(testAt + 1) : undefined;

for (const year of years.length ? years : WPILIB_YEARS) buildSeason(year);

function buildSeason(year) {
  const season = SEASONS[year];
  const BUILD = path.join(HERE, 'build', year);
  const RUNTIME = path.join(RUNTIME_ROOT, year);
  const CACHE = path.join(ROOT, '.cache', year);
  const SOURCES = path.join(CACHE, 'sources');

  function findJdk() {
    const file = path.join(CACHE, 'java-home');
    const home = fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trim() : undefined;
    if (!home || !fs.existsSync(path.join(home, 'bin', 'javac'))) {
      throw new Error(`No JDK for WPILib ${year}. Run \`pnpm fetch-deps --year ${year}\` first.`);
    }
    return home;
  }

  const JDK = findJdk();
  const tool = (name) => path.join(JDK, 'bin', name);

  function javaFiles(dir, skip) {
    if (!fs.existsSync(dir)) return [];
    return fs.readdirSync(dir, { recursive: true })
      .filter((f) => f.endsWith('.java'))
      .map((f) => path.join(dir, f))
      .filter((f) => !skip || !f.startsWith(skip));
  }

  function javac(out, sourceDirs, classpath, extraFiles = [], skip) {
    fs.rmSync(out, { recursive: true, force: true });
    fs.mkdirSync(out, { recursive: true });
    const files = [...sourceDirs.flatMap((d) => javaFiles(d, skip)), ...extraFiles];
    if (files.length === 0) return;
    const argFile = `${out}.args`;
    fs.writeFileSync(argFile, files.map((f) => `"${f}"`).join('\n'));
    // Inline concatenation: CheerpJ runs an invokedynamic string concat 15 times slower than a StringBuilder.
    const args = ['-J-Xmx512m', '--release', season.release, '-XDstringConcat=inline', '-encoding', 'UTF-8', '-g', '-nowarn', '-proc:none', '-d', out];
    if (classpath.length) args.push('-classpath', classpath.join(path.delimiter));
    run(tool('javac'), [...args, `@${argFile}`]);
    for (const f of fs.readdirSync(out, { recursive: true })) {
      if (f.endsWith('.class') && fs.readFileSync(path.join(out, f)).includes('makeConcatWithConstants')) {
        throw new Error(`${f} concatenates strings through invokedynamic, which is slow on CheerpJ`);
      }
    }
  }

  function jar(file, ...dirs) {
    fs.rmSync(file, { force: true });
    const args = ['--create', '--file', file];
    for (const d of dirs) args.push('-C', d, '.');
    run(tool('jar'), args);
  }

  const manifest = JSON.parse(fs.readFileSync(path.join(RUNTIME, 'manifest.json'), 'utf8'));
  // Optional jars are vendordeps for projects, but the shim and its tests use them.
  const wpilib = [...manifest.classpath, ...(manifest.optional ?? [])].map((p) => path.join(RUNTIME, p));
  // The cache keeps the sources of earlier releases too.
  const sourceJars = fs.existsSync(SOURCES)
    ? fs.readdirSync(SOURCES).filter((f) => f.endsWith(`-${manifest.wpilib}-sources.jar`)).sort().map((f) => path.join(SOURCES, f))
    : [];
  if (sourceJars.length === 0) throw new Error('WPILib sources jars missing. Run `pnpm fetch-deps` first.');

  const shimJar = path.join(RUNTIME, 'frcdevwasm-shim.jar');
  // Only the 2026 season has a SmartDashboard release.
  const hasSmartDashboard = year === '2026';
  const SMARTDASHBOARD = path.join(RUNTIME_ROOT, 'tools', 'smartdashboard');
  const smartDashboardJar = path.join(SMARTDASHBOARD, 'SmartDashboard.jar');
  const smartDashboardShimJar = path.join(SMARTDASHBOARD, 'frcdevwasm-smartdashboard.jar');
  const toolsJar = path.join(RUNTIME, 'frcdevwasm-tools.jar');
  const junitJar = path.join(RUNTIME, 'frcdevwasm-junit.jar');
  // The JUnit launcher (./gradlew test) compiles against the launcher that fetch-deps downloaded. The browser runs it with the project's own.
  const JUNIT_CACHE = path.join(ROOT, '.cache', 'junit');
  const junitJars = fs.existsSync(JUNIT_CACHE) ? fs.readdirSync(JUNIT_CACHE).filter((f) => f.endsWith('.jar')).sort().map((f) => path.join(JUNIT_CACHE, f)) : [];
  if (junitJars.length === 0) throw new Error('JUnit jars missing. Run `pnpm fetch-deps` first.');
  const junitApi = junitJars.filter((f) => /junit-platform-(launcher|engine|commons)|opentest4j|apiguardian/.test(path.basename(f)));
  const TOOLS = path.join(ROOT, '.cache', 'tools');
  const asmFile = fs.existsSync(TOOLS) ? fs.readdirSync(TOOLS).find((f) => /^asm-.*\.jar$/.test(f)) : undefined;
  if (!asmFile) throw new Error('ASM jar missing. Run `pnpm fetch-deps` first.');
  const asmJar = path.join(TOOLS, asmFile);
  const VENDOR_RAW = path.join(CACHE, 'vendor');
  const VENDOR_TRANSFORMED = path.join(CACHE, 'vendor-transformed');
  const VENDOR_SHIM = path.join(HERE, season.shim, 'dev', 'project516', 'frcdevwasm', 'shim', 'vendor');
  // OpenCV's natives, in Java, for both seasons
  const OPENCV_IMPL = path.join(HERE, 'opencv', 'src');
  const dirs = {
    vendorTool: path.join(BUILD, 'vendor-tool-classes'),
    vendorImpl: path.join(BUILD, 'vendor-impl-classes'),
    asm: path.join(BUILD, 'asm-classes'),
    vendorResources: path.join(BUILD, 'vendor-resources'),
    gen: path.join(BUILD, 'gen-classes'),
    impl: path.join(BUILD, 'impl-classes'),
    generated: path.join(BUILD, 'generated'),
    jni: path.join(BUILD, 'jni-classes'),
    tools: path.join(BUILD, 'tools-classes'),
    junit: path.join(BUILD, 'junit-classes'),
    junitTest: path.join(BUILD, 'junit-test-classes'),
    test: path.join(BUILD, 'test-classes'),
    shimTest: path.join(BUILD, 'shim-test-classes'),
    smartdashboard: path.join(BUILD, 'smartdashboard-classes'),
    smartdashboardTest: path.join(BUILD, 'smartdashboard-test-classes'),
  };

  fs.mkdirSync(BUILD, { recursive: true });

  // Impl classes first: the generator links natives only to Impl methods that exist.
  javac(dirs.impl, [path.join(HERE, 'bridge', 'src'), path.join(HERE, season.shim)], wpilib, [], VENDOR_SHIM);

  javac(dirs.gen, [path.join(HERE, 'generator', 'src')], []);
  fs.rmSync(dirs.generated, { recursive: true, force: true });
  run(tool('java'), [
    '-Xmx512m', '-cp', dirs.gen, 'dev.project516.frcdevwasm.gen.Generator',
    '--root', season.root,
    '--classpath', wpilib.join(path.delimiter),
    '--impl', dirs.impl,
    '--out', dirs.generated,
    '--report', path.join(BUILD, 'natives-report.txt'),
    ...sourceJars,
  ]);
  javac(dirs.jni, [dirs.generated], [dirs.impl, ...wpilib]);

  buildVendorLibraries();
  stubOpenCv();

  fs.mkdirSync(RUNTIME, { recursive: true });
  jar(shimJar, dirs.impl, dirs.jni, dirs.vendorImpl);

  // The tools jar also carries the vendor jar transform that the browser runs on downloaded jars.
  javac(dirs.tools, [path.join(HERE, 'tools', 'src')], []);
  fs.rmSync(dirs.asm, { recursive: true, force: true });
  fs.mkdirSync(dirs.asm, { recursive: true });
  run(tool('jar'), ['--extract', '--file', asmJar], dirs.asm);
  fs.rmSync(path.join(dirs.asm, 'META-INF'), { recursive: true, force: true });
  fs.copyFileSync(path.join(HERE, 'vendor', 'ASM-LICENSE.txt'), path.join(dirs.asm, 'ASM-LICENSE.txt'));
  jar(toolsJar, dirs.tools, dirs.vendorTool, dirs.asm, dirs.vendorResources);
  console.log(`built ${path.relative(ROOT, shimJar)} and ${path.relative(ROOT, toolsJar)}`);

  javac(dirs.junit, [path.join(HERE, 'junit', 'src')], junitApi);
  jar(junitJar, dirs.junit);
  console.log(`built ${path.relative(ROOT, junitJar)}`);

  // SmartDashboard bundles the NetworkTables 3 API, so its JNI classes are replaced separately
  // from the shim and put ahead of its jar.
  if (hasSmartDashboard) {
    if (!fs.existsSync(smartDashboardJar)) throw new Error('SmartDashboard.jar missing. Run `pnpm fetch-deps` first.');
    javac(dirs.smartdashboard, [path.join(HERE, 'bridge', 'src'), path.join(HERE, 'smartdashboard', 'src')], [smartDashboardJar]);
    jar(smartDashboardShimJar, dirs.smartdashboard);
    console.log(`built ${path.relative(ROOT, smartDashboardShimJar)}`);
  }

  // Compiles the vendor shim classes against the vendor jars that fetch-deps downloaded, and the
  // transform that makes a vendor jar run without native code. The site does not serve vendor jars:
  // the browser downloads them and runs the transform. The version in transform.json changes when the
  // transform output can change, which invalidates the transformed jars cached on devices.
  function buildVendorLibraries() {
    const indexFile = path.join(VENDOR_RAW, 'index.json');
    if (!fs.existsSync(indexFile)) throw new Error('Vendor libraries missing. Run `pnpm fetch-deps` first.');
    const { vendordeps, older = [], maven } = JSON.parse(fs.readFileSync(indexFile, 'utf8'));

    // Vendor shim classes compile against the newest version of each library, and then the older
    // releases whose natives they also implement.
    const newest = new Set();
    const visit = (key) => {
      if (!maven[key] || newest.has(key)) return;
      newest.add(key);
      maven[key].deps.forEach(visit);
    };
    [...vendordeps.flatMap((v) => v.java), ...older].forEach(visit);
    const missing = Object.values(maven).map(({ jar: rel }) => path.join(VENDOR_RAW, rel)).find((f) => !fs.existsSync(f));
    if (missing) throw new Error(`${path.relative(ROOT, missing)} missing. Run \`pnpm fetch-deps\` first.`);
    const rawJars = [...newest].map((key) => path.join(VENDOR_RAW, maven[key].jar));
    javac(dirs.vendorImpl, [VENDOR_SHIM, OPENCV_IMPL], [dirs.impl, dirs.jni, ...wpilib, ...rawJars]);

    javac(dirs.vendorTool, [path.join(HERE, 'vendor', 'src')], [asmJar]);
    const resources = path.join(dirs.vendorResources, 'dev', 'project516', 'frcdevwasm', 'vendor');
    fs.rmSync(dirs.vendorResources, { recursive: true, force: true });
    fs.mkdirSync(resources, { recursive: true });
    const impls = path.join(resources, 'impls.txt');
    run(tool('java'), ['-cp', [dirs.vendorTool, asmJar].join(path.delimiter), 'dev.project516.frcdevwasm.vendor.NativeStubber', '--index', dirs.vendorImpl, impls]);

    const version = createHash('sha256')
      .update(fs.readFileSync(path.join(HERE, 'vendor', 'src', 'dev', 'project516', 'frcdevwasm', 'vendor', 'NativeStubber.java')))
      .update(fs.readFileSync(impls))
      .update(path.basename(asmJar))
      .digest('hex')
      .slice(0, 12);
    fs.mkdirSync(path.join(RUNTIME, 'vendor'), { recursive: true });
    fs.writeFileSync(path.join(RUNTIME, 'vendor', 'transform.json'), `${JSON.stringify({ version })}\n`);
  }

  // WPILib's OpenCV jar is on the robot classpath and its natives need a library that neither JVM has. The jar
  // is redistributable, so the site serves it stubbed like the vendor jars: natives call Impl classes or return
  // defaults. It is always rebuilt from the jar as downloaded, kept under .cache.
  function stubOpenCv() {
    const lib = path.join(RUNTIME, 'lib');
    for (const name of fs.readdirSync(lib).filter((f) => /^opencv-java-.*\.jar$/.test(f))) {
      const original = path.join(CACHE, 'opencv', name);
      if (!fs.existsSync(original)) {
        fs.mkdirSync(path.dirname(original), { recursive: true });
        fs.copyFileSync(path.join(lib, name), original);
      }
      run(tool('java'), [
        '-cp', [dirs.vendorTool, asmJar].join(path.delimiter),
        'dev.project516.frcdevwasm.vendor.NativeStubber',
        '--impl', dirs.vendorImpl,
        '--report', path.join(BUILD, 'opencv-natives-report.txt'),
        original, path.join(lib, name),
      ]);
    }
  }

  // What the site does in the browser, for the desktop tests: every downloaded jar, transformed under .cache.
  function transformVendorJars() {
    const { maven } = JSON.parse(fs.readFileSync(path.join(VENDOR_RAW, 'index.json'), 'utf8'));
    const pairs = Object.values(maven).flatMap(({ jar: rel }) => [path.join(VENDOR_RAW, rel), path.join(VENDOR_TRANSFORMED, rel)]);
    const argFile = path.join(BUILD, 'vendor-transform.args');
    fs.writeFileSync(argFile, pairs.map((f) => `"${f}"`).join('\n'));
    run(tool('java'), [
      '-Xmx512m', '-cp', [dirs.vendorTool, asmJar].join(path.delimiter),
      'dev.project516.frcdevwasm.vendor.NativeStubber',
      '--impl', dirs.vendorImpl,
      '--report', path.join(BUILD, 'vendor-natives-report.txt'),
      `@${argFile}`,
    ]);
  }

  // The JUnit launcher against tests that pass, fail, skip, print and nest: its console output must equal junit/test/expected.txt,
  // which is what Gradle prints for these tests apart from the failure messages (see StackTraces).
  function testJunitDriver() {
    const fixtures = path.join(HERE, 'junit', 'test');
    javac(dirs.junitTest, [path.join(fixtures, 'java')], junitJars);
    const classpath = [dirs.junitTest, dirs.junit, ...junitJars].join(path.delimiter);
    const runDriver = (...args) => {
      const result = spawnSync(tool('java'), ['-Xmx512m', '-cp', classpath, 'dev.project516.frcdevwasm.junit.TestRunner', ...args], { encoding: 'utf8' });
      return { code: result.status, out: result.stdout + result.stderr };
    };
    const all = ['BrokenSetupTest', 'PlainTest', 'frc.robot.BasicTest', 'frc.robot.BasicTest$Inner'];
    const expectedFile = path.join(fixtures, 'expected.txt');
    const full = runDriver(...all);
    if (process.env.UPDATE_JUNIT_EXPECTED) fs.writeFileSync(expectedFile, full.out);
    const failures = [];
    if (full.out !== fs.readFileSync(expectedFile, 'utf8')) failures.push('console output differs from junit/test/expected.txt (UPDATE_JUNIT_EXPECTED=1 rewrites it)');
    if (full.code !== 1) failures.push(`exit code ${full.code} for failing tests, expected 1`);
    const passes = runDriver('--tests', 'frc.robot.BasicTest.passes', ...all);
    if (passes.code !== 0 || passes.out !== '\nBasicTest > passes() PASSED\n') failures.push(`--tests by method: ${JSON.stringify(passes)}`);
    const simple = runDriver('--tests', 'BasicTest.passes', ...all);
    if (simple.code !== 0 || simple.out !== passes.out) failures.push(`--tests by method without the package: ${JSON.stringify(simple)}`);
    const byClass = runDriver('--tests', '*PlainTest', ...all);
    if (byClass.code !== 0 || byClass.out !== '\nPlainTest > ok() PASSED\n') failures.push(`--tests by class with a wildcard: ${JSON.stringify(byClass)}`);
    const none = runDriver('--tests', 'Nothing', ...all);
    if (none.code !== 3 || !none.out.includes('No tests found for given includes: [Nothing](--tests filter)')) failures.push(`--tests that matches nothing: ${JSON.stringify(none)}`);
    if (failures.length > 0) {
      console.error(`FAIL JUnit launcher\n${failures.join('\n')}\n--- output ---\n${full.out}`);
      process.exit(1);
    }
    console.log('ok   JUnit launcher');
  }

  if (onlyTests) {
    transformVendorJars();
    const only = onlyTests;
    const javaLib = wpilib.filter((p) => fs.existsSync(p));
    javac(dirs.test, [path.join(HERE, season.halTests)], [shimJar, toolsJar, dirs.vendorTool, asmJar, ...javaLib]);
    run(tool('java'), [
      '-Xmx512m', '-ea',
      `-Dfrcdevwasm.jdk=${JDK}`,
      `-Dfrcdevwasm.runtime=${RUNTIME}`,
      `-Dfrcdevwasm.vendor=${VENDOR_RAW}`,
      `-Dfrcdevwasm.vendorTransformed=${VENDOR_TRANSFORMED}`,
      '-cp', [dirs.test, shimJar, toolsJar, dirs.vendorTool, asmJar, ...wpilib].join(path.delimiter),
      'dev.project516.frcdevwasm.test.TestMain',
      ...only,
    ]);

    if (only.length === 0 || only.includes('junit')) testJunitDriver();

    // Tests of the nt, math, cscore, apriltag and wpinet shims; same filter rules.
    javac(dirs.shimTest, [path.join(HERE, season.shimTests)], [shimJar, ...wpilib]);
    run(tool('java'), [
      '-Xmx512m', '-ea',
      '-cp', [dirs.shimTest, shimJar, ...wpilib].join(path.delimiter),
      'dev.project516.frcdevwasm.shim.test.TestMain', dirs.shimTest,
      ...only,
    ]);

    // Tests of the SmartDashboard client; same filter rules.
    if (!hasSmartDashboard) return;
    const sharedTests = path.join(HERE, 'shim', 'test', 'java', 'dev', 'project516', 'frcdevwasm', 'shim', 'test');
    javac(dirs.smartdashboardTest, [path.join(HERE, 'smartdashboard', 'test', 'java')], [dirs.smartdashboard, smartDashboardJar],
      ['T.java', 'TestMain.java'].map((f) => path.join(sharedTests, f)));
    run(tool('java'), [
      '-Xmx512m', '-ea',
      '-cp', [dirs.smartdashboardTest, dirs.smartdashboard, smartDashboardJar].join(path.delimiter),
      'dev.project516.frcdevwasm.shim.test.TestMain', dirs.smartdashboardTest,
      ...only,
    ]);
  }
}
