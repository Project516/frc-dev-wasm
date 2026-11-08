// Downloads the WPILib jars, project templates, vendor libraries and JDK of each season
// into runtime/<year>/ and .cache/<year>/.
// `--year <y>` limits it to one season. Safe to rerun; finished files are skipped.
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { unzipSync, zipSync } from 'fflate';
import { pomDependencies } from '../packages/runtime/src/pom.ts';
import { WPILIB_YEARS } from '../packages/runtime/src/years.ts';

const ROOT = path.resolve(import.meta.dirname, '..');
const CACHE = path.join(ROOT, '.cache');
const DOWNLOADS = path.join(CACHE, 'downloads');
const RUNTIME = path.join(ROOT, 'apps', 'ide', 'public', 'runtime');

const FRC_MAVEN = 'https://frcmaven.wpi.edu/artifactory/release';
const CENTRAL = 'https://repo1.maven.org/maven2';

const central = (group, artifact, version) => ({ repo: CENTRAL, group, artifact, version });

// One entry per year in WPILIB_YEARS.
const SEASONS = {
  2026() {
    const version = '2026.2.1';
    const wpi = (group, artifact, sources = false) => ({ repo: FRC_MAVEN, group: `edu.wpi.first.${group}`, artifact, version, sources });
    const tools = `${FRC_MAVEN}/edu/wpi/first/tools`;
    return {
      version,
      jdk: 'jdk-17.0.20.1+1',
      // Group prefix of the WPILib artifacts, which vendor poms depend on but the site serves itself.
      group: 'edu.wpi.first',
      // Mirrors what GradleRIO puts on a Java robot classpath.
      jars: [
        wpi('wpilibj', 'wpilibj-java'),
        wpi('wpilibNewCommands', 'wpilibNewCommands-java'),
        wpi('wpimath', 'wpimath-java', true),
        wpi('wpiutil', 'wpiutil-java', true),
        wpi('wpiunits', 'wpiunits-java'),
        wpi('ntcore', 'ntcore-java', true),
        wpi('hal', 'hal-java', true),
        wpi('cscore', 'cscore-java', true),
        wpi('cameraserver', 'cameraserver-java'),
        wpi('apriltag', 'apriltag-java', true),
        wpi('wpinet', 'wpinet-java', true),
        wpi('epilogue', 'epilogue-runtime-java'),
        { repo: FRC_MAVEN, group: 'edu.wpi.first.thirdparty.frc2025.opencv', artifact: 'opencv-java', version: '4.10.0-3' },
        ...['ejml-simple', 'ejml-core', 'ejml-ddense', 'ejml-fdense', 'ejml-cdense', 'ejml-zdense', 'ejml-dsparse', 'ejml-fsparse'].map((a) => central('org.ejml', a, '0.44.0')),
        ...['jackson-annotations', 'jackson-core', 'jackson-databind'].map((a) => central('com.fasterxml.jackson.core', a, '2.19.2')),
        central('us.hebi.quickbuf', 'quickbuf-runtime', '1.4'),
      ],
      zips: ['templates', 'examples', 'commands'].map((name) => ({ ...wpi('wpilibj', name), ext: 'zip' })),
      // Field images and their JSON configs, for the Sim GUI field view.
      fieldImages: wpi('fieldImages', 'fieldImages-java'),
      // Annotation processors that robot projects commonly declare in build.gradle.
      processors: [wpi('epilogue', 'epilogue-processor-java'), central('org.projectlombok', 'lombok', '1.18.48')],
      // The vendor library list the WPILib VS Code extension offers (wpilibsuite/vendor-json-repo).
      marketplace: 'https://frcmaven.wpi.edu/artifactory/vendordeps/vendordep-marketplace/2026.json',
      // WPILib vendordeps whose jars share the WPILib version.
      wpilibVendorJars: [wpi('romiVendordep', 'romiVendordep-java'), wpi('xrpVendordep', 'xrpVendordep-java')],
      elastic: {
        url: 'https://github.com/Gold872/elastic_dashboard/releases/download/v2026.1.2/Elastic-Web.zip',
        sha256: '6865537c1e2281b73a54aba475e9fa0d30fd270cc2f2cab62b7949e3578e3f47',
      },
      // Project skeleton files that the WPILib VS Code extension copies into new projects.
      vscodeWpilibTag: `v${version}`,
      projectTemplates: /^(java|javaromi|javaxrp|shared)\/|^version\.txt$/,
      projectVendordeps: ['wpilibNewCommands/WPILibNewCommands.json', 'romiVendordep/RomiVendordep.json', 'xrpVendordep/XRPVendordep.json'],
      // Java Swing tools, unmodified in CheerpJ. Shared runtime/tools/ paths: only this season has them.
      // The SmartDashboard platform jars differ only in the native libraries they bundle, and CheerpJ loads none of them.
      swingTools: [
        { url: `${tools}/RobotBuilder/${version}/RobotBuilder-${version}.jar`, dest: 'robotbuilder/RobotBuilder.jar' },
        { url: `${tools}/SmartDashboard/${version}/SmartDashboard-${version}-linuxx64.jar`, dest: 'smartdashboard/SmartDashboard.jar' },
      ],
    };
  },
  // The alphas of the next season
  2027() {
    const version = '2027.0.0-alpha-8';
    const wpi = (name) => ({ repo: FRC_MAVEN, group: `org.wpilib.${name}`, artifact: `${name}-java`, version, sources: true });
    const wpilibj = (artifact) => ({ repo: FRC_MAVEN, group: 'org.wpilib.wpilibj', artifact, version });
    return {
      version,
      jdk: 'jdk-25.0.4.1+1',
      group: 'org.wpilib',
      // Mirrors what GradleRIO 2027 puts on a Java robot classpath (WPIJavaDepsExtension).
      jars: [
        ...['wpilibj', 'wpimath', 'ntcore', 'hal', 'wpinet', 'wpiutil', 'wpiunits', 'datalog', 'drivers', 'fields', 'telemetry', 'tunables'].map(wpi),
        // Vendordeps in GradleRIO 2027: off the robot classpath, served as bundled jars for the projects that add them.
        ...['cscore', 'cameraserver', 'apriltag'].map((name) => ({ ...wpi(name), optional: true })),
        { repo: FRC_MAVEN, group: 'org.wpilib.thirdparty.opencv', artifact: 'opencv-java', version: '2027-4.13.0-3', optional: true },
        { ...wpi('epilogue'), artifact: 'epilogue-runtime-java' },
        { repo: FRC_MAVEN, group: 'org.wpilib', artifact: 'annotations-java', version, sources: true },
        ...['avaje-jsonb', 'avaje-json-core'].map((a) => central('io.avaje', a, '3.14')),
        ...['ejml-simple', 'ejml-core', 'ejml-ddense', 'ejml-fdense', 'ejml-cdense', 'ejml-zdense', 'ejml-dsparse', 'ejml-fsparse'].map((a) => central('org.ejml', a, '0.46.0')),
        central('us.hebi.quickbuf', 'quickbuf-runtime', '1.4'),
      ],
      zips: ['templates', 'examples', 'commands'].map((name) => ({ ...wpilibj(name), ext: 'zip' })),
      // The processors GradleRIO puts on javac's processor path, plus lombok.
      processors: [
        { ...wpi('epilogue'), artifact: 'epilogue-processor-java' },
        { repo: FRC_MAVEN, group: 'org.wpilib', artifact: 'javac-plugin-java', version },
        central('io.avaje', 'avaje-jsonb-generator', '3.14'),
        central('org.projectlombok', 'lombok', '1.18.48'),
      ],
      marketplace: 'https://frcmaven.wpi.edu/artifactory/vendordeps/vendordep-marketplace/2027_alpha7.json',
      // Commands are vendordeps in 2027 and share the WPILib version, like the Romi and XRP libraries.
      wpilibVendorJars: [
        { repo: FRC_MAVEN, group: 'org.wpilib.commandsv2', artifact: 'commandsv2-java', version },
        { repo: FRC_MAVEN, group: 'org.wpilib', artifact: 'commandsv3-java', version },
        { repo: FRC_MAVEN, group: 'org.wpilib.romiVendordep', artifact: 'romiVendordep-java', version },
        { repo: FRC_MAVEN, group: 'org.wpilib.xrpVendordep', artifact: 'xrpVendordep-java', version },
      ],
      // Older releases that projects still use. Only the Impl classes and desktop tests need them.
      olderVendorJars: [{ repo: 'https://maven.ctr-electronics.com/release', group: 'com.ctre.phoenix6', artifact: 'wpiapi-java', version: '26.50.0-alpha-1' }],
      // Its Selectable needs Elastic 2027 alpha 9 or newer (WPILib 2027 alpha 7 release notes).
      elastic: {
        url: 'https://github.com/Gold872/elastic_dashboard/releases/download/v2027.0.0-alpha9/Elastic-Web.zip',
        sha256: 'f9ae5ac67e11dfc6598fcbef83bebf9f56e40c0e81a1a87dc797768f9353cf38',
      },
      vscodeWpilibTag: `v${version}`,
      projectTemplates: /^(java|javaromi|javaxrp|shared)\/|^version\.txt$/,
      projectVendordeps: ['apriltag/AprilTag.json', 'cameraserver/CameraServer.json', 'commandsv2/CommandsV2.json', 'commandsv3/CommandsV3.json', 'romiVendordep/RomiVendordep.json', 'xrpVendordep/XRPVendordep.json'],
    };
  },
};

