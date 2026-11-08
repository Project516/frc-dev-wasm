// Runs browser scenarios from scenarios.ts in headless Chromium.
// Usage: node test/run.mjs [scenario...]
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright-core';
import { createServer } from 'vite';

const here = import.meta.dirname;

// The files of the robot project in PROJECT_DIR that a build reads, for the buildProject scenario
function projectFiles(dir) {
  const files = {};
  const walk = (rel) => {
    for (const entry of fs.readdirSync(path.join(dir, rel), { withFileTypes: true })) {
      const file = rel ? `${rel}/${entry.name}` : entry.name;
      if (entry.isDirectory()) walk(file);
      else if (/^(build\.gradle|gradle\.properties|vendordeps\/.*\.json|src\/main\/java\/.*\.java)$/.test(file)) files[file] = fs.readFileSync(path.join(dir, file), 'utf8');
    }
  };
  for (const top of ['build.gradle', 'gradle.properties']) if (fs.existsSync(path.join(dir, top))) files[top] = fs.readFileSync(path.join(dir, top), 'utf8');
  for (const sub of ['vendordeps', 'src/main/java']) if (fs.existsSync(path.join(dir, sub))) walk(sub);
  return files;
}
const names = process.argv.slice(2);
const all = ['nt4Server', 'nt4RobotOnly', 'robotStartupFailure', 'compileTemplate', 'simulateTemplate', 'simulateVendors', 'simulateMaven', 'simulatePhoenixMotion', 'simulateOverrun', 'simulateDogLog'];

const server = await createServer({
  root: path.resolve(here, '..'),
  publicDir: path.resolve(here, '../../../apps/ide/public'),
  logLevel: 'warn',
  server: { port: 0, host: '127.0.0.1' },
  // Bundle dependencies before the page loads: one found later reloads the page mid-scenario
  optimizeDeps: { entries: ['test/index.html'] },
});
await server.listen();
const url = `${server.resolvedUrls.local[0]}test/`;

const browser = await chromium.launch();
fs.mkdirSync(path.join(here, '../test-results'), { recursive: true });
let failed = false;
try {
  const page = await browser.newPage();
  page.on('pageerror', (err) => console.log(`pageerror: ${err.message} ${(err.stack ?? '').split('\n').slice(0, 3).join(' | ')}`));
  page.on('console', (m) => process.env.DEBUG_CONSOLE && console.log('console:', m.text().slice(0, 300)));
  await page.goto(url);
  await page.waitForFunction(() => 'scenarios' in window);
  for (const name of names.length ? names : all) {
    const started = Date.now();
    try {
      const arg =
        name === 'buildProject' ? [projectFiles(process.env.PROJECT_DIR), Number(process.env.SIMULATE_MS ?? 0)]
        : name === 'elastic' ? [process.env.WPILIB_YEAR ?? '2026']
        : name === 'junit' ? [process.env.WPILIB_YEAR ?? '2026', process.env.JUNIT_VARIANT ?? '']
        : [];
      const result = await page.evaluate(([n, a]) => window.scenarios[n](...a), [name, arg]);
      console.log(`== ${name} (${Date.now() - started} ms)\n${JSON.stringify(result, null, 2)}`);
      if (process.env.SCREENSHOT) await page.screenshot({ path: path.join(here, `../test-results/${name}.png`) });
    } catch (err) {
      failed = true;
      console.log(`== ${name} FAILED\n${err.message}`);
    }
  }
} finally {
  await browser.close();
  await server.close();
}
process.exit(failed ? 1 : 0);
