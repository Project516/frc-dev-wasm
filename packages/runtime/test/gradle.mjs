// build.gradle dependency and repository parsing. Usage: node test/gradle.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { gradleRioVersion, gradleRioYear, matchesPattern, parseGradleBuild, parseProperties, referencedFiles, simulationSettings, sourceExcludes } from '../src/gradle.ts';
import { resolveProjectJars, selectLibraries } from '../src/libraries.ts';

const parse = (gradle, files = {}) => parseGradleBuild(gradle, new Map(Object.entries(files)));
const coords = (build) => build.dependencies.map((d) => `${d.configuration} ${d.group}:${d.artifact}:${d.version}`);
const block = (lines) => `dependencies {\n${lines.join('\n')}\n}`;

test('reads every string and map form of a dependency', () => {
  const build = parse(
    block([
      `implementation 'org.apache.commons:commons-math3:3.6.1'`,
      `implementation("com.google.code.gson:gson:2.10.1")`,
      `api "org.ejml:ejml-core:0.43"`,
      `compileOnly group: 'org.projectlombok', name: 'lombok', version: '1.18.30'`,
      `runtimeOnly(group: 'org.slf4j', name: 'slf4j-simple', version: '2.0.9')`,
      `annotationProcessor 'org.acme:proc:1.0@jar'`,
      `implementation 'a.b:c:1', 'd.e:f:2'`,
    ]),
  );
  assert.deepEqual(coords(build), [
    'implementation org.apache.commons:commons-math3:3.6.1',
    'implementation com.google.code.gson:gson:2.10.1',
    'api org.ejml:ejml-core:0.43',
    'compileOnly org.projectlombok:lombok:1.18.30',
    'runtimeOnly org.slf4j:slf4j-simple:2.0.9',
    'annotationProcessor org.acme:proc:1.0',
    'implementation a.b:c:1',
    'implementation d.e:f:2',
  ]);
  assert.deepEqual(build.warnings, []);
});

test('resolves $var, ${var}, ${obj.version} and def, ext and gradle.properties variables', () => {
  const build = parse(
    `def gsonVersion = '2.10.1'
ext {
  jamaVersion = "1.0.3"
  versions = ['x': 1]
}
ext.mathVersion = '3.6.1'
${block([
      `implementation "com.google.code.gson:gson:$gsonVersion"`,
      `implementation "gov.nist.math:jama:\${jamaVersion}"`,
      `implementation "org.apache.commons:commons-math3:$mathVersion"`,
      `implementation "org.acme:fromprops:$acmeVersion"`,
      `implementation "org.acme:project-prop:\${project.findProperty('acmeVersion')}"`,
      `implementation 'org.acme:single-quoted:$gsonVersion'`,
      `implementation group: 'org.acme', name: 'map', version: gsonVersion`,
    ])}`,
    { 'gradle.properties': '# comment\nacmeVersion=4.5\norg.gradle.jvmargs=-Xmx1g\n' },
  );
  assert.deepEqual(coords(build), [
    'implementation com.google.code.gson:gson:2.10.1',
    'implementation gov.nist.math:jama:1.0.3',
    'implementation org.apache.commons:commons-math3:3.6.1',
    'implementation org.acme:fromprops:4.5',
    'implementation org.acme:project-prop:4.5',
    'implementation org.acme:map:2.10.1',
  ]);
  // Single quotes do not interpolate in Groovy
  assert.equal(build.warnings.length, 1);
  assert.match(build.warnings[0], /not a valid maven coordinate: implementation 'org.acme:single-quoted:\$gsonVersion'/);
});

test('reads the AdvantageKit version through JsonSlurper from a vendordep file', () => {
  const gradle = block([
    `def akitJson = new groovy.json.JsonSlurper().parseText(new File(projectDir.getAbsolutePath() + "/vendordeps/AdvantageKit.json").text)`,
    `annotationProcessor "org.littletonrobotics.akit:akit-autolog:$akitJson.version"`,
    `annotationProcessor "org.littletonrobotics.akit:akit-autolog:\${akitJson.version}"`,
  ]);
  const files = { 'vendordeps/AdvantageKit.json': '{"name":"AdvantageKit","version":"26.0.2"}' };
  const build = parse(gradle, files);
  assert.deepEqual(coords(build), [
    'annotationProcessor org.littletonrobotics.akit:akit-autolog:26.0.2',
    'annotationProcessor org.littletonrobotics.akit:akit-autolog:26.0.2',
  ]);
  assert.deepEqual(build.warnings, []);
  assert.deepEqual(referencedFiles(gradle), ['vendordeps/AdvantageKit.json']);
});

