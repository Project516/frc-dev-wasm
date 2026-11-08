// Loads the built SysId against an in-memory workspace holding a log of the four
// SysId tests, loads it through the workspace file dialog, picks the entries
// and checks that the analysis shows plots. Needs `pnpm build:sysid`,
// `pnpm fetch-deps` and `pnpm build:java` first. See harness.mjs for EXPLORE.
import { startHarness, year } from './harness.mjs';
import { sysidLog } from './sysid-fixture.mjs';

const LOG = '/workspace/Logs/sysid.wpilog';

// Open button, the drags from the Log Loader to the Data Selector (test state, then the three
// signals) and the Load button, in the default layout at 1280x800.
const [OPEN, DRAGS, LOAD] =
  year === '2027'
    ? [[68, 63], [['drag', 60, 236, 60, 515], ['drag', 60, 194, 60, 559], ['drag', 60, 173, 60, 577], ['drag', 60, 215, 60, 595]], [31, 695]]
    : [[83, 61], [['drag', 80, 208, 100, 513], ['drag', 60, 174, 100, 553], ['drag', 60, 157, 100, 570], ['drag', 60, 191, 100, 587]], [29, 676]];

const t = await startHarness();
try {
  await t.open();
  const tool = await t.startTool('sysid', { [LOG]: [...sysidLog()] });
  t.check('SysId canvas is created', true);
  await t.page.waitForTimeout(2000);
  const baseline = await t.shot('sysid-1-start');

  if (!(await t.explore('sysid'))) {
    await t.act({ answer: [LOG] });
    await t.act(OPEN);
    const loaded = await t.shot('sysid-2-loaded');
    t.check('the log opens through the workspace file dialog', !loaded.equals(baseline));
    t.check('the file is copied into MEMFS under /ws', await tool.evaluate((p) => Module.FS.analyzePath(`/ws${p}`).exists, LOG));

    // Drag the test state and the three signals from the Log Loader to the Data Selector, then load.
    for (const step of DRAGS) await t.act(step);
    await t.act(LOAD);
    await t.page.waitForTimeout(2000);
    const analyzed = await t.shot('sysid-3-analyzed');
    // The plots are empty until the analysis fills them with colored lines.
    const plots = [690, 70, 590, 570];
    const before = await t.coloredPixels(loaded, plots);
    const after = await t.coloredPixels(analyzed, plots);
    t.check('the analysis plots the test runs', after > 3 * before, `${before} colored pixels before, ${after} after`);
  }
} catch (error) {
  t.failures.push('exception');
  console.error(error);
} finally {
  await t.finish();
}
