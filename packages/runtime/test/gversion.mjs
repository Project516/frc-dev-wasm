// The class that the gversion Gradle plugin writes, from build.gradle fixtures. Usage: node test/gversion.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { stripComments } from '../src/gradle.ts';
import { formatDate, generateGversionFile, gversionOptions, gversionPaths } from '../src/gversion.ts';

const fixture = (name) => stripComments(fs.readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8'));
const NOW = new Date('2026-10-19T16:30:45.123Z');

test('formats dates as SimpleDateFormat does', () => {
  const at = (pattern, zone, date = NOW) => formatDate(date, pattern, zone);
  assert.equal(at("yyyy-MM-dd'T'HH:mm:ss'Z'", 'UTC'), '2026-10-19T16:30:45Z');
  assert.equal(at('yyyy-MM-dd HH:mm:ss z', 'America/Denver'), '2026-10-19 10:30:45 MDT');
  assert.equal(at('yyyy-MM-dd HH:mm:ss z', 'America/Denver', new Date('2026-12-01T00:00:00Z')), '2026-11-30 17:00:00 MST');
  assert.equal(at('EEE, d MMM yyyy hh:mm a Z', 'Europe/Berlin'), 'Mon, 19 Oct 2026 06:30 PM +0200');
  assert.equal(at('EEEE MMMM dd yy HH:mm:ss.SSS XXX', 'Asia/Kolkata'), 'Monday October 19 26 22:00:45.123 +05:30');
  assert.equal(at("yyyy 'at' ''HH''", 'UTC'), "2026 at '16'");
  assert.equal(at('H X XX', 'UTC', new Date('2026-01-01T00:00:00Z')), '0 Z Z');
  assert.equal(at('zzzz', 'America/Denver'), 'Mountain Daylight Time');
  assert.equal(at('HH', 'No/Such_Zone'), '16');
  assert.throws(() => at('w', 'UTC'), /not supported/);
});

test('reads the gversion block of a build.gradle', () => {
  assert.deepEqual(gversionOptions(fixture('gversion-hooked.build.gradle')), {
    srcDir: 'src/main/java/',
    classPackage: 'frc.robot',
    className: 'BuildConstants',
    dateFormat: 'yyyy-MM-dd HH:mm:ss z',
    timeZone: 'America/Denver',
    indent: '  ',
    annotate: '',
    version: undefined,
    hooked: true,
  });
  const fileOptions = gversionOptions(fixture('gversion-file.build.gradle'));
  assert.equal(fileOptions.srcDir, 'src/main/java');
  assert.equal(fileOptions.className, 'BuildData');
  assert.equal(fileOptions.dateFormat, "yyyy-MM-dd'T'HH:mm:ss'Z'");
  assert.equal(fileOptions.indent, '\t');
  assert.deepEqual(gversionPaths(fileOptions), { path: 'frc/robot/util/BuildData.java', projectPath: 'src/main/java/frc/robot/util/BuildData.java' });
});

test('is absent without the plugin or its block', () => {
  assert.equal(gversionOptions('gversion { srcDir = "src/main/java" }'), undefined);
  assert.equal(gversionOptions(`plugins { id "com.peterabeles.gversion" version "1.10" }`), undefined);
});

test('keeps the file inside the project', () => {
  const block = (body) => gversionOptions(`plugins { id "com.peterabeles.gversion" version "1.10" }\ngversion {\n${body}\n}`);
  assert.ok(block('srcDir = "src/main/java"'));
  assert.equal(block('srcDir = "../../other/src"'), undefined);
  assert.equal(block('srcDir = "/workspace/other"'), undefined);
  assert.equal(block('srcDir = "src/main/java"\nclassName = "../../X"'), undefined);
  assert.equal(block('srcDir = "src/main/java"\nclassPackage = "frc..robot"'), undefined);
});

test('reads Groovy escapes in quoted values', () => {
  const options = gversionOptions(`plugins { id "com.peterabeles.gversion" version "1.10" }\ngversion {\nsrcDir = "src/main/java"\nindent = "\\t"\n}`);
  assert.equal(options.indent, '\t');
});

const GIT = {
  'rev-list': { code: 0, out: '412\n' },
  'rev-parse HEAD': { code: 0, out: '8679621fb2e5cabf98b95d4644f8889bf9f4ecd3\n' },
  'rev-parse --abbrev-ref': { code: 0, out: 'main\n' },
  show: { code: 0, out: '2026-10-19T06:06:09-05:00\n' },
  diff: { code: 1, out: '' },
};
const runner = async (args) => {
  const key = args[0] === 'rev-parse' ? (args[1] === 'HEAD' ? 'rev-parse HEAD' : 'rev-parse --abbrev-ref') : args[0];
  return GIT[key];
};

test('writes the class with the values of the repository', async () => {
  const options = gversionOptions(fixture('gversion-hooked.build.gradle'));
  const { file, warnings } = await generateGversionFile(options, { name: 'sample-robot', group: '', version: 'unspecified' }, runner, NOW);
  assert.deepEqual(warnings, []);
  assert.equal(file.path, 'frc/robot/BuildConstants.java');
  assert.equal(
    file.source,
    `package frc.robot;

/**
 * Automatically generated file containing build version information.
 */
public final class BuildConstants {
  public static final String MAVEN_GROUP = "";
  public static final String MAVEN_NAME = "sample-robot";
  public static final String VERSION = "unspecified";
  public static final int GIT_REVISION = 412;
  public static final String GIT_SHA = "8679621fb2e5cabf98b95d4644f8889bf9f4ecd3";
  public static final String GIT_DATE = "2026-10-19 05:06:09 MDT";
  public static final String GIT_BRANCH = "main";
  public static final String BUILD_DATE = "2026-10-19 10:30:45 MDT";
  public static final long BUILD_UNIX_TIME = ${NOW.getTime()}L;
  public static final int DIRTY = 1;

  private BuildConstants(){}
}
`,
  );
});

test('falls back as the plugin does without git', async () => {
  const options = gversionOptions(fixture('gversion-file.build.gradle'));
  const { file } = await generateGversionFile(options, { name: 'sample-robot-2', group: 'frc', version: '2.0' }, undefined, NOW);
  assert.match(file.source, /^package frc\.robot\.util;\n\n/);
  for (const line of [
    'public final class BuildData {',
    '\tpublic static final String MAVEN_GROUP = "frc";',
    '\tpublic static final String VERSION = "2.0";',
    '\tpublic static final int GIT_REVISION = -1;',
    '\tpublic static final String GIT_SHA = "UNKNOWN";',
    '\tpublic static final String GIT_DATE = "UNKNOWN";',
    '\tpublic static final String GIT_BRANCH = "UNKNOWN";',
    '\tpublic static final String BUILD_DATE = "2026-10-19T16:30:45Z";',
    '\tpublic static final int DIRTY = -1;',
  ]) assert.ok(file.source.includes(`${line}\n`), line);
});

test('a failing or empty git answer is the fallback', async () => {
  const options = gversionOptions(fixture('gversion-file.build.gradle'));
  const git = async (args) => (args[0] === 'rev-list' ? { code: 128, out: 'fatal: not a git repository' } : { code: 0, out: ' \n' });
  const { file } = await generateGversionFile(options, { name: 'p', group: '', version: 'unspecified' }, git, NOW);
  assert.match(file.source, /GIT_REVISION = -1;/);
  assert.match(file.source, /GIT_SHA = "UNKNOWN";/);
  assert.match(file.source, /DIRTY = 0;/);
});