test('looks files up by name for other readers and other vendordeps', () => {
  const files = { 'vendordeps/Other.json': '{"version":"2.0","nested":{"id":"x"}}', 'libs/versions.json': '{"gson":"2.10.1"}' };
  const build = parse(
    `def a = new JsonSlurper().parseText(file('vendordeps/Other.json').text)
def b = new groovy.json.JsonSlurper().parseText(new File(rootDir, "libs/versions.json").getText())
def c = new groovy.json.JsonSlurper().parse(file("$projectDir/vendordeps/Other.json"))
${block([
      `implementation "org.acme:a:$a.version"`,
      `implementation "com.google.code.gson:gson:\${b.gson}"`,
      `implementation "org.acme:c:\${c.nested.id}"`,
      `implementation "org.acme:missing:\${new groovy.json.JsonSlurper().parseText(new File('nope.json').text).version}"`,
    ])}`,
    files,
  );
  assert.deepEqual(coords(build), ['implementation org.acme:a:2.0', 'implementation com.google.code.gson:gson:2.10.1', 'implementation org.acme:c:x']);
  assert.equal(build.warnings.length, 1);
  assert.match(build.warnings[0], /nope\.json not found/);
});

test('skips what GradleRIO provides, and reads the test configurations', () => {
  const build = parse(
    block([
      `annotationProcessor wpi.java.deps.wpilibAnnotations()`,
      `implementation wpi.java.deps.wpilib()`,
      `implementation wpi.java.vendor.java()`,
      `roborioDebug wpi.java.deps.wpilibJniDebug(wpi.platforms.roborio)`,
      `nativeRelease wpi.java.vendor.jniRelease(wpi.platforms.desktop)`,
      `simulationDebug wpi.sim.enableDebug()`,
      `testImplementation 'org.junit.jupiter:junit-jupiter:5.10.1'`,
      `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`,
      `testAnnotationProcessor 'org.acme:t:1'`,
    ]),
  );
  assert.deepEqual(coords(build), [
    'testImplementation org.junit.jupiter:junit-jupiter:5.10.1',
    'testRuntimeOnly org.junit.platform:junit-platform-launcher:',
    'testAnnotationProcessor org.acme:t:1',
  ]);
  assert.deepEqual(build.warnings, []);
});

test('turns what it cannot read into a warning that names the line', () => {
  const build = parse(
    block([
      `implementation project(':lib')`,
      `implementation fileTree(dir: 'libs', include: ['*.jar'])`,
      `implementation libs.guava`,
      `implementation 'org.acme:dynamic:1.+'`,
      `implementation 'org.acme:range:[1.0,2.0)'`,
      `implementation 'org.acme:noversion'`,
      `implementation 'org.acme:thing:1.0:sources'`,
      `implementation group: 'org.acme', name: 'cls', version: '1', classifier: 'all'`,
      `implementation "org.acme:var:$undefinedVersion"`,
      `implementation 'org.acme:xml:1.0@aar'`,
      `implementation 'bad coord/../x:y:1'`,
      `roborioDebug 'org.acme:odd:1'`,
      `implementation 'org.acme:fine:1'`,
    ]),
  );
  assert.deepEqual(coords(build), ['implementation org.acme:fine:1']);
  const messages = build.warnings.join('\n');
  for (const needle of [
    "project(':lib')",
    'fileTree',
    'unknown variable libs',
    '1.+',
    '[1.0,2.0)',
    'no version: implementation \'org.acme:noversion\'',
    'org.acme:thing:1.0:sources',
    'classifier',
    'unknown variable undefinedVersion',
    '@aar',
    'bad coord',
    'unknown configuration roborioDebug',
  ]) {
    assert.ok(messages.includes(needle), `${needle} in\n${messages}`);
  }
  assert.equal(build.warnings.length, 12);
});

test('handles continued lines, semicolons, nested closures and comments of strings', () => {
  const build = parse(
    `dependencies {
  implementation 'a.b:one:1' ; implementation 'a.b:two:2'
  implementation(
    'a.b:three:3'
  )
  implementation 'a.b:four:4', {
    exclude group: 'x', module: 'y'
  }
  implementation group: 'a.b',
      name: 'five',
      version: '5'
}`,
  );
  assert.deepEqual(coords(build), [
    'implementation a.b:one:1',
    'implementation a.b:two:2',
    'implementation a.b:three:3',
    'implementation a.b:four:4',
    'implementation a.b:five:5',
  ]);
});