// Reads and writes class files in the vendor jar transform (packages/java/vendor).
const ASM = central('org.ow2.asm', 'asm', '9.10.1');

// What the JUnit launcher of `./gradlew test` (packages/java/junit) compiles against, and the Jupiter jars its desktop test runs.
// The browser runs it with the launcher of the project's own dependencies.
const JUNIT_PLATFORM = '1.10.1';
const JUNIT_JUPITER = '5.10.1';
const JUNIT = [
  central('org.junit.platform', 'junit-platform-launcher', JUNIT_PLATFORM),
  central('org.junit.platform', 'junit-platform-engine', JUNIT_PLATFORM),
  central('org.junit.platform', 'junit-platform-commons', JUNIT_PLATFORM),
  central('org.junit.jupiter', 'junit-jupiter-api', JUNIT_JUPITER),
  central('org.junit.jupiter', 'junit-jupiter-engine', JUNIT_JUPITER),
  central('org.junit.jupiter', 'junit-jupiter-params', JUNIT_JUPITER),
  central('org.opentest4j', 'opentest4j', '1.3.0'),
  central('org.apiguardian', 'apiguardian-api', '1.1.2'),
];

function mavenUrl({ repo, group, artifact, version, ext = 'jar' }, classifier) {
  const file = `${artifact}-${version}${classifier ? `-${classifier}` : ''}.${ext}`;
  return `${repo}/${group.replaceAll('.', '/')}/${artifact}/${version}/${file}`;
}

