// Loads the built DataLogTool against an in-memory workspace. Opens a small log
// and the log a simulated robot wrote with DataLogManager, then exports CSV
// files back into the workspace. Needs `pnpm build:datalogtool`,
// `pnpm fetch-deps` and `pnpm build:java` first. See harness.mjs for EXPLORE.
import { startHarness, year } from './harness.mjs';
import { WpiLog } from './wpilog.mjs';

const log = new WpiLog();
const speed = log.start('/Test/Speed', 'double');
const mode = log.start('/Test/Mode', 'string');
log.double(speed, 1_000_000, 1.5);
log.string(mode, 1_000_000, 'auto');
log.double(speed, 2_000_000, 2.5);
const SAMPLE = '/workspace/Logs/sample.wpilog';

// The Output window in the default layout: Select Output Folder, then Export CSV.
const [SELECT_FOLDER, EXPORT_CSV] = year === '2027' ? [[447, 422], [421, 499]] : [[464, 426], [423, 489]];
// The 2027 Output window is too short to show Export CSV: drag its corner down.
const GROW_OUTPUT = year === '2027' ? ['drag', 910, 506, 910, 640] : undefined;

const t = await startHarness();
const workspaceText = (path) => t.page.evaluate((p) => {
  const data = window.workspace.files.get(p);
  return data ? new TextDecoder().decode(data) : null;
}, path);
async function waitForText(path) {
  for (let i = 0; i < 30; i++) {
    const text = await workspaceText(path);
    if (text) return text;
    await t.page.waitForTimeout(500);
  }
  return null;
}
const answer = (...paths) => t.page.evaluate((p) => window.workspace.answers.push(p), paths);

try {
  await t.open();
  const tool = await t.startTool('datalogtool', { [SAMPLE]: [...log.toBytes()] });
  t.check('DataLogTool canvas is created', true);
  await t.page.waitForTimeout(2000);
  const baseline = await t.shot('datalogtool-1-start');

  if (!(await t.explore('datalogtool'))) {
    await answer(SAMPLE);
    await t.act([58, 56]);
    const opened = await t.shot('datalogtool-2-opened');
    const picks = await t.page.evaluate(() => window.workspace.picks.map((p) => `${p.kind} ${p.filters.join('|')}`));
    t.check('Open File asks the workspace for a .wpilog', picks[0] === 'open DataLog Files|*.wpilog', picks.join(', '));
    t.check('the picked log is loaded into the tool', !opened.equals(baseline));
    t.check('the file is copied into MEMFS under /ws', await tool.evaluate((p) => Module.FS.analyzePath(`/ws${p}`).exists, SAMPLE));

    await answer('/workspace/Logs');
    await t.act(SELECT_FOLDER);
    if (GROW_OUTPUT) await t.act(GROW_OUTPUT);
    await t.act(EXPORT_CSV);
    const csv = await waitForText('/workspace/Logs/sample.csv');
    t.check('the export lands in the workspace as CSV', csv?.includes('"/Test/Speed",2.5') && csv.includes('"/Test/Mode","auto"'), JSON.stringify(csv));
    await t.shot('datalogtool-3-exported');

    // A log written by a simulated robot, which the IDE puts in the project.
    const sim = await t.page.evaluate(() => window.scenarios.robotDataLog());
    t.check('a simulated robot writes a DataLog into the workspace', sim.built && sim.saved.length === 1 && sim.saved[0].size > 100, JSON.stringify(sim));
    const robotLog = sim.saved[0].path;
    await answer(robotLog);
    await t.act([58, 56]);
    await t.act(EXPORT_CSV);
    const stem = robotLog.split('/').pop().replace('.wpilog', '');
    const robotCsv = await waitForText(`/workspace/Logs/${stem}.csv`);
    t.check('the robot log exports as CSV with its entries', robotCsv?.includes('"/Test/Counter",') && robotCsv.includes('sim log started'), robotCsv?.slice(0, 300));
    await t.shot('datalogtool-4-robot-log');
  }
} catch (error) {
  t.failures.push('exception');
  console.error(error);
} finally {
  await t.finish();
}