test('reads repositories in order, with https only, and ignores plugin repositories', () => {
  const build = parse(
    `buildscript { repositories { maven { url 'https://plugins.example.test/m2' } } }
repositories {
  mavenCentral()
  mavenLocal()
  maven { url 'https://jitpack.io' }
  maven { url = uri("https://repo.acme.test/releases/") }
  maven {
    name = "acme"
    url "https://repo.other.test/maven"
    credentials { username 'x' }
  }
  maven("https://frcmaven.wpi.edu/artifactory/littletonrobotics-mvn-release")
  maven { url uri('https://www.jitpack.io') }
  maven { url 'http://insecure.example.test/repo' }
  maven { name 'no url' }
  jcenter()
  flatDir { dirs 'libs' }
}`,
  );
  assert.deepEqual(build.repositories, [
    'https://repo1.maven.org/maven2',
    'https://jitpack.io',
    'https://repo.acme.test/releases',
    'https://repo.other.test/maven',
    'https://frcmaven.wpi.edu/artifactory/littletonrobotics-mvn-release',
  ]);
  assert.equal(build.warnings.length, 4);
  assert.ok(build.warnings.some((w) => w.includes('only https')));
  assert.ok(build.warnings.some((w) => w.includes('without a url')));
  assert.ok(build.warnings.some((w) => w.includes('jcenter')));
  assert.ok(build.warnings.some((w) => w.includes('flatDir')));
});

test('parseProperties reads key=value, key: value and ignores comments', () => {
  assert.deepEqual([...parseProperties('# x\n! y\na=1\nb : two words \nc.d=3\n\n')], [['a', '1'], ['b', 'two words'], ['c.d', '3']]);
});

test('a real AdvantageKit template build.gradle', () => {
  const build = parse(
    `dependencies {
    annotationProcessor wpi.java.deps.wpilibAnnotations()
    implementation wpi.java.deps.wpilib()
    implementation wpi.java.vendor.java()

    roborioDebug wpi.java.deps.wpilibJniDebug(wpi.platforms.roborio)
    simulationDebug wpi.sim.enableDebug()
    nativeRelease wpi.java.vendor.jniRelease(wpi.platforms.desktop)

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.1'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'

    def akitJson = new groovy.json.JsonSlurper().parseText(new File(projectDir.getAbsolutePath() + "/vendordeps/AdvantageKit.json").text)
    annotationProcessor "org.littletonrobotics.akit:akit-autolog:$akitJson.version"
}

test {
    useJUnitPlatform()
    systemProperty 'junit.jupiter.extensions.autodetection.enabled', 'true'
}
jar {
    from { configurations.runtimeClasspath.collect { it.isDirectory() ? it : zipTree(it) } }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}`,
    { 'vendordeps/AdvantageKit.json': '{"version":"26.0.2"}' },
  );
  assert.deepEqual(coords(build), [
    'testImplementation org.junit.jupiter:junit-jupiter:5.10.1',
    'testRuntimeOnly org.junit.platform:junit-platform-launcher:',
    'annotationProcessor org.littletonrobotics.akit:akit-autolog:26.0.2',
  ]);
  assert.deepEqual(build.warnings, []);
});

const CONTEXT = {
  provided: new Set(['edu.wpi.first.wpilibj:wpilibj-java']),
  fixed: new Map([['org.littletonrobotics.akit:akit-java', '26.0.2']]),
  bundled: new Set(['org.projectlombok:lombok']),
  vendorRepos: ['https://frcmaven.wpi.edu/artifactory/littletonrobotics-mvn-release'],
};