// A file the server does not have. Other failures are retried, then stop the run.
class Missing extends Error {}

async function get(url, read) {
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(url);
      if (res.ok) return await read(res);
      const error = `GET ${url}: ${res.status}`;
      throw res.status < 500 && res.status !== 429 ? new Missing(error) : new Error(error);
    } catch (err) {
      if (err instanceof Missing || attempt === 3) throw err;
      await new Promise((resolve) => setTimeout(resolve, 2000 * attempt));
    }
  }
}

async function download(url, dest, sha1) {
  if (fs.existsSync(dest)) return dest;
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const buf = await get(url, async (res) => Buffer.from(await res.arrayBuffer()));
  const checksum = sha1 ? await fetch(`${url}.sha1`) : undefined;
  if (checksum?.ok) {
    const expected = (await checksum.text()).trim().split(/\s+/)[0];
    const actual = createHash('sha1').update(buf).digest('hex');
    if (expected !== actual) throw new Error(`sha1 mismatch for ${url}`);
  }
  fs.writeFileSync(`${dest}.part`, buf);
  fs.renameSync(`${dest}.part`, dest);
  console.log(`fetched ${path.basename(dest)} (${(buf.length / 1e6).toFixed(1)} MB)`);
  return dest;
}

// A Temurin release such as jdk-17.0.20.1+1, for this machine.
function hostJdk(release) {
  const major = release.match(/^jdk-(\d+)/)[1];
  const os = { linux: 'linux', darwin: 'mac', win32: 'windows' }[process.platform];
  const arch = { x64: 'x64', arm64: 'aarch64' }[process.arch];
  if (!os || !arch) throw new Error(`no JDK ${major} build for ${process.platform}/${process.arch}`);
  const ver = release.slice(4).replace('+', '_');
  const ext = os === 'windows' ? 'zip' : 'tar.gz';
  const file = `OpenJDK${major}U-jdk_${arch}_${os}_hotspot_${ver}.${ext}`;
  const url = `https://github.com/adoptium/temurin${major}-binaries/releases/download/${encodeURIComponent(release)}/${file}`;
  return { url, file };
}

