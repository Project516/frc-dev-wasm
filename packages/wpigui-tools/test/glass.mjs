// Loads the built Glass in headless Chromium against a simulated robot and
// checks that it renders, connects, and shows Field2d and Plot widgets. Needs
// `pnpm build:glass` and `pnpm fetch-deps` first. Coordinates assume Glass's
// default window layout at 1280x800. See harness.mjs for EXPLORE.
import { startHarness, year } from './harness.mjs';

const t = await startHarness();
const { page, check, shot, act } = t;

try {
  await t.open();
  await page.evaluate((season) => window.scenarios.glassStart(season), year);

  await page.frameLocator('#glass').locator('canvas#canvas').waitFor({ timeout: 60_000 });
  check('Glass canvas is created', true);

  const glass = page.frames().find((f) => f.url().includes('/tools/glass/'));
  await glass.waitForFunction(() => document.title.includes('Connected'), undefined, { timeout: 30_000 });
  check('Glass connects to the simulated robot', true, await glass.title());

  await page.waitForTimeout(2000);
  const baseline = await shot('glass-1-connected');

  if (!(await t.explore('glass'))) {
    // NetworkTables menu, SmartDashboard, Field
    const [fieldSteps, plotSteps] =
      year === '2027'
        ? [
            [[152, 8], [158, 131], [345, 131]],
            [
              // Get the Field window off the NT tree, then Plot menu, New Plot Window, move it,
              // Add plot, expand SmartDashboard, scroll to Sine, drag it in.
              ['drag', 260, 211, 60, 560],
              [211, 8], [247, 59],
              ['drag', 400, 70, 920, 398],
              [615, 427],
              [270, 415],
              ['wheel', 600, 430, 300],
              ['drag', 290, 437, 900, 600],
            ],
          ]
        : [
            [[167, 9], [170, 122], [346, 122]],
            [
              // Get the Field window off the NT tree, then Plot menu, New Plot Window,
              // move it, Add plot, expand SmartDashboard, scroll to Sine, drag it in.
              ['drag', 300, 209, 150, 480],
              [237, 9], [270, 54],
              ['drag', 500, 69, 1000, 420],
              [568, 448],
              [268, 398],
              ['wheel', 600, 430, 300],
              ['drag', 291, 440, 900, 620],
            ],
          ];
    for (const step of fieldSteps) await act(step);
    const field = await shot('glass-2-field');
    check('NT tree and Field2d window open from the menu', !field.equals(baseline));
    await page.waitForTimeout(1500);
    const later = await shot('glass-2-field-later');
    check('robot pose moves on the field', !later.equals(field));

    for (const step of plotSteps) await act(step);
    const plot = await shot('glass-3-plot');
    check('a plot window shows a value dragged from the NT tree', !plot.equals(later));

    // Settings reach IndexedDB shortly after a change and load again on start.
    await page.waitForTimeout(6000);
    await page.reload();
    await page.waitForFunction(() => 'scenarios' in window);
    await page.evaluate((season) => window.scenarios.glassStart(season), year);
    await page.frameLocator('#glass').locator('canvas#canvas').waitFor({ timeout: 60_000 });
    const reloaded = page.frames().find((f) => f.url().includes('/tools/glass/'));
    await reloaded.waitForFunction(() => document.title.includes('Connected'), undefined, { timeout: 30_000 });
    const files = await reloaded.evaluate(() => Module.FS.readdir('/settings/glass'));
    check('settings persist across a reload', files.some((f) => f.endsWith('.json')), files.join(','));
    await shot('glass-4-reloaded');
  }
} catch (error) {
  t.failures.push('exception');
  console.error(error);
} finally {
  await t.finish();
}
