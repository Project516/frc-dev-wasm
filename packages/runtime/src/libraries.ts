// Also run by test/gradle.mjs under Node.
import type { GradleBuild } from './gradle.ts';
import { CENTRAL, FRCMAVEN_RELEASE, coordinate, type VendorJar, type VendorRequirement } from './vendor.ts';

/** The maven libraries of a build.gradle, by where they are used. Each is a requirement for `VendorLibraries`. */
export interface Libraries {
  /** implementation, api and compileOnly: on the compile classpath. */
  compile: VendorRequirement[];
  /** implementation, api and runtimeOnly: on the robot's classpath. */
  runtime: VendorRequirement[];
  /** annotationProcessor: on the processor path. */
  processors: VendorRequirement[];
  /** testImplementation and testCompileOnly: on the compile classpath of the tests, besides `compile`. Some have no version, see `resolveTestJars`. */
  testCompile: VendorRequirement[];
  /** testImplementation and testRuntimeOnly: on the test JVM's classpath, besides `runtime`. Some have no version. */
  testRuntime: VendorRequirement[];
  /** `platform(...)` of the test configurations: BOMs that supply versions. */
  testPlatforms: VendorRequirement[];
}

export interface LibraryContext {
  /** `group:artifact` of what WPILib puts on the classpath. */
  provided: ReadonlySet<string>;
  /** `group:artifact` to the version that WPILib or a vendordep puts on the classpath. These win over the build.gradle. */
  fixed: ReadonlyMap<string, string>;
  /** `group:artifact` of processors that the site serves itself, which are handled apart. */
  bundled: ReadonlySet<string>;
  /** Maven repositories of the vendordeps. */
  vendorRepos: string[];
}

export function emptyLibraries(): Libraries {
  return { compile: [], runtime: [], processors: [], testCompile: [], testRuntime: [], testPlatforms: [] };
}

/** Repositories to try, in order: those of the build.gradle and the vendordeps, then what GradleRIO adds, and Maven Central. */
export function libraryRepositories(declared: string[], vendorRepos: string[]): string[] {
  return [...new Set([...declared, ...vendorRepos, CENTRAL, FRCMAVEN_RELEASE])];
}

const TARGETS: Record<string, Array<keyof Libraries>> = {
  implementation: ['compile', 'runtime'],
  api: ['compile', 'runtime'],
  compileOnly: ['compile'],
  runtimeOnly: ['runtime'],
  annotationProcessor: ['processors'],
  testImplementation: ['testCompile', 'testRuntime'],
  testCompileOnly: ['testCompile'],
  testRuntimeOnly: ['testRuntime'],
};

export function selectLibraries(build: GradleBuild, context: LibraryContext, warnings: string[]): Libraries {
  const libraries = emptyLibraries();
  const repos = libraryRepositories(build.repositories, context.vendorRepos);
  const seen = new Set<string>();
  for (const dep of build.dependencies) {
    const name = `${dep.group}:${dep.artifact}`;
    if (context.provided.has(name) || context.bundled.has(name)) continue;
    const fixed = context.fixed.get(name);
    if (fixed !== undefined) {
      if (fixed !== dep.version && !dep.configuration.startsWith('test')) {
        warnings.push(`build.gradle: ${name} ${dep.version} is replaced by version ${fixed}, which a vendordep provides`);
      }
      continue;
    }
    const requirement: VendorRequirement = { group: dep.group, artifact: dep.artifact, version: dep.version, repos, plain: true };
    const targets: Array<keyof Libraries> = dep.platform ? ['testPlatforms'] : (TARGETS[dep.configuration] ?? []);
    if (targets.length === 0) warnings.push(`build.gradle: ${dep.configuration} ${name} is not used`);
    for (const target of targets) {
      // Two versions of one library both go on, and VendorLibraries.resolve keeps the highest
      const key = `${target}|${coordinate(requirement)}`;
      if (seen.has(key)) continue;
      seen.add(key);
      libraries[target].push(requirement);
    }
  }
  return libraries;
}

/** The jars of a project: vendordeps and libraries for the compile classpath and the robot, and the annotation processors. */
export async function resolveProjectJars(
  project: { vendor: VendorRequirement[]; libraries: Libraries },
  resolve: (requirements: VendorRequirement[]) => Promise<VendorJar[]>,
): Promise<{ compile: VendorJar[]; runtime: VendorJar[]; processors: VendorJar[] }> {
  const { vendor, libraries } = project;
  const compile = await resolve([...vendor, ...libraries.compile]);
  const same = libraries.compile.map(coordinate).join() === libraries.runtime.map(coordinate).join();
  // Vendordeps come first, so their versions win over what a library asks for
  const runtime = same ? compile : await resolve([...vendor, ...libraries.runtime]);
  return { compile, runtime, processors: await resolve(libraries.processors) };
}