function findJavaHome(dir) {
  for (const candidate of [dir, path.join(dir, 'Contents', 'Home')]) {
    if (fs.existsSync(path.join(candidate, 'lib', 'modules'))) return candidate;
  }
  throw new Error(`no JDK found in ${dir}`);
}

// Each release unpacks to .cache/jdk/<release>, so seasons on different JDKs share the folder.
async function fetchJdk(release) {
  const jdkDir = path.join(CACHE, 'jdk');
  const marker = path.join(jdkDir, `${release}.done`);
  if (fs.existsSync(marker)) return findJavaHome(path.join(jdkDir, release));
  const { url, file } = hostJdk(release);
  const archive = await download(url, path.join(DOWNLOADS, file));
  const expected = (await (await fetch(`${url}.sha256.txt`)).text()).trim().split(/\s+/)[0];
  const actual = createHash('sha256').update(fs.readFileSync(archive)).digest('hex');
  if (expected !== actual) throw new Error(`sha256 mismatch for ${file}`);
  fs.rmSync(path.join(jdkDir, release), { recursive: true, force: true });
  fs.mkdirSync(jdkDir, { recursive: true });
  execFileSync('tar', ['-xf', archive, '-C', jdkDir], { stdio: 'inherit' });
  fs.writeFileSync(marker, '');
  return findJavaHome(path.join(jdkDir, release));
}

const coord = (d) => `${d.group}:${d.artifact}:${d.version}`;

// Downloads a jar and its compile/runtime dependencies into .cache/<year>/vendor/m2, and records them in the index.
// WPILib artifacts are skipped since the site already serves them.
async function mirror(season, dep, repos, index) {
  const key = coord(dep);
  if (season.provided.has(`${dep.group}:${dep.artifact}`)) return undefined;
  if (key in index) return key;
  index[key] = null;
  const file = path.basename(mavenUrl({ ...dep, repo: '' }));
  const rel = `m2/${dep.group.replaceAll('.', '/')}/${dep.artifact}/${dep.version}/${file}`;
  for (const repo of repos) {
    try {
      await download(mavenUrl({ ...dep, repo }), path.join(season.vendorRaw, rel), true);
    } catch (err) {
      if (err instanceof Missing) continue;
      throw err;
    }
    // A vendor repository can host a jar without its pom, so try the others for the pom.
    let pom;
    for (const source of [repo, ...repos.filter((r) => r !== repo)]) {
      const text = await get(mavenUrl({ ...dep, repo: source, ext: 'pom' }), (res) => res.text()).catch((err) => {
        if (err instanceof Missing) return '';
        throw err;
      });
      if (text.includes('<project')) {
        pom = text;
        break;
      }
    }
    const deps = [];
    for (const child of pom ? pomDependencies(pom) : []) {
      if (child.group.startsWith(season.group) || season.provided.has(`${child.group}:${child.artifact}`)) continue;
      if (!child.version || /[[\]()+,$]/.test(child.version)) {
        console.warn(`skipping ${coord(child)} needed by ${key}: no fixed version`);
        continue;
      }
      const childKey = await mirror(season, child, repos, index);
      if (childKey) deps.push(childKey);
    }
    index[key] = { jar: rel, deps };
    return key;
  }
  throw new Missing(`${key} not found in ${repos.join(', ')}`);
}

