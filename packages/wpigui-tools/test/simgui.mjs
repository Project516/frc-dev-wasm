// Loads the built Sim GUI (halsim_gui) next to a simulated TimedRobot in a real
// CheerpJ JVM. Clicks Teleoperated and checks that the robot sees it, maps the
// keyboard joystick to joystick 0 and checks that an axis reaches the robot, and
// checks that the PWM value the robot sets arrives in the GUI. Needs
// `pnpm build:simgui`, `pnpm fetch-deps` and `pnpm build:java` first. Coordinates
// assume the default window layout at 1280x800. See harness.mjs for EXPLORE.
import { startHarness, year } from './harness.mjs';

const t = await startHarness();
const { page, check } = t;

// Window positions in the default layout. In 2026 clicking Teleoperated enables the robot. In
// 2027 it selects the mode and the Enable button below it enables the robot.
const [TELEOP, ENABLE, HARDWARE_MENU, PWM_OUTPUTS, KEYBOARD_0, JOYSTICK_0] =
  year === '2027'
    ? [[41, 91], [27, 171], [127, 8], [150, 149], [50, 511], [287, 505]]
    : [[48, 104], undefined, [156, 9], [160, 139], [40, 502], [300, 502]];
// The value in the PWM Outputs window: its title bar changes color with the focus.
const PWM_WINDOW = year === '2027' ? { x: 912, y: 45, width: 130, height: 28 } : { x: 905, y: 20, width: 115, height: 100 };
// A dark window that the fit check moves near the far corner of a larger layout.
const FAR_WINDOW = year === '2027' ? '###Display' : '###FMS';

const sim = (frame) => frame.evaluate(() => JSON.parse(Module.ccall('frc_sim_state', 'string', [], [])));
const robotConsole = () => page.evaluate(() => window.simTest.console);

async function until(predicate, timeout = 60_000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (await predicate()) return true;
    await page.waitForTimeout(500);
  }
  return false;
}

// ImGui needs a few frames between the steps of a drag and drop.
async function dragSlowly([x1, y1], [x2, y2]) {
  await page.mouse.move(x1, y1);
  await page.waitForTimeout(200);
  await page.mouse.down();
  for (let i = 1; i <= 8; i++) {
    await page.waitForTimeout(300);
    await page.mouse.move(x1 + ((x2 - x1) * i) / 8, y1 + ((y2 - y1) * i) / 8);
    if (i === 4) await t.shot('simgui-drag');
  }
  await page.waitForTimeout(400);
  await page.mouse.up();
  await page.waitForTimeout(500);
}

