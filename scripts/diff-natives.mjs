// Lists the native methods of every class in two sets of jars and prints what the second set added
// or removed, to find the shim classes a WPILib update touches.
// Usage: node scripts/diff-natives.mjs <old-jar-dir> <new-jar-dir> [java-home]
// Without java-home, javap comes from PATH.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { unzipSync } from 'fflate';

const [oldDir, newDir, javaHome] = process.argv.slice(2);
if (!oldDir || !newDir) {
  console.error('usage: node scripts/diff-natives.mjs <old-jar-dir> <new-jar-dir> [java-home]');
  process.exit(2);
}
const javap = javaHome ? path.join(javaHome, 'bin', 'javap') : 'javap';

// class name to the set of "name descriptor" of its native methods
function natives(dir) {
  const result = new Map();
  for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.jar') && !f.endsWith('-sources.jar')).sort()) {
    const jar = path.join(dir, file);
    const classes = Object.keys(unzipSync(fs.readFileSync(jar), { filter: (f) => f.name.endsWith('.class') }))
      .filter((n) => n !== 'module-info.class' && !n.startsWith('META-INF/'))
      .map((n) => n.slice(0, -'.class'.length).replaceAll('/', '.'));
    for (let i = 0; i < classes.length; i += 200) {
      const out = execFileSync(javap, ['-p', '-s', '-cp', jar, ...classes.slice(i, i + 200)], { encoding: 'utf8', maxBuffer: 1 << 28 });
      let current;
      let pending;
      for (const line of out.split('\n')) {
        const cls = /^(?:public |final |abstract |synchronized )*(?:class|interface|enum|record) ([^\s<]+)/.exec(line);
        if (cls) current = cls[1];
        else if (/^ {2}\S.*\bnative\b.*\(/.test(line)) pending = /(\S+)\(/.exec(line)[1];
        else if (pending && /^ {4}descriptor: /.test(line)) {
          if (!result.has(current)) result.set(current, new Set());
          result.get(current).add(`${pending}${line.trim().slice('descriptor: '.length)}`);
          pending = undefined;
        }
      }
    }
  }
  return result;
}

const before = natives(oldDir);
const after = natives(newDir);
let changes = 0;
for (const cls of [...new Set([...before.keys(), ...after.keys()])].sort()) {
  const a = before.get(cls) ?? new Set();
  const b = after.get(cls) ?? new Set();
  const removed = [...a].filter((m) => !b.has(m)).sort();
  const added = [...b].filter((m) => !a.has(m)).sort();
  if (!removed.length && !added.length) continue;
  changes++;
  const note = !before.has(cls) ? ' (new class)' : !after.has(cls) ? ' (class gone or no natives left)' : '';
  console.log(`${cls}${note}`);
  for (const m of removed) console.log(`  - ${m}`);
  for (const m of added) console.log(`  + ${m}`);
}
console.log(`${changes} classes changed, ${after.size} classes with natives in the new set`);
