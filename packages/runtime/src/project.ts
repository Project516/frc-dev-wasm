// Reads a GradleRIO robot project the way its build.gradle would build it,
// for the parts that matter in the browser: sources, resources, deploy files,
// vendor libraries, maven dependencies, annotation processors and the main class. Gradle itself
// does not run here.
import { zipSync } from 'fflate';
import type { RuntimeAssets } from './assets.ts';
import { gradleRioVersion, gradleRioYear, matchesPattern, parseGradleBuild, parseProperties, referencedFiles, robotMainClass, sourceExcludes, stripComments, type GradleTest } from './gradle.ts';
import { generateGversionFile, gversionOptions, gversionPaths, type GitRunner } from './gversion.ts';
import { appPath } from './app-path.ts';
import { selectLibraries, type Libraries } from './libraries.ts';
import { CENTRAL, assertSafeCoordinate, loadVendorIndex, type VendorIndex, type VendorRequirement } from './vendor.ts';
import { WPILIB_YEARS } from './years.ts';

/** Read access to a project folder. Paths are relative to the project root and use `/`. */
export interface ProjectFiles {
  read(path: string): Promise<Uint8Array | undefined>;
  /** Every file below `dir`, as paths relative to the project root. Empty when `dir` does not exist. */
  list(dir: string): Promise<string[]>;
}

export interface RobotProject {
  name: string;
  mainClass: string;
  /** Java sources and resources, keyed by path relative to the source root. */
  sources: Record<string, Uint8Array>;
  /** CheerpJ paths of jars the site serves, such as the WPILib vendordeps. */
  classpath: string[];
  /** Vendor jars to download. See `VendorLibraries`. */
  vendor: VendorRequirement[];
  /** CheerpJ paths of annotation processor jars that the site serves. */
  processors: string[];
  /** Maven dependencies from the dependencies block of build.gradle, apart from what WPILib and the vendordeps provide. */
  libraries: Libraries;
  /** What the build.gradle sets for `./gradlew test`. */
  test: GradleTest;
  /** Files Gradle writes before compiling, such as the gversion class: path in the project to contents. Also in `sources`. */
  generated: Record<string, string>;
  /** Zip of `src/main/deploy`, when the project has one. */
  deploy?: Uint8Array;
  /** Problems that do not stop a build, such as a substituted vendor library version. */
  warnings: string[];
}

interface VendorDep {
  name: string;
  version: string;
  mavenUrls?: unknown;
  javaDependencies?: Array<{ groupId: string; artifactId: string; version: string }>;
}

// The processors `wpi.java.deps.wpilibAnnotations()` adds, by season (GradleRIO's WPIJavaDepsExtension)
const WPILIB_ANNOTATIONS: Record<string, string[]> = {
  '2026': ['edu.wpi.first.epilogue:epilogue-processor-java', 'edu.wpi.first.epilogue:epilogue-runtime-java'],
  '2027': [
    'io.avaje:avaje-jsonb-generator',
    'org.wpilib.epilogue:epilogue-processor-java',
    'org.wpilib.epilogue:epilogue-runtime-java',
    'org.wpilib:javac-plugin-java',
    'org.wpilib:annotations-java',
  ],
};

const decoder = new TextDecoder();
const encoder = new TextEncoder();

export interface AvailableVendorDep {
  name: string;
  version: string;
  uuid: string;
  description: string;
  /** The vendordep JSON file contents. */
  json(): Promise<string>;
}

/** The newest version of each vendor library in the WPILib marketplace, for the vendor library manager. */
export async function availableVendorDeps(assets: RuntimeAssets): Promise<AvailableVendorDep[]> {
  const index = await loadVendorIndex(assets);
  return index.vendordeps.map((v) => ({
    name: v.name,
    version: v.version,
    uuid: v.uuid,
    description: v.description ?? '',
    json: async () => (await fetch(assets.url(v.file))).text(),
  }));
}

/** The Java sources and resources of `src/test`, keyed by path relative to the source root. Empty when there are no Java tests. */
export async function loadTestSources(files: ProjectFiles): Promise<Record<string, Uint8Array>> {
  const sources: Record<string, Uint8Array> = {};
  for (const root of ['src/test/java/', 'src/test/resources/']) {
    for (const path of await files.list(root)) {
      if (root === 'src/test/java/' && !path.endsWith('.java')) continue;
      const data = await files.read(path);
      if (data) sources[path.slice(root.length)] = data;
    }
  }
  return Object.keys(sources).some((path) => path.endsWith('.java')) ? sources : {};
}

export async function isRobotProject(files: ProjectFiles): Promise<boolean> {
  const gradle = await files.read('build.gradle');
  return gradle !== undefined && /(edu\.wpi\.first|org\.wpilib)\.GradleRIO/.test(decoder.decode(gradle));
}

export interface ProjectYear {
  /** The season whose runtime builds and runs the project. */
  year: string;
  /** Set when a project older than every season the site serves builds with the oldest one. */
  warning?: string;
}