// Orders versions like 2026.1.2 < 2026.1.10, with prereleases (2026.1.1-rc-2) before the release.
function compareVersions(a, b) {
  const parse = (v) => {
    const [main, pre] = v.replace(/^v/, '').split(/-(.*)/);
    return { nums: main.split('.').map((n) => Number.parseInt(n, 10) || 0), pre };
  };
  const x = parse(a);
  const y = parse(b);
  for (let i = 0; i < Math.max(x.nums.length, y.nums.length); i++) {
    const d = (x.nums[i] ?? 0) - (y.nums[i] ?? 0);
    if (d) return d;
  }
  if (x.pre === y.pre) return 0;
  if (x.pre === undefined) return 1;
  if (y.pre === undefined) return -1;
  return x.pre < y.pre ? -1 : 1;
}

function fetchJson(url) {
  return get(url, (res) => res.json());
}

// Every Java vendordep version in the marketplace, so a project's pinned version
// resolves. A library's own jsonUrl can add a newer release for the same season.
async function vendordepList(season) {
  const manifest = await fetchJson(season.marketplace);
  const items = [];
  for (const entry of manifest) {
    if (!entry.languages?.includes('java')) continue;
    const cached = path.join(season.cache, 'vendordeps', path.basename(entry.path));
    if (!fs.existsSync(cached)) {
      fs.mkdirSync(path.dirname(cached), { recursive: true });
      fs.writeFileSync(cached, JSON.stringify(await fetchJson(new URL(entry.path, season.marketplace))));
    }
    items.push({ json: JSON.parse(fs.readFileSync(cached, 'utf8')), file: path.basename(entry.path), description: entry.description, website: entry.website });
  }
  const newest = new Map();
  for (const item of items) {
    const current = newest.get(item.json.uuid);
    if (!current || compareVersions(item.json.version, current.json.version) > 0) newest.set(item.json.uuid, item);
  }
  for (const item of [...newest.values()]) {
    const { json } = item;
    if (!json.jsonUrl) continue;
    const upstream = await fetchJson(json.jsonUrl).catch(() => undefined);
    const isRelease = !upstream?.version.includes('-');
    if (upstream?.uuid === json.uuid && (upstream.frcYear ?? upstream.wpilibYear) === (json.frcYear ?? json.wpilibYear) && isRelease && compareVersions(upstream.version, json.version) > 0) {
      const added = { ...item, json: upstream, file: item.file.replace(json.version, upstream.version) };
      items.push(added);
      newest.set(json.uuid, added);
    }
  }
  for (const item of items) item.newest = newest.get(item.json.uuid) === item;
  return items;
}

