// Runs the tool tests against the build of each season, one after the other:
//   node test/run.mjs [--year Y]... [test ...]
// Without --year it tests every season the site serves, without tests all of them.
import { spawnSync } from 'node:child_process';
import path from 'node:path';

const here = import.meta.dirname;
const TESTS = ['glass', 'outlineviewer', 'datalogtool', 'sysid', 'simgui'];

const args = process.argv.slice(2);
const years = [];
for (let i = args.indexOf('--year'); i >= 0; i = args.indexOf('--year')) years.push(args.splice(i, 2)[1]);
if (!years.length) years.push(...(await import('../../runtime/src/years.ts')).WPILIB_YEARS);
for (const test of args) if (!TESTS.includes(test)) throw new Error(`unknown test ${test}, expected one of ${TESTS.join(', ')}`);

let failed = false;
for (const year of years) {
  for (const test of args.length ? args : TESTS) {
    console.log(`== ${test} ${year}`);
    const run = spawnSync(process.execPath, [path.join(here, `${test}.mjs`)], { stdio: 'inherit', env: { ...process.env, WPIGUI_YEAR: year } });
    if (run.status !== 0) failed = true;
  }
}
process.exit(failed ? 1 : 0);