test('selectLibraries sorts libraries by where they are used and keeps what WPILib and vendordeps provide', () => {
  const build = parse(
    `repositories { mavenCentral(); maven { url 'https://jitpack.io' } }
${block([
      `implementation 'org.apache.commons:commons-math3:3.6.1'`,
      `api 'gov.nist.math:jama:1.0.3'`,
      `compileOnly 'org.acme:annotations:1.0'`,
      `runtimeOnly 'org.acme:driver:2.0'`,
      `annotationProcessor 'org.littletonrobotics.akit:akit-autolog:26.0.2'`,
      `implementation 'edu.wpi.first.wpilibj:wpilibj-java:2025.1.1'`,
      `implementation 'org.littletonrobotics.akit:akit-java:4.0.0'`,
      `compileOnly 'org.projectlombok:lombok:1.18.30'`,
      `annotationProcessor 'org.projectlombok:lombok:1.18.30'`,
      `implementation 'org.apache.commons:commons-math3:3.0'`,
      `testImplementation 'org.junit.jupiter:junit-jupiter:5.10.1'`,
    ])}`,
  );
  const warnings = [];
  const libraries = selectLibraries(build, CONTEXT, warnings);
  const names = (list) => list.map((r) => `${r.group}:${r.artifact}:${r.version}`);
  // Both commons-math3 versions go to the resolver, which keeps the higher one
  assert.deepEqual(names(libraries.compile), ['org.apache.commons:commons-math3:3.6.1', 'gov.nist.math:jama:1.0.3', 'org.acme:annotations:1.0', 'org.apache.commons:commons-math3:3.0']);
  assert.deepEqual(names(libraries.runtime), ['org.apache.commons:commons-math3:3.6.1', 'gov.nist.math:jama:1.0.3', 'org.acme:driver:2.0', 'org.apache.commons:commons-math3:3.0']);
  assert.deepEqual(names(libraries.processors), ['org.littletonrobotics.akit:akit-autolog:26.0.2']);
  assert.deepEqual(names(libraries.testCompile), ['org.junit.jupiter:junit-jupiter:5.10.1']);
  assert.deepEqual(names(libraries.testRuntime), ['org.junit.jupiter:junit-jupiter:5.10.1']);
  assert.deepEqual(libraries.compile[0].repos, [
    'https://repo1.maven.org/maven2',
    'https://jitpack.io',
    'https://frcmaven.wpi.edu/artifactory/littletonrobotics-mvn-release',
    'https://frcmaven.wpi.edu/artifactory/release',
  ]);
  assert.ok(libraries.compile.every((r) => r.plain));
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /akit-java 4\.0\.0 is replaced by version 26\.0\.2/);
});

test('selectLibraries adds Maven Central and the GradleRIO repository when the project lists none', () => {
  const libraries = selectLibraries(parse(block([`implementation 'a.b:c:1'`])), { ...CONTEXT, vendorRepos: [] }, []);
  assert.deepEqual(libraries.compile[0].repos, ['https://repo1.maven.org/maven2', 'https://frcmaven.wpi.edu/artifactory/release']);
});

test('reads versions from map and list literals', () => {
  const build = parse(
    `ext {
  versions = [gson: '2.10.1', 'math': "3.6.1"]
}
${block([`implementation "com.google.code.gson:gson:$versions.gson"`, `implementation "org.apache.commons:commons-math3:\${versions['math']}"`])}`,
  );
  assert.deepEqual(coords(build), ['implementation com.google.code.gson:gson:2.10.1', 'implementation org.apache.commons:commons-math3:3.6.1']);
  assert.deepEqual(build.warnings, []);
});

test('resolveProjectJars resolves vendordeps first, and the robot classpath again only when it differs', async () => {
  const req = (artifact) => ({ group: 'g', artifact, version: '1', repos: [] });
  const calls = [];
  const resolve = async (requirements) => {
    calls.push(requirements.map((r) => r.artifact).join());
    return requirements.map((r) => ({ key: r.artifact, name: `${r.artifact}.jar`, data: async () => new Uint8Array() }));
  };
  const same = { vendor: [req('v')], libraries: { compile: [req('a')], runtime: [req('a')], processors: [req('p')], testCompile: [req('t')], testRuntime: [req('t')], testPlatforms: [] } };
  const jars = await resolveProjectJars(same, resolve);
  assert.deepEqual(calls, ['v,a', 'p']);
  assert.equal(jars.runtime, jars.compile);
  assert.deepEqual(jars.processors.map((j) => j.name), ['p.jar']);

  calls.length = 0;
  await resolveProjectJars({ vendor: [req('v')], libraries: { compile: [req('a'), req('c')], runtime: [req('a'), req('r')], processors: [], testCompile: [], testRuntime: [], testPlatforms: [] } }, resolve);
  assert.deepEqual(calls, ['v,a,c', 'v,a,r', '']);
});

test('simulationSettings reads the wpi.sim extensions and environment variables as GradleRIO names them', () => {
  const settings = simulationSettings(`
wpi.sim.addGui().defaultEnabled = true
wpi.sim.addDriverstation()
// wpi.sim.addWebsocketsServer().defaultEnabled = true
wpi.sim.envVar("HALSIMXRP_HOST", "192.168.42.1")
wpi.sim.addXRPClient().defaultEnabled = true
`);
  assert.deepEqual(settings, {
    extensions: [
      { name: 'Sim GUI', artifact: 'halsim_gui', defaultEnabled: true },
      { name: 'Use Real DriverStation', artifact: 'halsim_ds_socket', defaultEnabled: false },
      { name: 'Sim XRP Client', artifact: 'halsim_xrp', defaultEnabled: true },
    ],
    environment: { HALSIMXRP_HOST: '192.168.42.1' },
  });
});

