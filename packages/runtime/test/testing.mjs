// The classpath of `./gradlew test`: versions from platforms, the launcher at the engine's version, JUnit 4.
// Usage: node test/testing.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { zipSync } from 'fflate';
import { emptyLibraries } from '../src/libraries.ts';
import { pomManagedVersions } from '../src/pom.ts';
import { loadTestSources } from '../src/project.ts';
import { resolveTestJars, testClassNames } from '../src/testing.ts';

const req = (group, artifact, version = '') => ({ group, artifact, version, repos: ['https://repo1.maven.org/maven2'], plain: true });
const jar = (name) => ({ key: name, name, data: async () => new Uint8Array() });

// The jars Maven Central's poms lead to for what the tests use
const GRAPH = {
  'org.junit.jupiter:junit-jupiter': (v, platform) => [`junit-jupiter-${v}`, `junit-jupiter-api-${v}`, `junit-jupiter-engine-${v}`, `junit-platform-engine-${platform}`],
  'junit:junit': (v) => [`junit-${v}`, 'hamcrest-core-1.3'],
  'org.junit.vintage:junit-vintage-engine': (v) => [`junit-vintage-engine-${v}`, 'junit-platform-engine-1.10.1'],
  'org.junit.platform:junit-platform-launcher': (v) => [`junit-platform-launcher-${v}`, `junit-platform-engine-${v}`],
};

function resolver({ boms = {}, platform = '1.10.1' } = {}) {
  const calls = [];
  return {
    calls,
    resolve: async (requirements) => {
      calls.push(requirements.map((r) => `${r.group}:${r.artifact}:${r.version}`));
      const names = new Set();
      for (const r of requirements) for (const name of GRAPH[`${r.group}:${r.artifact}`]?.(r.version, platform) ?? [`${r.artifact}-${r.version}`]) names.add(name);
      return [...names].map((n) => jar(`${n}.jar`));
    },
    managedVersions: async (bom) => new Map(Object.entries(boms[`${bom.group}:${bom.artifact}:${bom.version}`] ?? {})),
  };
}

const project = (libraries) => ({ vendor: [req('com.ctre', 'api-java', '1.0')], libraries: { ...emptyLibraries(), ...libraries } });
const names = (jars) => jars.map((j) => j.name);

test('the stock template: the launcher comes at the version of the platform engine', async () => {
  const r = resolver();
  const jupiter = req('org.junit.jupiter', 'junit-jupiter', '5.10.1');
  const launcher = req('org.junit.platform', 'junit-platform-launcher');
  const warnings = [];
  const jars = await resolveTestJars(project({ testCompile: [jupiter], testRuntime: [jupiter, launcher] }), 'junit-platform', r, warnings);
  assert.deepEqual(warnings, []);
  assert.ok(names(jars.runtime).includes('junit-platform-launcher-1.10.1.jar'));
  assert.ok(names(jars.runtime).includes('junit-jupiter-engine-5.10.1.jar'));
  assert.ok(names(jars.runtime).includes('api-java-1.0.jar'));
  assert.ok(!names(jars.compile).includes('junit-platform-launcher-1.10.1.jar'));
  assert.ok(names(jars.compile).includes('junit-jupiter-api-5.10.1.jar'));
  // Resolved once for the engine's version, again with the launcher, and for the compile
  assert.equal(r.calls.length, 3);
  assert.ok(r.calls[1].includes('org.junit.platform:junit-platform-launcher:1.10.1'));
  assert.ok(!r.calls[2].some((c) => c.includes('launcher')));
});

test('a project that declares no launcher gets one, and the engine decides the version', async () => {
  const r = resolver({ platform: '1.11.4' });
  const jupiter = req('org.junit.jupiter', 'junit-jupiter', '5.11.4');
  const jars = await resolveTestJars(project({ testCompile: [jupiter], testRuntime: [jupiter] }), 'junit-platform', r, []);
  assert.ok(names(jars.runtime).includes('junit-platform-launcher-1.11.4.jar'));
});

test('a launcher with its own version is left alone', async () => {
  const r = resolver();
  const jupiter = req('org.junit.jupiter', 'junit-jupiter', '5.10.1');
  const launcher = req('org.junit.platform', 'junit-platform-launcher', '1.10.1');
  await resolveTestJars(project({ testCompile: [jupiter], testRuntime: [jupiter, launcher] }), 'junit-platform', r, []);
  assert.equal(r.calls.length, 2);
});

test("a platform() gives the versions that a build.gradle leaves out, with the BOM's own", async () => {
  const r = resolver({
    boms: { 'org.junit:junit-bom:5.10.2': { 'org.junit.jupiter:junit-jupiter': '5.10.2', 'org.junit.platform:junit-platform-launcher': '1.10.2' } },
    platform: '1.10.2',
  });
  const jupiter = req('org.junit.jupiter', 'junit-jupiter');
  const launcher = req('org.junit.platform', 'junit-platform-launcher');
  const bom = req('org.junit', 'junit-bom', '5.10.2');
  const jars = await resolveTestJars(project({ testPlatforms: [bom], testCompile: [jupiter], testRuntime: [jupiter, launcher] }), 'junit-platform', r, []);
  assert.ok(names(jars.runtime).includes('junit-jupiter-5.10.2.jar'));
  assert.ok(names(jars.runtime).includes('junit-platform-launcher-1.10.2.jar'));
});