try {
  await t.open();
  await page.evaluate((season) => window.scenarios.simGuiStart('simgui', season), year);
  await page.frameLocator('#tool').locator('canvas#canvas').waitFor({ timeout: 60_000 });
  check('Sim GUI canvas is created', true);
  const gui = page.frames().find((f) => f.url().includes('/tools/simgui/'));

  check('the robot program starts', await until(async () => (await robotConsole()).includes('SIMGUI started'), 240_000));
  check('the GUI does not take keyboard focus when it starts', await gui.evaluate(() => document.activeElement?.id !== 'canvas'));
  // One clear per drawn frame. The GUI slows down while nobody uses it, since it shares the robot's thread.
  await gui.evaluate(() => {
    window.drawnFrames = 0;
    for (const context of [WebGLRenderingContext, WebGL2RenderingContext]) {
      const clear = context.prototype.clear;
      context.prototype.clear = function (...args) {
        window.drawnFrames++;
        return clear.apply(this, args);
      };
    }
  });
  const framesPerSecond = async (move) => {
    await gui.evaluate(() => (window.drawnFrames = 0));
    for (let i = 0; i < 20; i++) {
      if (move) await page.mouse.move(600 + (i % 2) * 20, 400);
      await page.waitForTimeout(100);
    }
    return (await gui.evaluate(() => window.drawnFrames)) / 2;
  };
  await page.waitForTimeout(1500);
  const idle = await framesPerSecond(false);
  const active = await framesPerSecond(true);
  check('the GUI draws fewer frames while idle', idle <= 12 && active >= 2 * idle, `${idle} fps idle, ${active} fps while the mouse moves`);
  await until(async () => 'pwm' in (await sim(gui)) && '0' in (await sim(gui)).pwm, 30_000);
  await page.waitForTimeout(2000);
  await t.shot('simgui-1-running');

  if (!(await t.explore('simgui'))) {
    check('the robot program sees the GUI as disconnected', (await robotConsole()).includes('SIMGUI disabled') || !(await sim(gui)).ds);
    await page.evaluate(() => {
      window.savedSettings = new Set();
      window.addEventListener('message', ({ data }) => {
        if (data?.frcToolFiles && data.type === 'write') window.savedSettings.add(data.path);
      });
    });

    // Hardware windows are opened from the menu, as on the desktop.
    await t.act(HARDWARE_MENU);
    await t.act(PWM_OUTPUTS);
    await page.mouse.click(700, 650);
    await page.waitForTimeout(500);
    const idle = await page.screenshot({ clip: PWM_WINDOW });

    await t.act(TELEOP);
    if (ENABLE) await t.act(ENABLE);
    await t.shot('simgui-2-teleop');
    const state = await sim(gui);
    check('Teleoperated enables the GUI robot', state.ds && state.enabled && !state.autonomous && !state.test, JSON.stringify(state));
    check('the GUI connects to the robot NetworkTables', state.nt);
    check('the robot program sees isTeleopEnabled', await until(async () => (await robotConsole()).includes('SIMGUI teleop enabled=true')));

    // The 2026 robot sets a speed, the 2027 one a pulse width in microseconds.
    const [pwmValue, pwmTolerance] = year === '2027' ? [1750, 1] : [0.5, 0.01];
    const pwm = await until(async () => Math.abs(((await sim(gui)).pwm['0'] ?? 0) - pwmValue) < pwmTolerance);
    check('the PWM value the robot sets reaches the GUI', pwm, JSON.stringify((await sim(gui)).pwm));
    await page.waitForTimeout(1000);
    await t.shot('simgui-3-pwm');
    check('the PWM Outputs window shows the new value', !(await page.screenshot({ clip: PWM_WINDOW })).equals(idle));

    // Drag Keyboard 0 onto Joysticks slot 0, then hold D, which raises axis 0.
    await dragSlowly(KEYBOARD_0, JOYSTICK_0);
    await t.shot('simgui-4-mapped');
    await page.mouse.click(700, 650);
    await page.keyboard.down('d');
    const axis = await until(async () => (await robotConsole()).match(/axis0=(0\.[1-9]|1\.0)/) !== null);
    await page.keyboard.up('d');
    check('a keyboard joystick axis reaches the robot', axis, (await robotConsole()).split('\n').filter((l) => l.includes('axis0')).slice(-3).join(' | '));
    await t.shot('simgui-5-axis');

    await page.waitForFunction(() => {
      return ['simgui.json', 'simgui-ds.json'].every((name) => window.savedSettings.has(`/workspace/MyRobot/${name}`));
    }, undefined, { timeout: 30_000 });
    const before = await page.screenshot({ clip: PWM_WINDOW });
    await gui.evaluate(() => location.reload());
    await page.frameLocator('#tool').locator('canvas#canvas').waitFor({ timeout: 60_000 });
    const reopened = page.frames().find((f) => f.url().includes('/tools/simgui/'));
    await reopened.waitForFunction(() => Module.frcSim?.host && JSON.parse(Module.ccall('frc_sim_state', 'string', [], [])).enabled, undefined, { timeout: 60_000 });
    check('a reopened GUI starts with the robot state the last one set', true);
    await page.waitForTimeout(3000);
    await t.shot('simgui-6-reloaded');
    const files = await reopened.evaluate(() => Module.FS.readdir('/ws/workspace/MyRobot'));
    check('the layout is read from the project folder', files.includes('simgui.json'), files.join(','));
    const saved = await page.evaluate(() => [...window.workspace.files.keys()].filter((path) => path.includes('simgui')));
    check('the layout is saved in the project folder', saved.includes('/workspace/MyRobot/simgui.json') && saved.includes('/workspace/MyRobot/simgui-ds.json'), saved.join(','));
    check('the PWM Outputs window is restored', (await page.screenshot({ clip: PWM_WINDOW })).equals(before));

    // A layout saved in a desktop window twice the size of the page is drawn at half size, so a
    // window near its far corner shows up near the middle instead of off screen.
    await page.evaluate(([season, farWindow]) => {
      document.getElementById('tool').remove();
      const layout = {
        MainWindow: { GLOBAL: { width: '2560', height: '1600', maximized: '1', userScale: '2', style: '0', fps: '60' } },
        Window: { [farWindow]: { Collapsed: '0', Pos: '2200,1300', Size: '300,200' } },
      };
      window.workspace.files.set('/workspace/MyRobot/simgui-window.json', new TextEncoder().encode(JSON.stringify(layout)));
      const frame = document.createElement('iframe');
      frame.id = 'tool';
      frame.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;border:0';
      frame.src = `/runtime/${season}/tools/simgui/index.html`;
      document.body.append(frame);
    }, [year, FAR_WINDOW]);
    await page.frameLocator('#tool').locator('canvas#canvas').waitFor({ timeout: 60_000 });
    await page.waitForTimeout(4000);
    await t.shot('simgui-7-fit');
    const moved = await page.screenshot({ clip: { x: 1105, y: 665, width: 70, height: 70 } });
    const dark = await page.evaluate(async (base64) => {
      const bitmap = await createImageBitmap(await (await fetch(`data:image/png;base64,${base64}`)).blob());
      const context = new OffscreenCanvas(bitmap.width, bitmap.height).getContext('2d');
      context.drawImage(bitmap, 0, 0);
      const { data } = context.getImageData(0, 0, bitmap.width, bitmap.height);
      let count = 0;
      for (let i = 0; i < data.length; i += 4) if (Math.max(data[i], data[i + 1], data[i + 2]) < 60) count++;
      return count;
    }, moved.toString('base64'));
    check('a layout from a larger window is scaled down to fit', dark > 70 * 70 * 0.6, `${dark} of ${70 * 70} pixels dark where the moved window should be`);
  }
} catch (error) {
  t.failures.push('exception');
  console.error(error);
} finally {
  await t.finish();
}