// The site serves only what it may redistribute: the vendordep descriptions, the WPILib vendordep jars
// and the annotation processors. Everything else is downloaded by the browser from the vendor.
async function fetchVendorLibraries(season) {
  const { runtime, vendorRaw } = season;
  const maven = {};
  const vendordeps = [];
  const newest = [];
  const vendorDir = path.join(runtime, 'vendor');
  fs.rmSync(vendorDir, { recursive: true, force: true });
  fs.mkdirSync(path.join(vendorDir, 'vendordeps'), { recursive: true });
  for (const { json, file: name, description, website, newest: isNewest } of await vendordepList(season)) {
    // The newest version of each library is what the vendor library manager offers.
    if (!isNewest) continue;
    const repos = [...json.mavenUrls.map((u) => u.replace(/\/$/, '')), CENTRAL];
    const java = [];
    try {
      for (const d of json.javaDependencies ?? []) {
        if (d.version === 'wpilib') continue;
        const key = await mirror(season, { group: d.groupId, artifact: d.artifactId, version: d.version }, repos, maven);
        if (key) java.push(key);
      }
    } catch (err) {
      if (!(err instanceof Missing)) throw err;
      console.warn(`skipping ${json.name} ${json.version}: ${err.message}`);
      continue;
    }
    const file = `vendor/vendordeps/${name}`;
    fs.writeFileSync(path.join(runtime, file), `${JSON.stringify(json, null, 2)}\n`);
    vendordeps.push({ file, name: json.name, version: json.version, uuid: json.uuid, description, website });
    newest.push({ name: json.name, java });
  }
  const older = [];
  for (const jar of season.olderVendorJars ?? []) older.push(await mirror(season, jar, [jar.repo, CENTRAL], maven));
  const bundled = {};
  const bundle = (key) => {
    const entry = maven[key];
    if (!entry || key in bundled) return;
    const dest = `bundled/${path.basename(entry.jar)}`;
    fs.mkdirSync(path.join(runtime, 'bundled'), { recursive: true });
    fs.copyFileSync(path.join(vendorRaw, entry.jar), path.join(runtime, dest));
    bundled[key] = { jar: dest, deps: entry.deps };
    entry.deps.forEach(bundle);
  };
  for (const jar of season.jars.filter((j) => j.optional)) bundled[coord(jar)] = { jar: `lib/${path.basename(mavenUrl(jar))}`, deps: [] };
  for (const jar of season.wpilibVendorJars) bundle(await mirror(season, jar, [jar.repo], maven));
  const processors = {};
  for (const p of season.processors) {
    processors[`${p.group}:${p.artifact}`] = await mirror(season, p, [p.repo, CENTRAL], maven);
    bundle(processors[`${p.group}:${p.artifact}`]);
  }
  for (const key of Object.keys(maven)) if (maven[key] === null) delete maven[key];
  // Dependencies of the libraries above, so a build offline can still find a library's own dependencies
  const transitive = Object.fromEntries(Object.entries(maven).filter(([key]) => !(key in bundled)).map(([key, { deps }]) => [key, deps]));
  fs.writeFileSync(path.join(vendorDir, 'index.json'), `${JSON.stringify({ vendordeps, processors, bundled, transitive }, null, 2)}\n`);
  fs.mkdirSync(vendorRaw, { recursive: true });
  fs.writeFileSync(path.join(vendorRaw, 'index.json'), `${JSON.stringify({ vendordeps: newest, older, maven }, null, 2)}\n`);
}

async function fetchProjectFiles(season) {
  const out = path.join(season.runtime, 'wpilib', 'project-files.zip');
  if (fs.existsSync(out)) return;
  const tag = season.vscodeWpilibTag;
  const headers = process.env.GITHUB_TOKEN ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {};
  const tree = await fetch(`https://api.github.com/repos/wpilibsuite/vscode-wpilib/git/trees/${tag}?recursive=1`, { headers });
  if (!tree.ok) throw new Error(`GitHub tree request failed: ${tree.status}`);
  const prefix = 'vscode-wpilib/resources/gradle/';
  const files = {};
  for (const entry of (await tree.json()).tree) {
    if (entry.type !== 'blob' || !entry.path.startsWith(prefix)) continue;
    const rel = entry.path.slice(prefix.length);
    if (!season.projectTemplates.test(rel)) continue;
    const res = await fetch(`https://raw.githubusercontent.com/wpilibsuite/vscode-wpilib/${tag}/${entry.path}`);
    if (!res.ok) throw new Error(`GET ${entry.path}: ${res.status}`);
    files[`gradle/${rel}`] = new Uint8Array(await res.arrayBuffer());
  }
  for (const file of season.projectVendordeps) {
    const res = await fetch(`https://raw.githubusercontent.com/wpilibsuite/allwpilib/v${season.version}/${file}`);
    if (!res.ok) throw new Error(`GET ${file}: ${res.status}`);
    files[`vendordeps/${path.basename(file)}`] = new Uint8Array(await res.arrayBuffer());
  }
  fs.writeFileSync(out, zipSync(files, { level: 6 }));
  console.log(`fetched project-files.zip (${Object.keys(files).length} files)`);
}

// Elastic is served from runtime/<year>/tools/elastic/, one release per season. Its page loads the NT4
// socket shim first, so it talks to the simulated robot inside this page.
const NT4_SHIM = path.join(ROOT, 'packages', 'runtime', 'src', 'nt4-socket-shim.js');

