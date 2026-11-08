import type { RuntimeAssets } from './assets.ts';
import { Jvm } from './jvm.ts';
import { DEPLOY_ZIP, ROBOT_JVM_PROPERTIES } from './robot.ts';
import { vendorJarPath, type VendorJar } from './vendor.ts';

export interface TestRunOptions {
  assets: RuntimeAssets;
  /** The compiled robot code, with its resources. */
  mainJar: Uint8Array;
  /** The compiled tests, with their resources. */
  testJar: Uint8Array;
  /** The test JVM's jars from `resolveTestJars`. */
  jars: VendorJar[];
  /** CheerpJ paths of jars the site serves, such as WPILib vendordeps. */
  classpath?: string[];
  /** Names of the classes JUnit starts from, see `testClassNames`. */
  classes: string[];
  /** Zip of the project's `src/main/deploy` folder, which `Filesystem.getDeployDirectory()` serves as in the robot's JVM. */
  deploy?: Uint8Array;
  /** The `systemProperty` lines of the build.gradle's test block. */
  systemProperties?: Record<string, string>;
  /** `--tests` patterns: only the tests whose class or `class.method` matches run. */
  filters?: string[];
  /** The output of the test run, as Gradle prints it, one line at a time without the line break. */
  onOutput: (text: string) => void;
}

/** The test JVM's working directory. */
const TEST_DIR = '/files/frcdevwasm/test';

/** The JUnit Platform running a project's tests in its own JVM, as the `test` task of Gradle does. */
export class TestRun {
  /** Resolves with the exit code: 0 when every test passed, 130 after `kill`. */
  readonly exit: Promise<number>;
  private stopped!: (code: number) => void;

  private constructor(jvm: Jvm, run: Promise<number>, onOutput: (text: string) => void) {
    const killed = new Promise<number>((resolve) => (this.stopped = resolve));
    const crashed = (error: unknown) => {
      onOutput(String(error));
      return 2;
    };
    this.exit = Promise.race([run.catch(crashed), killed]).then((code) => {
      jvm.dispose();
      return code;
    });
  }

  static async start(options: TestRunOptions): Promise<TestRun> {
    const { assets } = options;
    const properties = Object.entries(options.systemProperties ?? {}).map(([key, value]) => `${key}=${value}`);
    if (options.deploy) properties.push(`frcdevwasm.deploy=${DEPLOY_ZIP}`);
    const jvm = await Jvm.create({
      loader: assets.jvmLoader,
      javaProperties: [`user.dir=${TEST_DIR}`, ...(ROBOT_JVM_PROPERTIES[assets.year] ?? []), ...properties],
      // CheerpJ ends each chunk with its line break, java-web does not
      onOutput: (text) => options.onOutput(text.replace(/\n$/, '')),
    });
    let classpath: string[];
    try {
      classpath = await TestRun.classpath(jvm, options);
    } catch (error) {
      jvm.dispose();
      throw error;
    }
    const args = [...(options.filters ?? []).flatMap((pattern) => ['--tests', pattern]), ...options.classes];
    return new TestRun(jvm, jvm.runMain('dev.project516.frcdevwasm.junit.TestRunner', classpath, ...args), options.onOutput);
  }

  private static async classpath(jvm: Jvm, options: TestRunOptions): Promise<string[]> {
    const { assets } = options;
    if (options.deploy) jvm.writeFile(DEPLOY_ZIP, options.deploy);
    const jarPaths: string[] = [];
    for (const jar of options.jars) {
      const path = vendorJarPath(jar);
      jvm.writeFile(path, await jar.data());
      jarPaths.push(path);
    }
    return [
      assets.shim,
      jvm.addFile('test.jar', options.testJar),
      jvm.addFile('main.jar', options.mainJar),
      ...jarPaths,
      ...(options.classpath ?? []),
      ...assets.wpilibClasspath,
      assets.junit,
    ];
  }

  /** Stops the run by removing its JVM. */
  kill(): void {
    this.stopped(130);
  }
}
