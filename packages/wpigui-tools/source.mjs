// A hash of what a season's tool build reads from this package, written into each built tool, so a tool built
// from older sources is not tested or deployed by mistake.
//
//   node source.mjs <runtime-dir>   fails when a tool in <runtime-dir>/<year>/tools/ is stale
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { SEASONS } from './toolchain.mjs';

const PKG = import.meta.dirname;
export const STAMP = 'source.txt';

const files = (dir) =>
  fs
    .readdirSync(path.join(PKG, dir), { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => path.relative(PKG, path.join(entry.parentPath, entry.name)).split(path.sep).join('/'));

export function sourceHash(year) {
  // src/2027 is the 2027 build's own; the rest of src is 2026's, and src/shell is shared
  const own = files('src').filter((file) =>
    year === '2026' ? !file.startsWith('src/2027/') : file.startsWith(`src/${year}/`) || file.startsWith('src/shell/'),
  );
  const hash = createHash('sha256').update(SEASONS[year].tag);
  for (const file of ['build.mjs', 'toolchain.mjs', `targets/${year}.mjs`, ...files(`patches/${year}`), ...own].sort()) {
    hash.update(`\0${file}\0`).update(fs.readFileSync(path.join(PKG, file)));
  }
  return hash.digest('hex').slice(0, 16);
}

/** Tools under `runtime` built from other sources (`stale`), or before builds were stamped (`unstamped`). */
export async function checkTools(runtime) {
  const result = { stale: [], unstamped: [] };
  for (const year of Object.keys(SEASONS)) {
    const { apps } = await import(`./targets/${year}.mjs`);
    const hash = sourceHash(year);
    for (const name of Object.keys(apps)) {
      const dir = path.join(runtime, year, 'tools', name);
      if (!fs.existsSync(dir)) continue;
      const stamp = path.join(dir, STAMP);
      if (!fs.existsSync(stamp)) result.unstamped.push(`${year}/${name}`);
      else if (fs.readFileSync(stamp, 'utf8').trim() !== hash) result.stale.push(`${year}/${name}`);
    }
  }
  return result;
}

if (process.argv[1] === import.meta.filename) {
  const runtime = process.argv[2];
  if (!runtime) throw new Error('usage: node source.mjs <runtime-dir>');
  const { stale, unstamped } = await checkTools(runtime);
  if (unstamped.length) console.warn(`warning: ${unstamped.join(', ')} in ${runtime} have no ${STAMP}, so they may be stale`);
  if (stale.length) {
    const years = [...new Set(stale.map((tool) => tool.split('/')[0]))];
    console.error(`${stale.join(', ')} in ${runtime} were built from other sources than packages/wpigui-tools has now. Rebuild them: pnpm build:wpigui-tools ${years.map((y) => `--year ${y}`).join(' ')}`);
    process.exit(1);
  }
}
