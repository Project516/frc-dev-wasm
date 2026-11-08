// Loads AdvantageScope Lite in an iframe against an in-page NT4 server with fake robot
// topics, then checks the connection, the topic list and the field assets.
// Needs `pnpm build:advantagescope`. Screenshots go to test-results/.
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright-core';
import { createServer } from 'vite';

const here = import.meta.dirname;
const results = path.join(here, '../test-results');
const shot = (name) => path.join(results, `${name}.png`);

const server = await createServer({
  root: path.resolve(here, '..'),
  publicDir: path.resolve(here, '../../../apps/ide/public'),
  logLevel: 'warn',
  server: { port: 0, host: '127.0.0.1' },
});
await server.listen();
const url = `${server.resolvedUrls.local[0]}test/`;

const browser = await chromium.launch();
fs.mkdirSync(results, { recursive: true });
let failed = false;
const check = (ok, message) => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${message}`);
  if (!ok) failed = true;
};

try {
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  const responses = [];
  page.on('response', (r) => responses.push({ status: r.status(), url: r.url() }));
  await page.goto(url);
  await page.waitForFunction(() => 'scenarios' in window);
  await page.evaluate(() => window.scenarios.advantagescope());

  const hub = () => page.frames().find((f) => f.url().endsWith('/www/hub.html'));
  while (!hub()) await page.waitForTimeout(250);
  const frame = hub();

  await frame.waitForFunction(() => window.log?.getFieldKeys().some((k) => k.endsWith('/SmartDashboard/Speed')), null, { timeout: 60000 });
  const keys = await frame.evaluate(() => window.log.getFieldKeys());
  for (const topic of ['/SmartDashboard/Speed', '/SmartDashboard/Mode', '/Robot/Pose']) {
    check(keys.includes(`NT:${topic}`), `topic ${topic} listed`);
  }
  for (const name of ['SmartDashboard', 'Robot']) {
    await frame.locator('.field-item', { hasText: name }).first().locator(':scope > *').first().click();
  }
  await page.waitForTimeout(500);
  await page.screenshot({ path: shot('advantagescope') });

  await frame.waitForFunction(() => window.log.getNumber('NT:/SmartDashboard/Speed', -Infinity, Infinity)?.values.length > 0, null, { timeout: 30000 });
  const values = await frame.evaluate(() => ({
    speed: window.log.getNumber('NT:/SmartDashboard/Speed', -Infinity, Infinity)?.values[0],
    mode: window.log.getString('NT:/SmartDashboard/Mode', -Infinity, Infinity)?.values[0],
    poseType: window.log.getStructuredType('NT:/Robot/Pose'),
  }));
  check(values.speed === 1.25, `double value ${values.speed}`);
  check(values.mode === 'teleop', `string value ${values.mode}`);
  check(values.poseType === 'Pose2d', `struct type ${values.poseType}`);

  const assets = await frame.evaluate(() => ({
    field2ds: window.assets.field2ds.map((f) => f.name),
    field3ds: window.assets.field3ds.map((f) => f.name),
    loadFailures: window.assets.loadFailures,
  }));
  check(assets.field2ds.includes('2026 Field'), `2D fields ${assets.field2ds}`);
  check(assets.field3ds.includes('2026 Field'), `3D fields ${assets.field3ds}`);
  check(assets.loadFailures.length === 0, 'no asset load failures');

  const tab = await frame.getByText('2D Field').first().boundingBox();
  await page.mouse.click(tab.x + tab.width / 2, tab.y + tab.height / 2);
  await page.waitForTimeout(3000);
  await page.screenshot({ path: shot('advantagescope-field') });
  check(responses.some((r) => r.status === 200 && /Field2d_2026FRCFieldV1\/image\.png$/.test(r.url)), '2026 field image loaded');
  const model = await page.evaluate(async () => (await fetch('/runtime/tools/advantagescope/assets/Field3d_2026FRCFieldV1/model.glb')).status);
  check(model === 200, `3D field model status ${model}`);

  const bad = responses.filter((r) => r.status >= 400);
  check(bad.length === 0, `no failed requests ${bad.map((r) => `${r.status} ${r.url}`).join(', ')}`);
  check(pageErrors.length === 0, `no page errors ${pageErrors.slice(0, 3).join(' | ')}`);
} catch (err) {
  console.log(`FAIL ${err.message}`);
  failed = true;
} finally {
  await browser.close();
  await server.close();
}
process.exit(failed ? 1 : 0);