test('reads the WPILib year of the GradleRIO plugin', () => {
  assert.equal(gradleRioYear(`plugins {\n  id "java"\n  id "edu.wpi.first.GradleRIO" version "2026.2.1"\n}`), '2026');
  assert.equal(gradleRioYear(`plugins {\n  id "org.wpilib.GradleRIO" version "2027.0.0-alpha-7"\n}`), '2027');
  assert.equal(gradleRioYear(`plugins { id('edu.wpi.first.GradleRIO') version('2025.3.2') }`), '2025');
  // A version kept elsewhere, such as settings.gradle
  assert.equal(gradleRioYear(`plugins { id "edu.wpi.first.GradleRIO" }`), '2026');
  assert.equal(gradleRioYear(`plugins { id "org.wpilib.GradleRIO" version gradleRioVersion }`), '2027');
  assert.equal(gradleRioYear(`// id "org.wpilib.GradleRIO" version "2027.0.0"\nplugins { id "edu.wpi.first.GradleRIO" version "2026.2.1" }`), '2026');
  assert.equal(gradleRioYear(`plugins { id "java" }`), undefined);
});

test('reads the GradleRIO version when it is written out', () => {
  assert.equal(gradleRioVersion(`plugins {\n  id "org.wpilib.GradleRIO" version "2027.0.0-alpha-6"\n}`), '2027.0.0-alpha-6');
  assert.equal(gradleRioVersion(`plugins { id('edu.wpi.first.GradleRIO') version('2026.2.1') }`), '2026.2.1');
  assert.equal(gradleRioVersion(`plugins { id "org.wpilib.GradleRIO" version gradleRioVersion }`), undefined);
});

test('reads platform() of the test configurations and the dependencies that leave their version to it', () => {
  const build = parse(
    block([
      `testImplementation platform('org.junit:junit-bom:5.10.2')`,
      `testImplementation(enforcedPlatform("org.acme:bom:$v"))`,
      `testImplementation 'org.junit.jupiter:junit-jupiter'`,
      `testRuntimeOnly group: 'org.junit.platform', name: 'junit-platform-launcher'`,
      `implementation platform('org.acme:bom:1')`,
    ]).replace('$v', '2'),
  );
  assert.deepEqual(
    build.dependencies.map((d) => `${d.configuration} ${d.group}:${d.artifact}:${d.version}${d.platform ? ' platform' : ''}`),
    [
      'testImplementation org.junit:junit-bom:5.10.2 platform',
      'testImplementation org.acme:bom:2 platform',
      'testImplementation org.junit.jupiter:junit-jupiter:',
      'testRuntimeOnly org.junit.platform:junit-platform-launcher:',
    ],
  );
  assert.equal(build.warnings.length, 1);
  assert.match(build.warnings[0], /platform\(\.\.\.\) is not supported/);
});

test('reads the test task: the framework and its system properties', () => {
  const settings = (gradle) => parse(gradle).test;
  assert.deepEqual(settings(`test { useJUnitPlatform() }`), { framework: 'junit-platform', systemProperties: {}, warnings: [] });
  assert.deepEqual(settings(`dependencies { }`), { framework: 'junit', systemProperties: {}, warnings: [] });
  assert.deepEqual(settings(`test { useJUnit() }`), { framework: 'junit', systemProperties: {}, warnings: [] });
  assert.equal(settings(`test { useTestNG() }`).framework, 'testng');
  assert.equal(settings(`tasks.named('test') { useJUnitPlatform { includeEngines 'x' } }`).framework, 'junit-platform');
  assert.equal(settings(`tasks.withType(Test) { useJUnitPlatform() }`).framework, 'junit-platform');
  assert.equal(settings(`tasks.test { useJUnitPlatform() }`).framework, 'junit-platform');
  assert.deepEqual(
    settings(`def mode = 'fast'
test {
  useJUnitPlatform()
  systemProperty 'junit.jupiter.extensions.autodetection.enabled', 'true'
  systemProperty("mode", mode)
  systemProperty 'port', 8080
  testLogging { events 'failed'; showStandardStreams = true }
}`),
    {
      framework: 'junit-platform',
      systemProperties: { 'junit.jupiter.extensions.autodetection.enabled': 'true', mode: 'fast', port: '8080' },
      warnings: ['build.gradle: test setting not applied: testLogging'],
    },
  );
  // The settings the browser does not apply are warnings for ./gradlew test, not for the build
  const build = parse(`test {
  useJUnitPlatform { excludeTags 'hardware' }
  ignoreFailures = true
  systemProperty 'port', unknownVariable
}`);
  assert.deepEqual(build.test.warnings, [
    "build.gradle: test setting not applied: excludeTags 'hardware'",
    'build.gradle: test setting not applied: ignoreFailures = true',
    "build.gradle: test setting not applied: systemProperty 'port', unknownVariable",
  ]);
  assert.deepEqual(build.warnings, []);
  // Another block that is named test is not the task
  assert.deepEqual(settings(`foo { test { useJUnitPlatform() } }\nbar { useJUnitPlatform() }`), { framework: 'junit', systemProperties: {}, warnings: [] });
});