/** The WPILib season of a robot project, from its build.gradle. Undefined when it has no GradleRIO plugin. */
export async function projectYear(files: ProjectFiles): Promise<ProjectYear | undefined> {
  const gradle = await files.read('build.gradle');
  const year = gradle && gradleRioYear(decoder.decode(gradle));
  if (!year) return undefined;
  const oldest = WPILIB_YEARS[0];
  if (Number(year) < Number(oldest)) return { year: oldest, warning: `build.gradle uses GradleRIO ${year}, so the project builds with WPILib ${oldest}` };
  return { year };
}

export interface LoadOptions {
  /** Runs git in the project folder, for the values of generated version files. */
  git?: GitRunner;
}

export async function loadRobotProject(files: ProjectFiles, assets: RuntimeAssets, name: string, load: LoadOptions = {}): Promise<RobotProject> {
  const gradleBytes = await files.read('build.gradle');
  if (!gradleBytes) throw new Error('build.gradle not found');
  const gradle = stripComments(decoder.decode(gradleBytes));
  const warnings: string[] = [];

  const gradleRio = gradleRioVersion(gradle);
  const preRelease = gradleRio?.includes('-') || assets.wpilibVersion.includes('-');
  if (gradleRio && gradleRio !== assets.wpilibVersion && preRelease && gradleRioYear(gradle) === assets.year) {
    warnings.push(`build.gradle uses GradleRIO ${gradleRio}, but the project builds with WPILib ${assets.wpilibVersion}, the version this site serves. Pre-releases change their API between versions.`);
  }

  const mainClass = robotMainClass(gradle);
  if (!mainClass) throw new Error('build.gradle does not define ROBOT_MAIN_CLASS');

  const excludes = sourceExcludes(gradle);
  warnings.push(...excludes.warnings);
  const sources: Record<string, Uint8Array> = {};
  for (const root of ['src/main/java/', 'src/main/resources/']) {
    for (const path of await files.list(root)) {
      if (root === 'src/main/java/' && !path.endsWith('.java')) continue;
      if (root === 'src/main/java/' && excludes.patterns.some((pattern) => matchesPattern(path.slice(root.length), pattern))) continue;
      const data = await files.read(path);
      if (data) sources[path.slice(root.length)] = data;
    }
  }
  const generated: Record<string, string> = {};
  const version = gversionOptions(gradle);
  if (version && (version.hooked || !(await files.read(gversionPaths(version).projectPath)))) {
    const properties = parseProperties(decoder.decode((await files.read('gradle.properties')) ?? new Uint8Array()));
    const settings = decoder.decode((await files.read('settings.gradle')) ?? new Uint8Array());
    const top = (key: string) => new RegExp(`^${key}\\s*=?\\s*["']([^"']*)["']`, 'm').exec(gradle)?.[1];
    const project = {
      name: /rootProject\.name\s*=\s*["']([^"']*)["']/.exec(settings)?.[1] ?? name,
      group: top('group') ?? properties.get('group') ?? '',
      version: top('version') ?? properties.get('version') ?? 'unspecified',
    };
    const result = await generateGversionFile(version, project, load.git);
    warnings.push(...result.warnings);
    sources[result.file.path] = encoder.encode(result.file.source);
    generated[result.file.projectPath] = result.file.source;
  }

  const index = await loadVendorIndex(assets);
  const { classpath, vendor, repos, fixed } = await readVendorDeps(files, assets, index, warnings);
  const { libraries, test } = await readLibraries(files, gradle, assets, index, { repos, fixed }, warnings);

  const processors = new Set<string>();
  const annotations = /annotationProcessor\s+wpi\.java\.deps\.wpilibAnnotations\(\)/.test(gradle) ? (WPILIB_ANNOTATIONS[assets.year] ?? []) : [];
  const usesLombok = /io\.freefair\.lombok/.test(gradle);
  const wanted = (key: string) => annotations.includes(key) || gradle.includes(key) || (usesLombok && key === 'org.projectlombok:lombok');
  for (const key of [...annotations, ...Object.keys(index.processors)]) {
    if (!wanted(key)) continue;
    if (index.processors[key]) collectJars(index, assets, index.processors[key], processors);
    else if (assets.provided.has(key)) {
      // Jars on the classpath that a processor also loads from the processor path
      const [, artifact] = key.split(':');
      const jar = assets.wpilibClasspath.find((path) => path.endsWith(`/${artifact}-${assets.wpilibVersion}.jar`));
      if (jar) processors.add(jar);
    }
  }

  const deployFiles: Record<string, Uint8Array> = {};
  for (const path of await files.list('src/main/deploy/')) {
    const data = await files.read(path);
    if (data) deployFiles[path.slice('src/main/deploy/'.length)] = data;
  }
  const deploy = Object.keys(deployFiles).length ? zipSync(deployFiles, { level: 0 }) : undefined;

  return { name, mainClass, sources, generated, classpath, vendor, processors: [...processors], libraries, test, deploy, warnings };
}

