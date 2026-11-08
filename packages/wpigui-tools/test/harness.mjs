// Shared setup for the tool tests: a dev server over packages/runtime and the
// IDE's public files, headless Chromium with software GL, and check helpers.
// Screenshots go to test-results/.
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright-core';
import { createServer } from 'vite';

const here = import.meta.dirname;
// The season whose build is tested, see run.mjs.
export const year = process.env.WPIGUI_YEAR ?? '2026';
const results = path.join(here, '../test-results');
fs.mkdirSync(results, { recursive: true });

export async function startHarness() {
  // A stuck page would otherwise hold the machine until the caller gives up.
  setTimeout(() => {
    console.error('FAIL test timed out');
    process.exit(2);
  }, 600_000).unref();
  const failures = [];
  const errors = [];
  const server = await createServer({
    root: path.resolve(here, '../../runtime'),
    publicDir: path.resolve(here, '../../../apps/ide/public'),
    logLevel: 'warn',
    server: { port: 0, host: '127.0.0.1' },
  });
  await server.listen();
  const url = `${server.resolvedUrls.local[0]}test/`;
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !m.text().includes('passive event listener')) errors.push(`console: ${m.text()}`);
    if (process.env.DEBUG_CONSOLE) console.log(`console ${m.type()}: ${m.text().slice(0, 300)}`);
  });

  const check = (name, ok, detail = '') => {
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
    if (!ok) failures.push(name);
  };

  const shot = (name) => page.screenshot({ path: path.join(results, `${name}.png`) });

  // One mouse step: [x, y] clicks, ['drag', x1, y1, x2, y2] drags, ['wheel', x, y, dy] scrolls,
  // { answer: [paths] } queues the result of the next file dialog.
  async function act(step) {
    if (step.answer) {
      await page.evaluate((paths) => window.workspace.answers.push(paths), step.answer);
      return;
    }
    if (step[0] === 'wheel') {
      await page.mouse.move(step[1], step[2]);
      await page.mouse.wheel(0, step[3]);
    } else if (step[0] === 'drag') {
      // ImGui needs a frame between hovering, pressing and moving to start a drag.
      await page.mouse.move(step[1], step[2]);
      await page.waitForTimeout(200);
      await page.mouse.down();
      await page.waitForTimeout(200);
      await page.mouse.move((step[1] + step[3]) / 2, (step[2] + step[4]) / 2, { steps: 5 });
      await page.waitForTimeout(200);
      await page.mouse.move(step[3], step[4], { steps: 5 });
      await page.waitForTimeout(200);
      await page.mouse.up();
    } else {
      await page.mouse.move(step[0], step[1]);
      await page.mouse.click(step[0], step[1]);
    }
    await page.waitForTimeout(800);
  }

  // EXPLORE='[[x,y],["drag",x1,y1,x2,y2]]' runs extra steps and saves a
  // screenshot after each, for finding coordinates.
  async function explore(name) {
    if (!process.env.EXPLORE) return false;
    for (const [i, step] of JSON.parse(process.env.EXPLORE).entries()) {
      await act(step);
      await shot(`${name}-explore-${i}`);
    }
    return true;
  }

  // Counts strongly colored pixels in a screenshot region: plot lines and
  // markers, as opposed to the gray and dark blue of the interface.
  async function coloredPixels(png, [x, y, width, height]) {
    return page.evaluate(
      async ([base64, box]) => {
        const bitmap = await createImageBitmap(await (await fetch(`data:image/png;base64,${base64}`)).blob());
        const canvas = new OffscreenCanvas(box[2], box[3]);
        const context = canvas.getContext('2d');
        context.drawImage(bitmap, -box[0], -box[1]);
        const { data } = context.getImageData(0, 0, box[2], box[3]);
        let count = 0;
        for (let i = 0; i < data.length; i += 4) {
          if (Math.max(data[i], data[i + 1], data[i + 2]) - Math.min(data[i], data[i + 1], data[i + 2]) > 100) count++;
        }
        return count;
      },
      [png.toString('base64'), [x, y, width, height]],
    );
  }

  async function open() {
    await page.goto(url);
    await page.waitForFunction(() => 'scenarios' in window);
  }

  // Starts a tool in an iframe next to a simulated robot, see toolStart in scenarios.ts.
  async function startTool(app, workspace = {}) {
    await page.evaluate(([name, files, season]) => window.scenarios.toolStart(name, files, season), [app, workspace, year]);
    await page.frameLocator('#tool').locator('canvas#canvas').waitFor({ timeout: 60_000 });
    const frame = page.frames().find((f) => f.url().includes(`/${year}/tools/${app}/`));
    return frame;
  }

  async function finish() {
    if (errors.length) console.log(`browser errors (${errors.length}):\n${errors.slice(0, 15).join('\n')}`);
    await browser.close();
    await server.close();
    process.exit(failures.length || errors.length ? 1 : 0);
  }

  return { page, check, shot, act, explore, coloredPixels, open, startTool, finish, failures };
}