test('selectLibraries sorts the test libraries, platforms included, and warns about the ones it does not use', () => {
  const build = parse(
    block([
      `testImplementation platform('org.junit:junit-bom:5.10.2')`,
      `testImplementation 'org.junit.jupiter:junit-jupiter'`,
      `testCompileOnly 'org.acme:annotations:1'`,
      `testRuntimeOnly 'org.acme:driver:2'`,
      `testAnnotationProcessor 'org.acme:proc:3'`,
    ]),
  );
  const warnings = [];
  const libraries = selectLibraries(build, CONTEXT, warnings);
  const names = (list) => list.map((r) => `${r.group}:${r.artifact}:${r.version}`);
  assert.deepEqual(names(libraries.testPlatforms), ['org.junit:junit-bom:5.10.2']);
  assert.deepEqual(names(libraries.testCompile), ['org.junit.jupiter:junit-jupiter:', 'org.acme:annotations:1']);
  assert.deepEqual(names(libraries.testRuntime), ['org.junit.jupiter:junit-jupiter:', 'org.acme:driver:2']);
  assert.deepEqual(warnings, ['build.gradle: testAnnotationProcessor org.acme:proc is not used']);
});

test("reads the main Java source set's excludes in each form, and warns about its other settings", () => {
  const forms = [
    `sourceSets.main.java {\n    exclude 'competition/simulation/MapleSimulator.java'\n    exclude('a/B.java', "c/**")\n}`,
    `sourceSets {\n  main {\n    java {\n      exclude 'competition/simulation/MapleSimulator.java'\n      exclude('a/B.java', "c/**")\n    }\n  }\n}`,
    `sourceSets.main {\n  java {\n    exclude 'competition/simulation/MapleSimulator.java'\n    exclude('a/B.java', "c/**")\n  }\n}`,
  ];
  for (const gradle of forms) {
    assert.deepEqual(sourceExcludes(gradle), { patterns: ['competition/simulation/MapleSimulator.java', 'a/B.java', 'c/**'], warnings: [] }, gradle);
  }
  assert.deepEqual(sourceExcludes(`sourceSets.main.java.exclude 'x/Y.java'`).patterns, ['x/Y.java']);
  assert.deepEqual(sourceExcludes(`sourceSets.main.java { srcDir 'gen' }\nsourceSets.test.java { exclude 'T.java' }`), {
    patterns: [],
    warnings: ["build.gradle: source set setting not applied: srcDir 'gen'"],
  });
});

test('matches source paths against Ant-style patterns as Gradle does', () => {
  assert.ok(matchesPattern('competition/simulation/MapleSimulator.java', 'competition/simulation/MapleSimulator.java'));
  assert.ok(!matchesPattern('competition/simulation/MapleSimulatorX.java', 'competition/simulation/MapleSimulator.java'));
  assert.ok(matchesPattern('a/b/C.java', '**/C.java'));
  assert.ok(matchesPattern('C.java', '**/C.java'));
  assert.ok(matchesPattern('sim/x/Y.java', 'sim/'));
  assert.ok(matchesPattern('sim/Maple.java', 'sim/*.java'));
  assert.ok(!matchesPattern('sim/x/Maple.java', 'sim/*.java'));
  assert.ok(matchesPattern('sim/x/Maple.java', 'sim/**'));
  assert.ok(matchesPattern('a/B1.java', 'a/B?.java'));
  assert.ok(!matchesPattern('a.B.java', 'a/B.java'));
});
