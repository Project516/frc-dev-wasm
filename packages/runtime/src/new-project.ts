// Creates robot projects the way the WPILib VS Code extension does
// (vscode-wpilib src/shared/generator.ts, generateCopyJava). Also run by test/project.mjs under Node.
import type { RuntimeAssets } from './assets.ts';
import { fetchZip } from './zip.ts';

export interface ProjectTemplate {
  kind: 'template' | 'example';
  /** Folder name in the WPILib templates or examples zip. */
  id: string;
  name: string;
  description: string;
  tags: string[];
  gradlebase: string;
  /** The Main class of a 2026 template. */
  mainclass?: string;
  /** The robot class of a 2027 template or example. */
  robotclass?: string;
  commandversion?: number;
  extravendordeps?: string[];
  hasunittests?: boolean;
}

// What the generator does differently by season. From 2027 every project gets one shared Main.java,
// and the commands library is a vendordep for the template's command version.
const LAYOUTS: Record<string, { packageFrom: RegExp; robotPackage: string; deployTarget: string; sharedMain: boolean }> = {
  '2026': { packageFrom: /edu\.wpi\.first\.wpilibj\.(?:examples|templates)\..+?(?=;|\.)/g, robotPackage: 'frc.robot', deployTarget: 'RoboRIO', sharedMain: false },
  '2027': { packageFrom: /org\.wpilib\.(?:examples|templates)\..+?(?=;|\.)/g, robotPackage: 'first.robot', deployTarget: 'Systemcore', sharedMain: true },
};
const deployHint = (target: string) => `Files placed in this directory will be deployed to the ${target} into the
'deploy' directory in the home folder. Use the 'Filesystem.getDeployDirectory' wpilib function
to get a proper path relative to the deploy directory.`;
const EXTRA_VENDORDEPS: Record<string, string> = { romi: 'RomiVendordep.json', xrp: 'XRPVendordep.json' };

const decoder = new TextDecoder();
const encoder = new TextEncoder();

export async function listProjectTemplates(assets: RuntimeAssets): Promise<ProjectTemplate[]> {
  const out: ProjectTemplate[] = [];
  for (const kind of ['template', 'example'] as const) {
    const zip = await fetchZip(assets.url(`wpilib/${kind}s.zip`));
    const index = JSON.parse(decoder.decode(zip[`${kind}s/${kind}s.json`])) as Array<Omit<ProjectTemplate, 'kind' | 'id'> & { foldername: string }>;
    for (const { foldername, ...rest } of index) {
      if (rest.gradlebase.startsWith('java')) out.push({ kind, id: foldername, ...rest });
    }
  }
  return out;
}

/** Returns the files of a new project, keyed by path relative to the project folder. */
export async function generateProject(assets: RuntimeAssets, template: ProjectTemplate, teamNumber: number): Promise<Record<string, Uint8Array>> {
  const files: Record<string, Uint8Array> = {};
  const zip = await fetchZip(assets.url(`wpilib/${template.kind}s.zip`));
  const skeleton = await fetchZip(assets.url('wpilib/project-files.zip'));

  const layout = LAYOUTS[assets.year];
  if (!layout) throw new Error(`Cannot create a WPILib ${assets.year} project`);
  const { robotPackage } = layout;
  const robotDir = robotPackage.replaceAll('.', '/');
  const copyCode = (prefix: string, into: string) => {
    for (const [path, data] of Object.entries(zip)) {
      if (!path.startsWith(prefix) || path.endsWith('/')) continue;
      const rel = path.slice(prefix.length);
      files[into + rel] = /\.(java|gradle)$/.test(rel) ? encoder.encode(decoder.decode(data).replace(layout.packageFrom, robotPackage)) : data;
    }
  };
  const robotClass = `${robotPackage}.${template.robotclass ?? template.mainclass}`;
  if (layout.sharedMain) {
    const main = decoder
      .decode(zip['Main.java'])
      .replace('package org.wpilib;', 'package first;')
      .replace('org.wpilib.templates.timed.Robot::new', `${robotClass}::new`);
    files['src/main/java/first/Main.java'] = encoder.encode(main);
  }
  copyCode(`${template.kind}s/${template.id}/`, `src/main/java/${robotDir}/`);
  if (template.hasunittests) copyCode(`${template.kind}s_test/${template.id}/`, `src/test/java/${robotDir}/`);

  const gradleVersion = decoder.decode(skeleton['gradle/version.txt']).trim();
  const mainClass = layout.sharedMain ? 'first.Main' : template.kind === 'template' ? robotClass : `${robotPackage}.Main`;
  for (const base of [`gradle/${template.gradlebase}/`, 'gradle/shared/']) {
    for (const [path, data] of Object.entries(skeleton)) {
      if (path.startsWith(base) && !path.endsWith('/')) files[path.slice(base.length)] = data;
    }
  }
  files['build.gradle'] = encoder.encode(
    decoder.decode(files['build.gradle']).replaceAll('###ROBOTCLASSREPLACE###', mainClass).replaceAll('###GRADLERIOREPLACE###', gradleVersion),
  );
  const prefsPath = '.wpilib/wpilib_preferences.json';
  const prefs = JSON.parse(decoder.decode(files[prefsPath]));
  prefs.teamNumber = teamNumber;
  files[prefsPath] = encoder.encode(`${JSON.stringify(prefs, null, 2)}\n`);

  files['src/main/deploy/example.txt'] = encoder.encode(deployHint(layout.deployTarget));
  const commands = layout.sharedMain ? (template.commandversion === 3 ? 'CommandsV3.json' : 'CommandsV2.json') : 'WPILibNewCommands.json';
  files[`vendordeps/${commands}`] = skeleton[`vendordeps/${commands}`];
  for (const extra of template.extravendordeps ?? []) {
    const name = EXTRA_VENDORDEPS[extra];
    if (name) files[`vendordeps/${name}`] = skeleton[`vendordeps/${name}`];
  }
  return files;
}