test('a library with no version and no platform is skipped with a warning', async () => {
  const r = resolver();
  const warnings = [];
  const jupiter = req('org.junit.jupiter', 'junit-jupiter', '5.10.1');
  await resolveTestJars(project({ testCompile: [jupiter, req('org.acme', 'lost')], testRuntime: [jupiter] }), 'junit-platform', r, warnings);
  assert.deepEqual(warnings, ['build.gradle: org.acme:lost has no version and no platform gives one, so it is not used']);
  assert.ok(r.calls.every((c) => !c.some((x) => x.includes('lost'))));
});

test('JUnit 4, which is what Gradle runs without useJUnitPlatform(), runs through the vintage engine', async () => {
  const r = resolver();
  const junit = req('junit', 'junit', '4.13.2');
  const jars = await resolveTestJars(project({ testCompile: [junit], testRuntime: [junit] }), 'junit', r, []);
  assert.ok(names(jars.runtime).includes('junit-vintage-engine-5.10.1.jar'));
  assert.ok(names(jars.runtime).includes('junit-platform-launcher-1.10.1.jar'));
  assert.ok(names(jars.runtime).includes('junit-4.13.2.jar'));
});

test('tests with nothing to run them say what to add', async () => {
  const jupiter = req('org.junit.jupiter', 'junit-jupiter', '5.10.1');
  await assert.rejects(resolveTestJars(project({}), 'junit-platform', resolver(), []), /useJUnitPlatform\(\) finds no JUnit engine/);
  await assert.rejects(resolveTestJars(project({}), 'junit', resolver(), []), /no JUnit on their classpath/);
  await assert.rejects(resolveTestJars(project({ testRuntime: [jupiter] }), 'testng', resolver(), []), /TestNG/);
});

test('testClassNames lists named classes and leaves out anonymous ones and module info', () => {
  const entry = new Uint8Array([1]);
  const jar = zipSync({
    'frc/robot/ExampleTest.class': entry,
    'frc/robot/ExampleTest$Inner.class': entry,
    'frc/robot/ExampleTest$1.class': entry,
    'frc/robot/ExampleTest$1Local.class': entry,
    'Plain.class': entry,
    'module-info.class': entry,
    'frc/robot/package-info.class': entry,
    'junit-platform.properties': entry,
  });
  assert.deepEqual(testClassNames(jar), ['Plain', 'frc.robot.ExampleTest', 'frc.robot.ExampleTest$Inner']);
});

const files = (tree) => ({ read: async (path) => tree[path], list: async (dir) => Object.keys(tree).filter((path) => path.startsWith(dir)) });
const bytes = (text) => new TextEncoder().encode(text);

test('loadTestSources reads the Java sources and resources of src/test, relative to their folders', async () => {
  const sources = await loadTestSources(
    files({
      'src/main/java/frc/robot/Robot.java': bytes('main'),
      'src/test/java/frc/robot/RobotTest.java': bytes('test'),
      'src/test/java/frc/robot/notes.txt': bytes('not java'),
      'src/test/resources/junit-platform.properties': bytes('x=1'),
    }),
  );
  assert.deepEqual(Object.keys(sources).sort(), ['frc/robot/RobotTest.java', 'junit-platform.properties']);
});

test('loadTestSources is empty when src/test has no Java file', async () => {
  assert.deepEqual(await loadTestSources(files({ 'src/test/resources/data.json': bytes('{}') })), {});
  assert.deepEqual(await loadTestSources(files({})), {});
});

test('pomManagedVersions reads a BOM, with properties and the versions of its parent', () => {
  const parent = `<project><dependencyManagement><dependencies>
    <dependency><groupId>org.acme</groupId><artifactId>from-parent</artifactId><version>1.2</version></dependency>
  </dependencies></dependencyManagement></project>`;
  const bom = `<project>
    <parent><groupId>org.acme</groupId><artifactId>parent</artifactId><version>9</version></parent>
    <groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>5.10.2</version>
    <properties><platform.version>1.10.2</platform.version></properties>
    <dependencyManagement><dependencies>
      <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>\${project.version}</version></dependency>
      <dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId><version>\${platform.version}</version></dependency>
      <!-- <dependency><groupId>old</groupId><artifactId>commented</artifactId><version>1</version></dependency> -->
    </dependencies></dependencyManagement>
  </project>`;
  assert.deepEqual(Object.fromEntries(pomManagedVersions(bom, [parent])), {
    'org.acme:from-parent': '1.2',
    'org.junit.jupiter:junit-jupiter': '5.10.2',
    'org.junit.platform:junit-platform-launcher': '1.10.2',
  });
});
