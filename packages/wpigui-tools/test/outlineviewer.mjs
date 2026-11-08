// Loads the built OutlineViewer against a simulated robot and checks that it
// connects and shows the robot's topics. Needs `pnpm build:outlineviewer` and
// `pnpm fetch-deps` first. See harness.mjs for EXPLORE.
import { startHarness, year } from './harness.mjs';

const t = await startHarness();
try {
  await t.open();
  const viewer = await t.startTool('outlineviewer');
  t.check('OutlineViewer canvas is created', true);

  await viewer.waitForFunction(() => document.title.includes('Connected'), undefined, { timeout: 30_000 });
  t.check('OutlineViewer connects to the simulated robot', true, await viewer.title());
  await t.page.waitForTimeout(2000);
  const baseline = await t.shot('outlineviewer-1-connected');

  if (!(await t.explore('outlineviewer'))) {
    // Expand SmartDashboard, then set Enabled to false with its dropdown.
    const [expand, dropdown, falseOption] = year === '2027' ? [[20, 222], [523, 247], [500, 270]] : [[18, 194], [515, 214], [485, 238]];
    await t.act(expand);
    const expanded = await t.shot('outlineviewer-2-expanded');
    t.check('the robot topics show in the table', !expanded.equals(baseline));

    await t.act(dropdown);
    await t.act(falseOption);
    await t.shot('outlineviewer-3-edited');
    const enabled = await t.page.evaluate(() => window.toRobot.filter((m) => m.data.name === '/SmartDashboard/Enabled').map((m) => m.data.value));
    t.check('editing a value reaches the robot', enabled.at(-1) === false, JSON.stringify(enabled));
  }
} catch (error) {
  t.failures.push('exception');
  console.error(error);
} finally {
  await t.finish();
}
