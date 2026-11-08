// Creating and reading robot projects of each WPILib season under Node, from the files that
// `pnpm fetch-deps` wrote to apps/ide/public/runtime. A season without them is skipped. Usage: node test/project.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { unzipSync } from 'fflate';
import { gradleRioYear, robotMainClass } from '../src/gradle.ts';
import { generateProject, listProjectTemplates } from '../src/new-project.ts';
import { isRobotProject, loadRobotProject, projectYear } from '../src/project.ts';

const RUNTIME = path.resolve(import.meta.dirname, '../../../apps/ide/public/runtime');
const decoder = new TextDecoder();
const text = (bytes) => decoder.decode(bytes);

// What jvm.ts's appPath reads
globalThis.document = { baseURI: 'http://test/' };
globalThis.location = { origin: 'http://test' };
const realFetch = globalThis.fetch;
globalThis.fetch = async (url) => {
  const file = path.join(RUNTIME, new URL(url).pathname.replace(/^\/runtime\//, ''));
  return file.startsWith(RUNTIME) && fs.existsSync(file) ? new Response(fs.readFileSync(file)) : new Response('', { status: 404 });
};
test.after(() => (globalThis.fetch = realFetch));

function seasonAssets(year) {
  const manifest = JSON.parse(fs.readFileSync(path.join(RUNTIME, year, 'manifest.json'), 'utf8'));
  return { year, wpilibVersion: manifest.wpilib, wpilibClasspath: manifest.classpath.map((file) => `/app/runtime/${year}/${file}`), provided: new Set(manifest.provided), url: (file) => new URL(file, `http://test/runtime/${year}/`) };
}

function projectFiles(files) {
  return {
    read: async (p) => files[p],
    list: async (dir) => Object.keys(files).filter((p) => p.startsWith(dir)),
  };
}

const jarName = (p) => path.basename(p);
const EXTRA = { romi: 'RomiVendordep.json', xrp: 'XRPVendordep.json' };

// What each season's generator produces, from vscode-wpilib's generator.ts
const SEASONS = {
  '2026': {
    robotPackage: 'frc/robot',
    mainClass: (t) => (t.kind === 'template' ? `frc.robot.${t.mainclass}` : 'frc.robot.Main'),
    commands: () => 'WPILibNewCommands.json',
    processors: (v) => [`epilogue-processor-java-${v}.jar`, `epilogue-runtime-java-${v}.jar`],
  },
  '2027': {
    robotPackage: 'first/robot',
    mainClass: () => 'first.Main',
    commands: (t) => (t.commandversion === 3 ? 'CommandsV3.json' : 'CommandsV2.json'),
    processors: (v) => ['avaje-jsonb-generator-3.14.jar', `epilogue-processor-java-${v}.jar`, `epilogue-runtime-java-${v}.jar`, `javac-plugin-java-${v}.jar`, `annotations-java-${v}.jar`],
  },
};

for (const [year, season] of Object.entries(SEASONS)) {
  const ready = fs.existsSync(path.join(RUNTIME, year, 'wpilib/templates.zip')) && fs.existsSync(path.join(RUNTIME, year, 'vendor/index.json'));
  const skip = ready ? false : `run pnpm fetch-deps --year ${year}`;
  const assets = ready ? seasonAssets(year) : undefined;

  test(`${year}: every template and example makes a project that reads back`, { skip }, async () => {
    const templates = await listProjectTemplates(assets);
    assert.ok(templates.some((t) => t.kind === 'template' && t.id === 'timed'));
    assert.ok(templates.some((t) => t.kind === 'example'));
    for (const t of templates) {
      const label = `${t.kind} ${t.id}`;
      const files = await generateProject(assets, t, 1234);

      for (const [p, data] of Object.entries(files)) {
        if (!/\.(java|gradle)$/.test(p)) continue;
        const source = text(data);
        assert.doesNotMatch(source, /(?:org\.wpilib|edu\.wpi\.first\.wpilibj)\.(?:templates|examples)\b/, `${label}: ${p} still names the template package`);
        const pkg = /^package ([\w.]+);/m.exec(source)?.[1];
        if (pkg && /^src\/(main|test)\/java\//.test(p)) assert.equal(path.dirname(p).replace(/^src\/\w+\/java\/?/, ''), pkg.replaceAll('.', '/'), `${label}: ${p}`);
      }

      const gradle = text(files['build.gradle']);
      assert.doesNotMatch(gradle, /###/, label);
      assert.equal(gradleRioYear(gradle), year, label);
      assert.ok(await isRobotProject(projectFiles(files)), label);
      assert.equal(JSON.parse(text(files['.wpilib/wpilib_preferences.json'])).teamNumber, 1234, label);

      const mainClass = season.mainClass(t);
      assert.equal(robotMainClass(gradle), mainClass, label);
      const main = files[`src/main/java/${mainClass.replaceAll('.', '/')}.java`];
      assert.ok(main, `${label}: no source for ${mainClass}`);
      assert.match(text(main), /startRobot\(/, label);
      if (year === '2027') {
        const robot = `first.robot.${t.robotclass}`;
        assert.ok(text(main).includes(`${robot}::new`), `${label}: Main does not start ${robot}`);
        assert.ok(files[`src/main/java/${robot.replaceAll('.', '/')}.java`], `${label}: no source for ${robot}`);
      }

      const vendordeps = Object.keys(files).filter((p) => p.startsWith('vendordeps/')).map((p) => p.slice('vendordeps/'.length));
      assert.deepEqual(vendordeps.sort(), [season.commands(t), ...(t.extravendordeps ?? []).map((e) => EXTRA[e])].sort(), label);
      for (const name of vendordeps) JSON.parse(text(files[`vendordeps/${name}`]));
      assert.match(text(files['src/main/deploy/example.txt']), year === '2027' ? /Systemcore/ : /RoboRIO/, label);

      const project = await loadRobotProject(projectFiles(files), assets, 'robot');
      assert.equal(project.mainClass, mainClass, label);
      assert.ok(`${mainClass.replaceAll('.', '/')}.java` in project.sources, label);
      assert.deepEqual(project.warnings, [], label);
      assert.deepEqual(project.vendor, [], `${label}: everything it needs is served by the site`);
      assert.deepEqual(project.processors.map(jarName), season.processors(assets.wpilibVersion), label);
      const jars = project.classpath.map(jarName);
      for (const extra of t.extravendordeps ?? []) assert.ok(jars.some((j) => j.startsWith(`${extra}Vendordep-java-`)), `${label}: ${extra} jar`);
      if (year === '2027') assert.ok(jars.some((j) => j.startsWith(t.commandversion === 3 ? 'commandsv3-java-' : 'commandsv2-java-')), `${label}: commands jar`);
      assert.deepEqual(await projectYear(projectFiles(files)), { year }, label);
    }
  });

  test(`${year}: annotation processors come from wpilibAnnotations(), an explicit dependency or lombok`, { skip }, async () => {
    const version = assets.wpilibVersion;
    const index = JSON.parse(fs.readFileSync(path.join(RUNTIME, year, 'vendor/index.json'), 'utf8'));
    const [epilogue] = Object.keys(index.processors);
    const plugin = year === '2027' ? 'org.wpilib.GradleRIO' : 'edu.wpi.first.GradleRIO';
    const processors = async (dependencies, extra = '') => {
      const gradle = `plugins {\n  id "${plugin}" version "${version}"\n${extra}}\ndef ROBOT_MAIN_CLASS = "a.Main"\ndependencies {\n${dependencies}\n}\n`;
      const project = await loadRobotProject(projectFiles({ 'build.gradle': new TextEncoder().encode(gradle) }), assets, 'robot');
      assert.deepEqual(project.libraries.processors, []);
      return project.processors.map(jarName);
    };
    assert.deepEqual(await processors(''), []);
    assert.deepEqual(await processors('annotationProcessor wpi.java.deps.wpilibAnnotations()'), season.processors(version));
    assert.deepEqual(await processors(`annotationProcessor "${epilogue}:${version}"`), [`epilogue-processor-java-${version}.jar`]);
    assert.deepEqual(await processors(`compileOnly 'org.projectlombok:lombok:1.18.48'\nannotationProcessor 'org.projectlombok:lombok:1.18.48'`), ['lombok-1.18.48.jar']);
    assert.deepEqual(await processors('', '  id "io.freefair.lombok" version "8.6"\n'), ['lombok-1.18.48.jar']);
    // Explicit and implied together name each jar once
    assert.deepEqual(await processors(`annotationProcessor wpi.java.deps.wpilibAnnotations()\nannotationProcessor "${epilogue}:${version}"`), season.processors(version));
  });
}

test('2027: CameraServer, cscore, AprilTag and OpenCV are on the classpath only with their vendordeps', { skip: !fs.existsSync(path.join(RUNTIME, '2027/vendor/index.json')) && 'run pnpm fetch-deps --year 2027' }, async () => {
  const assets = seasonAssets('2027');
  const skeleton = unzipSync(fs.readFileSync(path.join(RUNTIME, '2027/wpilib/project-files.zip')));
  const gradle = new TextEncoder().encode(`plugins { id "org.wpilib.GradleRIO" version "${assets.wpilibVersion}" }\ndef ROBOT_MAIN_CLASS = "a.Main"\n`);
  const load = (vendordeps) => loadRobotProject(projectFiles({ 'build.gradle': gradle, ...Object.fromEntries(vendordeps.map((name) => [`vendordeps/${name}.json`, skeleton[`vendordeps/${name}.json`]])) }), assets, 'robot');
  const jars = (project) => project.classpath.map(jarName);
  assert.ok(!assets.wpilibClasspath.some((p) => /cscore|cameraserver|apriltag|opencv/.test(p)));
  assert.deepEqual(jars(await load([])), []);
  const camera = await load(['CameraServer']);
  assert.deepEqual(camera.vendor, []);
  assert.deepEqual(jars(camera).sort(), [`cameraserver-java-${assets.wpilibVersion}.jar`, `cscore-java-${assets.wpilibVersion}.jar`, 'opencv-java-2027-4.13.0-3.jar']);
  assert.deepEqual(jars(await load(['AprilTag'])).sort(), [`apriltag-java-${assets.wpilibVersion}.jar`, 'opencv-java-2027-4.13.0-3.jar']);
});

test('2027: a processor of the 2026 season is a library to download, not a bundled processor', { skip: !fs.existsSync(path.join(RUNTIME, '2027/vendor/index.json')) && 'run pnpm fetch-deps --year 2027' }, async () => {
  const gradle = `plugins { id "org.wpilib.GradleRIO" version "2027.0.0-alpha-7" }\ndef ROBOT_MAIN_CLASS = "a.Main"\ndependencies {\nannotationProcessor 'edu.wpi.first.epilogue:epilogue-processor-java:2026.2.1'\n}\n`;
  const project = await loadRobotProject(projectFiles({ 'build.gradle': new TextEncoder().encode(gradle) }), seasonAssets('2027'), 'robot');
  assert.deepEqual(project.processors, []);
  assert.deepEqual(project.libraries.processors.map((r) => r.group), ['edu.wpi.first.epilogue']);
});

test('2027: another pre-release of GradleRIO is a warning', { skip: !fs.existsSync(path.join(RUNTIME, '2027/vendor/index.json')) && 'run pnpm fetch-deps --year 2027' }, async () => {
  const assets = seasonAssets('2027');
  const load = (version) => loadRobotProject(projectFiles({ 'build.gradle': new TextEncoder().encode(`plugins { id "org.wpilib.GradleRIO" version "${version}" }\ndef ROBOT_MAIN_CLASS = "a.Main"\n`) }), assets, 'robot');
  const older = await load('2027.0.0-alpha-1');
  assert.ok(older.warnings.some((w) => w.includes('GradleRIO 2027.0.0-alpha-1') && w.includes(`WPILib ${assets.wpilibVersion}`)), older.warnings.join('\n'));
  assert.deepEqual((await load(assets.wpilibVersion)).warnings, []);
});

test('2027: the gversion class is generated into the sources and the project files', { skip: !fs.existsSync(path.join(RUNTIME, '2027/vendor/index.json')) && 'run pnpm fetch-deps --year 2027' }, async () => {
  const gradle = fs.readFileSync(new URL('./fixtures/gversion-hooked.build.gradle', import.meta.url));
  const files = {
    'build.gradle': gradle,
    'src/main/java/frc/Main.java': new TextEncoder().encode('package frc; public class Main {}'),
  };
  const git = async (args) => (args[0] === 'rev-parse' && args[1] === 'HEAD' ? { code: 0, out: 'abc123\n' } : undefined);
  const project = await loadRobotProject(projectFiles(files), seasonAssets('2027'), 'My Robot', { git });
  const source = text(project.sources['frc/robot/BuildConstants.java']);
  assert.match(source, /MAVEN_NAME = "My Robot";/);
  assert.match(source, /GIT_SHA = "abc123";/);
  assert.match(source, /DIRTY = -1;/);
  assert.deepEqual(Object.keys(project.generated), ['src/main/java/frc/robot/BuildConstants.java']);
  assert.equal(project.generated['src/main/java/frc/robot/BuildConstants.java'], source);

  // The build.gradle runs createVersionFile before compileJava, so a file in the project is rewritten
  files['src/main/java/frc/robot/BuildConstants.java'] = new TextEncoder().encode('stale');
  assert.match(text((await loadRobotProject(projectFiles(files), seasonAssets('2027'), 'robot')).sources['frc/robot/BuildConstants.java']), /GIT_SHA = "UNKNOWN"/);
  const unhooked = text(gradle).replace('project.compileJava.dependsOn(createVersionFile)', '');
  files['build.gradle'] = new TextEncoder().encode(unhooked);
  const kept = await loadRobotProject(projectFiles(files), seasonAssets('2027'), 'robot');
  assert.equal(text(kept.sources['frc/robot/BuildConstants.java']), 'stale');
  assert.deepEqual(kept.generated, {});
});

test('a file that the main source set excludes is not compiled', { skip: !fs.existsSync(path.join(RUNTIME, '2026/vendor/index.json')) && 'run pnpm fetch-deps --year 2026' }, async () => {
  const assets = seasonAssets('2026');
  const gradle = `plugins { id "edu.wpi.first.GradleRIO" version "${assets.wpilibVersion}" }\ndef ROBOT_MAIN_CLASS = "a.Main"\nsourceSets.main.java {\n  exclude 'sim/Maple.java'\n}\n`;
  const source = new TextEncoder().encode('class X {}\n');
  const project = await loadRobotProject(projectFiles({ 'build.gradle': new TextEncoder().encode(gradle), 'src/main/java/a/Main.java': source, 'src/main/java/sim/Maple.java': source, 'src/main/java/sim/Other.java': source }), assets, 'robot');
  assert.deepEqual(Object.keys(project.sources).filter((p) => p.endsWith('.java')).sort(), ['a/Main.java', 'sim/Other.java']);
});
