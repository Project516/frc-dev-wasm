import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
import { createServer } from 'vite';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const results = join(root, 'test-results');
mkdirSync(results, { recursive: true });

const failures = [];
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failures.push(name);
}

const server = await createServer({ configFile: join(root, 'vite.config.ts'), server: { port: 5180, strictPort: false } });
await server.listen();
const url = server.resolvedUrls.local[0];

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const errors = [];
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
page.on('console', (m) => {
  if (m.type() === 'error') errors.push(`console: ${m.text()}`);
});

// A standard gamepad, held by the top window so every frame sees it.
await page.addInitScript(() => {
  navigator.getGamepads = () => [window.top.__pad ?? null];
});

const shot = (name) => page.screenshot({ path: join(results, `${name}.png`) });
const sent = () => page.evaluate(() => window.mock.sent);
const published = () => page.evaluate(() => window.mock.published);

async function until(predicate, label, timeout = 10_000) {
  for (const start = Date.now(); Date.now() - start < timeout; await page.waitForTimeout(100)) {
    if (await predicate()) return true;
  }
  check(label, false, 'timed out');
  return false;
}

try {
  await page.goto(url);
  const gui = page.frameLocator('#sim');

  await gui.getByText('No robot program is running').waitFor();
  check('empty state shown while stopped', true);
  await shot('01-stopped');

  await page.click('#start');
  await gui.getByText('Robot program: Running').waitFor();
  check('status shows running', true);

  // Theme variables reach the iframe.
  const themeVar = await gui.locator('html').evaluate((el) => el.style.getPropertyValue('--vscode-editor-background'));
  check('workbench theme variables copied into iframe', themeVar === '#1f1f1f', themeVar);

  // Driver station
  const teleop = gui.getByRole('button', { name: 'Teleoperated' });
  await teleop.click();
  await until(async () => (await sent()).some((m) => m.type === 'DriverStation'), 'DriverStation message sent');
  const ds = (await sent()).filter((m) => m.type === 'DriverStation' && '>enabled' in m.data).at(-1);
  check(
    'teleop sends enabled, ds, new_data',
    ds?.data['>enabled'] === true && ds.data['>ds'] === true && ds.data['>autonomous'] === false && ds.data['>test'] === false && ds.data['>new_data'] === true,
    JSON.stringify(ds?.data),
  );
  check('teleop button pressed', (await teleop.getAttribute('aria-pressed')) === 'true');

  await gui.getByRole('button', { name: 'Disconnected' }).click();
  const off = (await sent()).filter((m) => m.type === 'DriverStation' && '>enabled' in m.data).at(-1);
  check('disconnected clears ds and enabled', off.data['>ds'] === false && off.data['>enabled'] === false);

  // FMS
  await gui.getByLabel('Alliance station').selectOption('blue2');
  const station = (await sent()).filter((m) => m.type === 'DriverStation').at(-1);
  check('station sent', station.data['>station'] === 'blue2');
  await gui.getByLabel('Game specific message').fill('RBB');
  await gui.getByLabel('Game specific message').press('Enter');
  const gsm = (await sent()).filter((m) => m.type === 'DriverStation').at(-1);
  check('game data sent', gsm.data['>game_data'] === 'RBB');

  // Devices
  await gui.getByLabel('Analog input 0 volts').fill('3.3');
  await gui.getByLabel('Analog input 0 volts').press('Enter');
  const ai = (await sent()).filter((m) => m.type === 'AI').at(-1);
  check('analog input edit sent', ai?.data['>voltage'] === 3.3, JSON.stringify(ai?.data));
  await gui.getByText('PWM[0]').waitFor();
  check('PWM panel renders', true);
  await gui.getByText('Elevator[1]').waitFor();
  check('SimDevice renders', true);
  await gui.getByLabel('limitSwitch').or(gui.getByText('limitSwitch')).first().waitFor();
  const dioBox = gui.locator('.row', { hasText: 'DIO[0]' }).getByRole('checkbox');
  await dioBox.check();
  const dio = (await sent()).filter((m) => m.type === 'DIO').at(-1);
  check('DIO input edit sent', dio?.data['<>value'] === true);

  // NetworkTables
  const tree = gui.getByRole('tree', { name: 'NetworkTables topics' });
  await tree.waitFor();
  await tree.getByRole('treeitem', { name: /SmartDashboard/ }).first().click();
  const counter = tree.getByRole('treeitem').filter({ hasText: 'Counter' }).first();
  await counter.waitFor();
  const first = await counter.locator('input').inputValue();
  await page.waitForTimeout(600);
  const second = await counter.locator('input').inputValue();
  check('NT numbers update live', Number(second) > Number(first), `${first} -> ${second}`);
  await tree.getByLabel('/SmartDashboard/Enabled flag').check();
  check(
    'NT boolean edit published',
    (await published()).some((p) => p.name === '/SmartDashboard/Enabled flag' && p.value === true),
  );
  await tree.getByLabel('/SmartDashboard/Auto name').fill('Three piece');
  await tree.getByLabel('/SmartDashboard/Auto name').press('Enter');
  check('NT string edit published', (await published()).some((p) => p.value === 'Three piece'));
  await tree.getByLabel('/SmartDashboard/Gains').fill('1, 2, 3');
  await tree.getByLabel('/SmartDashboard/Gains').press('Enter');
  check('NT array edit published', (await published()).some((p) => Array.isArray(p.value) && p.value.join() === '1,2,3'));

  // Rendering is throttled while 300 topics change every 40 ms.
  const renders = await gui.locator('.nt-tree').evaluate(
    (el) =>
      new Promise((resolve) => {
        let n = 0;
        const observer = new MutationObserver(() => n++);
        observer.observe(el, { subtree: true, childList: true, attributes: true, characterData: true });
        setTimeout(() => {
          observer.disconnect();
          resolve(n);
        }, 1000);
      }),
  );
  check('NT view renders at most about 10 Hz', renders <= 40, `${renders} mutation batches per second`);

  // Field
  const canvas = gui.locator('canvas.field-canvas').first();
  await canvas.waitFor({ state: 'visible', timeout: 20_000 });
  check('Field2d canvas shown', true);
  await until(async () => (await gui.getByText(/Robot/).count()) > 0, 'field legend lists Robot');

  // Joysticks: Keyboard 0 to slot 0, hold D.
  await gui.getByLabel('Joystick 0', { exact: true }).selectOption({ label: '16: Keyboard 0' });
  await gui.getByRole('button', { name: 'Teleoperated' }).click();
  await page.keyboard.down('KeyD');
  await until(
    async () => (await sent()).some((m) => m.type === 'Joystick' && m.device === '0' && m.data['>axes']?.[0] > 0.1),
    'keyboard joystick axis sent',
  );
  await page.keyboard.up('KeyD');
  await page.keyboard.down('KeyZ');
  await until(async () => (await sent()).some((m) => m.type === 'Joystick' && m.data['>buttons']?.[0] === true), 'keyboard joystick button sent');
  await page.keyboard.up('KeyZ');
  await page.waitForTimeout(100);
  const before = (await sent()).length;
  await page.waitForTimeout(500);
  const idle = (await sent()).slice(before).filter((m) => m.type === 'Joystick');
  check('joystick messages stop when values stop changing', idle.length === 0, `${idle.length} extra`);

  // Timing
  await gui.getByRole('button', { name: 'Pause' }).click();
  check('pause sent', (await sent()).some((m) => m.type === 'sim.timing' && m.data.op === 'pause'));
  await gui.getByRole('button', { name: 'Step' }).press('Enter');
  check('step sent', (await sent()).some((m) => m.type === 'sim.timing' && m.data.op === 'step' && m.data.us === 20000));
  await gui.getByRole('button', { name: 'Run' }).click();
  check('resume sent', (await sent()).some((m) => m.type === 'sim.timing' && m.data.op === 'resume'));

  // Screenshots
  await page.setViewportSize({ width: 1280, height: 1900 });
  await page.waitForTimeout(700);
  await shot('02-running-dark');
  await page.click('#light');
  await page.waitForTimeout(400);
  const lightBg = await gui.locator('body').evaluate((el) => getComputedStyle(el).backgroundColor);
  check('theme change reaches iframe', lightBg === 'rgb(255, 255, 255)', lightBg);
  await shot('03-running-light');
  await page.click('#dark');
  await page.setViewportSize({ width: 700, height: 1500 });
  await page.waitForTimeout(500);
  await shot('04-narrow');

  await page.click('#stop');
  await gui.getByText('No robot program is running').waitFor();
  check('empty state returns after stop', true);
  await page.click('#start');
  await gui.getByText('Robot program: Running').waitFor();

  // Gamepad with "Map gamepad"
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.evaluate(() => {
    window.__pad = {
      id: 'Xbox 360 Controller (XInput STANDARD GAMEPAD)',
      index: 0,
      mapping: 'standard',
      axes: [0.5, -0.25, 0.1, 0.2],
      buttons: Array.from({ length: 17 }, (_, i) => ({ pressed: i === 0 || i === 7 || i === 12, value: i === 7 ? 0.8 : i === 0 || i === 12 ? 1 : 0 })),
    };
  });
  await gui.getByText('0: Xbox 360 Controller').first().waitFor();
  await gui.getByLabel('Joystick 1', { exact: true }).selectOption({ index: 1 });
  const padMessage = async () => (await sent()).filter((m) => m.type === 'Joystick' && m.device === '1').at(-1);
  await until(async () => (await padMessage())?.data['>axes']?.length === 6, 'gamepad mapped to six axes');
  const pad = (await padMessage()).data;
  check('gamepad axes use the Xbox order', JSON.stringify(pad['>axes']) === JSON.stringify([0.5, -0.25, 0, 0.8, 0.1, 0.2]), JSON.stringify(pad['>axes']));
  check('gamepad buttons use the Xbox order', pad['>buttons'].length === 10 && pad['>buttons'][0] === true && pad['>buttons'][1] === false);
  check('gamepad d-pad becomes a POV', JSON.stringify(pad['>povs']) === '[0]', JSON.stringify(pad['>povs']));
  await gui.getByLabel('Map gamepad').uncheck();
  await until(async () => (await padMessage())?.data['>axes']?.length === 4, 'unmapped gamepad sends raw layout');
  check('unmapped gamepad has raw buttons and no POV', (await padMessage()).data['>buttons'].length === 17 && (await padMessage()).data['>povs'].length === 0);
  await page.evaluate(() => (window.__pad = null));
  await until(async () => (await padMessage())?.data['>axes']?.length === 0, 'unplugged gamepad sends empty data');

  // Drag and drop assignment, keyboard rebinding
  await gui.locator('.list-row', { hasText: '17: Keyboard 1' }).dragTo(gui.locator('.slot', { hasText: 'Joystick 2' }));
  check('drag and drop assigns a joystick', (await gui.getByLabel('Joystick 2', { exact: true }).inputValue()) === 'Keyboard1');
  await gui.locator('.list-row', { hasText: '16: Keyboard 0' }).getByRole('button', { name: 'Settings' }).click();
  await gui.getByRole('button', { name: /Bind 0 axis 0 increase/ }).click();
  await page.keyboard.press('KeyH');
  check('key rebinding shows the new key', (await gui.getByRole('button', { name: /Bind 0 axis 0 increase/ }).innerText()) === 'H');
  await page.keyboard.down('KeyH');
  await until(async () => (await sent()).some((m) => m.type === 'Joystick' && m.device === '0' && m.data['>axes'][0] > 0.2), 'rebound key moves the axis');
  await page.keyboard.up('KeyH');
  await shot('05-keyboard-settings');

  // Collapsing and layout persist across reloads
  await gui.getByRole('button', { name: 'FMS' }).click();
  await page.reload();
  const again = page.frameLocator('#sim');
  await again.getByText('No robot program is running').waitFor();
  check('collapsed panel stays collapsed', (await again.getByRole('button', { name: 'FMS' }).getAttribute('aria-expanded')) === 'false');
  check('joystick assignment persists', (await again.getByLabel('Joystick 2', { exact: true }).inputValue()) === 'Keyboard1');
  await again.getByRole('button', { name: 'FMS' }).click();
} catch (error) {
  check('test run', false, String(error));
  await shot('failure').catch(() => {});
}

check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
await server.close();
if (failures.length) {
  console.log(`\n${failures.length} failed`);
  process.exit(1);
}
console.log('\nall passed');