// Only https repositories: the browser blocks plain http downloads for a page served over https, and the proxy takes https only.
function mavenRepositories(dep: VendorDep, path: string, warnings: string[]): string[] {
  const urls = Array.isArray(dep.mavenUrls) ? dep.mavenUrls : [];
  if (dep.mavenUrls !== undefined && !Array.isArray(dep.mavenUrls)) warnings.push(`${path}: mavenUrls is not a list, so it is ignored`);
  const repos: string[] = [];
  for (const url of urls) {
    if (typeof url !== 'string') warnings.push(`${path}: a mavenUrls entry is not text, so it is ignored`);
    else if (!url.startsWith('https://')) warnings.push(`${path}: ${url} is ignored because only https repositories can be used from the browser`);
    else repos.push(url.replace(/\/$/, ''));
  }
  return repos;
}

/** The jars that the project's vendordeps and its build.gradle need, without downloading anything. */
export async function loadVendorRequirements(
  files: ProjectFiles,
  assets: RuntimeAssets,
): Promise<{ vendor: VendorRequirement[]; libraries: Libraries; warnings: string[] }> {
  const warnings: string[] = [];
  const index = await loadVendorIndex(assets);
  const { vendor, repos, fixed } = await readVendorDeps(files, assets, index, warnings);
  const gradleBytes = await files.read('build.gradle');
  const gradle = gradleBytes ? stripComments(decoder.decode(gradleBytes)) : '';
  return { vendor, libraries: (await readLibraries(files, gradle, assets, index, { repos, fixed }, warnings)).libraries, warnings };
}

async function readLibraries(
  files: ProjectFiles,
  gradle: string,
  assets: RuntimeAssets,
  index: VendorIndex,
  vendordeps: { repos: string[]; fixed: Map<string, string> },
  warnings: string[],
): Promise<{ libraries: Libraries; test: GradleTest }> {
  const text = new Map<string, string>();
  const paths = ['gradle.properties', ...(await files.list('vendordeps/')).filter((p) => p.endsWith('.json')), ...referencedFiles(gradle)];
  for (const path of paths) {
    const data = await files.read(path);
    if (data) text.set(path, decoder.decode(data));
  }
  const build = parseGradleBuild(gradle, text);
  warnings.push(...build.warnings);
  const libraries = selectLibraries(
    build,
    { provided: assets.provided, fixed: vendordeps.fixed, bundled: new Set(Object.keys(index.processors)), vendorRepos: vendordeps.repos },
    warnings,
  );
  return { libraries, test: build.test };
}

async function readVendorDeps(
  files: ProjectFiles,
  assets: RuntimeAssets,
  index: VendorIndex,
  warnings: string[],
): Promise<{ classpath: string[]; vendor: VendorRequirement[]; repos: string[]; fixed: Map<string, string> }> {
  const classpath = new Set<string>();
  const vendor = new Map<string, VendorRequirement>();
  const allRepos = new Set<string>();
  const fixed = new Map<string, string>();
  for (const path of await files.list('vendordeps/')) {
    if (!path.endsWith('.json')) continue;
    const bytes = await files.read(path);
    if (!bytes) continue;
    let dep: VendorDep;
    try {
      dep = JSON.parse(decoder.decode(bytes));
    } catch {
      warnings.push(`${path} is not valid JSON`);
      continue;
    }
    const declared = mavenRepositories(dep, path, warnings);
    declared.forEach((repo) => allRepos.add(repo));
    const repos = [...declared, CENTRAL];
    for (const java of dep.javaDependencies ?? []) {
      assertSafeCoordinate({ group: java.groupId, artifact: java.artifactId, version: java.version === 'wpilib' ? '0' : java.version }, path);
      let version = java.version;
      if (version === 'wpilib') {
        // WPILibNewCommands is already on the WPILib classpath.
        if (java.groupId.startsWith('edu.wpi.first.wpilibNewCommands')) continue;
        version = assets.wpilibVersion;
      }
      if (assets.provided.has(`${java.groupId}:${java.artifactId}`)) continue;
      const exact = `${java.groupId}:${java.artifactId}:${version}`;
      const served = index.bundled[exact]
        ? exact
        : Object.keys(index.bundled).find((k) => k.startsWith(`${java.groupId}:${java.artifactId}:`) && index.bundled[k]);
      fixed.set(`${java.groupId}:${java.artifactId}`, served ? served.split(':')[2] : version);
      if (served) {
        if (served !== exact) warnings.push(`${dep.name}: ${java.artifactId} ${version} is not available here, using ${served.split(':')[2]}`);
        collectJars(index, assets, served, classpath);
      } else {
        vendor.set(exact, { group: java.groupId, artifact: java.artifactId, version, repos });
      }
    }
  }
  return { classpath: [...classpath], vendor: [...vendor.values()], repos: [...allRepos], fixed };
}

function collectJars(index: VendorIndex, assets: RuntimeAssets, coordinate: string | undefined, into: Set<string>): void {
  const entry = coordinate ? index.bundled[coordinate] : undefined;
  if (!entry) return;
  const path = appPath(assets.url(entry.jar));
  if (into.has(path)) return;
  into.add(path);
  for (const dep of entry.deps) collectJars(index, assets, dep, into);
}