async function fetchElastic({ runtime, elastic }) {
  const dir = path.join(runtime, 'tools', 'elastic');
  if (fs.existsSync(path.join(dir, 'index.html'))) return;
  const archive = await download(elastic.url, path.join(DOWNLOADS, `elastic-${elastic.url.split('/').at(-2)}-${path.basename(elastic.url)}`));
  if (createHash('sha256').update(fs.readFileSync(archive)).digest('hex') !== elastic.sha256) {
    fs.rmSync(archive);
    throw new Error(`sha256 mismatch for ${elastic.url}`);
  }
  for (const [name, data] of Object.entries(unzipSync(fs.readFileSync(archive)))) {
    if (name.endsWith('/') || name.endsWith('.py')) continue;
    fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
    fs.writeFileSync(path.join(dir, name), data);
  }
  const index = path.join(dir, 'index.html');
  const html = fs.readFileSync(index, 'utf8');
  if (!html.includes('<head>')) throw new Error('elastic: index.html has no <head>');
  fs.writeFileSync(index, html.replace('<head>', '<head>\n  <script src="../../../nt4-socket-shim.js"></script>'));
  console.log(`unpacked ${path.basename(runtime)}/tools/elastic`);
}

async function fetchSeason(year) {
  if (!SEASONS[year]) throw new Error(`no fetch-deps entry for WPILib ${year}`);
  const config = SEASONS[year]();
  const cache = path.join(CACHE, year);
  const season = {
    ...config,
    runtime: path.join(RUNTIME, year),
    cache,
    // Vendor jars as published, for the Impl classes and the desktop tests. The site does not serve them:
    // the browser downloads them from the vendor and transforms them on the device (packages/runtime/src/vendor.ts).
    vendorRaw: path.join(cache, 'vendor'),
    // Already on every robot classpath; vendor copies of these would clash.
    provided: new Set(config.jars.filter((j) => !j.optional).map((j) => `${j.group}:${j.artifact}`)),
  };
  const { runtime } = season;
  fs.mkdirSync(path.join(runtime, 'lib'), { recursive: true });

  const classpath = [];
  const optional = [];
  for (const jar of season.jars) {
    const name = path.basename(mavenUrl(jar));
    await download(mavenUrl(jar), path.join(runtime, 'lib', name), true);
    if (!jar.optional) classpath.push(`lib/${name}`);
    else optional.push(`lib/${name}`);
    if (jar.sources) {
      await download(mavenUrl(jar, 'sources'), path.join(cache, 'sources', path.basename(mavenUrl(jar, 'sources'))), true);
    }
  }
  for (const zip of season.zips) {
    await download(mavenUrl(zip), path.join(runtime, 'wpilib', `${zip.artifact}.zip`), true);
  }
  if (season.fieldImages) await download(mavenUrl(season.fieldImages), path.join(runtime, 'wpilib', 'fieldImages.jar'), true);

  await fetchVendorLibraries(season);
  await fetchProjectFiles(season);
  await fetchElastic(season);
  for (const tool of season.swingTools ?? []) await download(tool.url, path.join(RUNTIME, 'tools', tool.dest), true);

  const javaHome = await fetchJdk(season.jdk);

  const provided = [...season.provided];
  fs.writeFileSync(path.join(runtime, 'manifest.json'), `${JSON.stringify({ wpilib: season.version, jdk: season.jdk, classpath, optional, provided }, null, 2)}\n`);
  fs.writeFileSync(path.join(cache, 'java-home'), javaHome);
  console.log(`WPILib ${season.version} with ${season.jdk} at ${javaHome}`);
}

function selectedYears() {
  const years = process.argv.flatMap((arg, i) => (process.argv[i - 1] === '--year' ? [arg] : []));
  for (const year of years) if (!SEASONS[year]) throw new Error(`WPILib ${year} is not one of ${Object.keys(SEASONS).join(', ')}`);
  return years.length ? years : WPILIB_YEARS;
}

await download(mavenUrl(ASM), path.join(CACHE, 'tools', path.basename(mavenUrl(ASM))), true);
fs.mkdirSync(RUNTIME, { recursive: true });
for (const jar of JUNIT) await download(mavenUrl(jar), path.join(CACHE, 'junit', path.basename(mavenUrl(jar))), true);
fs.copyFileSync(NT4_SHIM, path.join(RUNTIME, 'nt4-socket-shim.js'));
for (const year of selectedYears()) await fetchSeason(year);
