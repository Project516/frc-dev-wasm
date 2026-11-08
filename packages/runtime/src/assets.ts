import { appPath } from './app-path.ts';
import { CHEERPJ_LOADER } from './cheerpj.ts';
import { JAVA_WEB_YEARS, WPILIB_YEARS } from './years.ts';

/** The files under `public/runtime/<year>/` that `pnpm fetch-deps` and the Java build produce. */
export interface RuntimeAssets {
  year: string;
  wpilibVersion: string;
  /** CheerpJ paths of the WPILib jars and their dependencies, in classpath order. */
  wpilibClasspath: string[];
  /** `group:artifact` of the jars in `wpilibClasspath`. */
  provided: Set<string>;
  tools: string;
  shim: string;
  /** The JUnit launcher of `./gradlew test`. */
  junit: string;
  /** Script that defines the season's JVM globals: CheerpJ's loader, or java-web's. */
  jvmLoader: string;
  /** URL of a file in the season's runtime folder, for fetching from the page. */
  url(file: string): URL;
}

interface Manifest {
  wpilib: string;
  classpath: string[];
  provided: string[];
}

/** `base` is the shared runtime folder; the season's files are in its `<year>/` folder. */
export async function loadRuntimeAssets(year: string, base: string | URL = 'runtime/'): Promise<RuntimeAssets> {
  if (!WPILIB_YEARS.includes(year)) {
    throw new Error(`WPILib ${year} projects are not supported. This site supports WPILib ${WPILIB_YEARS.join(' and ')}.`);
  }
  const root = new URL(base, document.baseURI);
  if (!root.pathname.endsWith('/')) root.pathname += '/';
  root.pathname += `${year}/`;
  const res = await fetch(new URL('manifest.json', root));
  if (!res.ok) throw new Error(`WPILib ${year} runtime missing (${res.status}); run pnpm fetch-deps --year ${year}`);
  const manifest = (await res.json()) as Manifest;
  const url = (file: string) => new URL(file, root);
  return {
    year,
    wpilibVersion: manifest.wpilib,
    wpilibClasspath: manifest.classpath.map((file) => appPath(url(file))),
    provided: new Set(manifest.provided),
    tools: appPath(url('frcdevwasm-tools.jar')),
    shim: appPath(url('frcdevwasm-shim.jar')),
    junit: appPath(url('frcdevwasm-junit.jar')),
    jvmLoader: JAVA_WEB_YEARS.includes(year) ? new URL('../java-web/java-web.js', root).href : CHEERPJ_LOADER,
    url,
  };
}
