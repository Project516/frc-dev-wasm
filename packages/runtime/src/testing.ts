// What `./gradlew test` puts on the classpath of the tests. Also run by test/testing.mjs under Node, so no
// imports of the browser and no parameter properties.
import { unzipSync } from 'fflate';
import type { GradleTest } from './gradle.ts';
import type { Libraries } from './libraries.ts';
import type { VendorJar, VendorRequirement } from './vendor.ts';

const JUNIT_PLATFORM = 'org.junit.platform';
const LAUNCHER = 'junit-platform-launcher';
/** For a JUnit 4 project, which Gradle runs with JUnit 4's own runner: here the platform runs it through the vintage engine. */
const VINTAGE = { group: 'org.junit.vintage', artifact: 'junit-vintage-engine', version: '5.10.1', platform: '1.10.1' };

export interface TestJars {
  /** The jars to compile the tests against: the project's compile jars and the test configurations'. */
  compile: VendorJar[];
  /** The jars of the test JVM, with the JUnit launcher: the project's runtime jars and the test configurations'. */
  runtime: VendorJar[];
}

export interface TestResolver {
  /** The jars for the requirements and what they need. Throws when a required jar cannot be downloaded. */
  resolve(requirements: VendorRequirement[]): Promise<VendorJar[]>;
  /** The versions that a BOM manages, by `group:artifact`. */
  managedVersions(bom: VendorRequirement): Promise<Map<string, string>>;
}

const versionOf = (jar: VendorJar, artifact: string) => new RegExp(`^${artifact.replaceAll('.', '\\.')}-(\\d[^/]*)\\.jar$`).exec(jar.name)?.[1];

/**
 * The jars of the test compile and the test JVM. A version that the build.gradle leaves out comes from its
 * `platform(...)`. Failing that, a `junit-platform-*` library takes the version of the platform engine
 * that the other jars bring, as JUnit's BOM gives it in Gradle, and a project without a launcher gets one.
 */
export async function resolveTestJars(
  project: { vendor: VendorRequirement[]; libraries: Libraries },
  framework: GradleTest['framework'],
  resolver: TestResolver,
  warnings: string[],
): Promise<TestJars> {
  const { vendor, libraries } = project;
  if (framework === 'testng') throw new Error('Tests with TestNG are not supported in the browser. Use JUnit with useJUnitPlatform().');
  const managed = new Map<string, string>();
  for (const bom of libraries.testPlatforms) for (const [name, version] of await resolver.managedVersions(bom)) managed.set(name, version);

  const unversioned: VendorRequirement[] = [];
  const withVersions = (list: VendorRequirement[]) =>
    list.flatMap((req) => {
      const version = req.version || managed.get(`${req.group}:${req.artifact}`);
      if (version) return [{ ...req, version }];
      if (req.group === JUNIT_PLATFORM) unversioned.push(req);
      else warnings.push(`build.gradle: ${req.group}:${req.artifact} has no version and no platform gives one, so it is not used`);
      return [];
    });
  const testCompile = withVersions(libraries.testCompile);
  const testRuntime = withVersions(libraries.testRuntime);

  const first = await resolver.resolve([...vendor, ...libraries.runtime, ...testRuntime]);
  const engine = first.map((jar) => versionOf(jar, 'junit-platform-engine')).find(Boolean);
  const repos = [...new Set([...testRuntime, ...libraries.runtime].flatMap((req) => req.repos))];
  const extra: VendorRequirement[] = [];
  const add = (group: string, artifact: string, version: string) => {
    if (!extra.some((req) => req.group === group && req.artifact === artifact)) extra.push({ group, artifact, version, repos, plain: true });
  };
  let platform = engine;
  if (!engine && framework === 'junit' && first.some((jar) => versionOf(jar, 'junit'))) {
    add(VINTAGE.group, VINTAGE.artifact, VINTAGE.version);
    platform = VINTAGE.platform;
  } else if (!engine) {
    throw new Error(
      framework === 'junit-platform'
        ? "useJUnitPlatform() finds no JUnit engine. Add testImplementation 'org.junit.jupiter:junit-jupiter:<version>' to build.gradle."
        : "The tests have no JUnit on their classpath. Add testImplementation 'org.junit.jupiter:junit-jupiter:<version>' and useJUnitPlatform() to build.gradle.",
    );
  }
  for (const req of unversioned) add(req.group, req.artifact, platform!);
  if (!first.some((jar) => versionOf(jar, LAUNCHER))) add(JUNIT_PLATFORM, LAUNCHER, platform!);

  const all = [...vendor, ...libraries.runtime, ...testRuntime, ...extra];
  return {
    runtime: extra.length > 0 ? await resolver.resolve(all) : first,
    compile: await resolver.resolve([...vendor, ...libraries.compile, ...testCompile]),
  };
}

/** The classes a test run starts from: every named class, since JUnit decides which of them hold tests. */
export function testClassNames(jar: Uint8Array): string[] {
  const entries = unzipSync(jar, { filter: (file) => file.name.endsWith('.class') });
  return Object.keys(entries)
    .map((path) => path.slice(0, -'.class'.length).replaceAll('/', '.'))
    .filter((name) => !/\$\d/.test(name) && !/(^|\.)(module|package)-info$/.test(name))
    .sort();
}
