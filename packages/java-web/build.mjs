// Copies java-web, the JVM that runs WPILib 2027 (Java 25) in the browser, from its npm package (the version is
// pinned in package.json) into apps/ide/public/runtime/java-web/: java-web.js, its worker, boot.pack and jdk/
// (image.jar with java.base, one jar per other module, app.pack, javac.pack and the OpenJDK license notices).
// Skips unless a season of WPILIB_YEARS runs on java-web; `--force` copies it anyway.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { JAVA_WEB_YEARS, WPILIB_YEARS } from '../runtime/src/years.ts'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const OUT = path.join(ROOT, 'apps', 'ide', 'public', 'runtime', 'java-web')
const DIST = path.dirname(fileURLToPath(import.meta.resolve('@project516/java-web/dist/boot.pack')))
const VERSION = JSON.parse(fs.readFileSync(path.join(DIST, '..', 'package.json'), 'utf8')).version

if (!process.argv.includes('--force') && !WPILIB_YEARS.some((year) => JAVA_WEB_YEARS.includes(year))) {
  console.log('no season in WPILIB_YEARS runs on java-web, skipping (--force copies it)')
  process.exit(0)
}

const stamp = path.join(OUT, '.version')
if (fs.existsSync(stamp) && fs.readFileSync(stamp, 'utf8') === VERSION && fs.existsSync(path.join(OUT, 'java-web.js'))) {
  console.log(`java-web ${VERSION} is in ${OUT}`)
  process.exit(0)
}

fs.rmSync(OUT, { recursive: true, force: true })
fs.mkdirSync(OUT, { recursive: true })
// jdk.localedata is only read with the localeData option, which the IDE does not set
for (const name of ['java-web.js', 'java-web-worker.js', 'boot.pack', 'jdk']) {
  fs.cpSync(path.join(DIST, name), path.join(OUT, name), { recursive: true, filter: (src) => !path.basename(src).startsWith('jdk.localedata.') })
}
fs.writeFileSync(stamp, VERSION)
console.log(`copied java-web ${VERSION} to ${OUT}`)
