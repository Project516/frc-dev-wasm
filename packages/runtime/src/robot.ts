import type { RuntimeAssets } from './assets.ts';
import { Jvm } from './jvm.ts';
import { isLogFile } from './logs.ts';
import { vendorJarPath, type VendorJar } from './vendor.ts';

/** A bridge message, see docs/bridge-protocol.md. */
export interface BridgeMessage {
  type: string;
  device: string;
  data: Record<string, unknown>;
}

export interface RobotSessionOptions {
  assets: RuntimeAssets;
  /** The compiled robot program. */
  jar: Uint8Array;
  mainClass?: string;
  /** CheerpJ paths of extra jars, such as vendor libraries. */
  classpath?: string[];
  /** Vendor jars from `VendorLibraries.resolve`. */
  vendorJars?: VendorJar[];
  /** Zip of the project's `src/main/deploy` folder. */
  deploy?: Uint8Array;
  onMessages?: (messages: BridgeMessage[]) => void;
  /** Receives the DataLog files the program wrote, once it has exited. Paths are relative to the program's working directory, such as `logs/FRC_1.wpilog`. */
  onLogFiles?: (files: { path: string; data: Uint8Array }[]) => void | Promise<void>;
  /** Output CheerpJ prints itself, such as class loading errors. Normal output arrives as `console` messages. */
  onOutput?: (text: string) => void;
  onExit?: (code: number) => void;
}

const STOP_TIMEOUT_MS = 3000;
/** The robot program's working directory, so the deploy folder lands in persistent storage. */
const ROBOT_DIR = '/files/frcdevwasm/robot';
// The JVM options GradleRIO gives a season's simulation task (WPIJavaExtension; simulateJava, or run
// in 2027), as the system properties the java launcher turns them into.
export const ROBOT_JVM_PROPERTIES: Record<string, string[]> = {
  '2027': [
    'jdk.module.addopens.0=java.base/jdk.internal.vm=ALL-UNNAMED',
    'jdk.module.addopens.1=java.base/java.lang=ALL-UNNAMED',
    'jdk.module.enable.native.access.0=ALL-UNNAMED',
  ],
};
export const DEPLOY_ZIP = '/str/frcdevwasm-deploy.zip';

/** A robot program running in simulation in its own JVM. */
export class RobotSession {
  private inbox: string[] = [];
  private exited = false;
  private logsCollected?: Promise<void>;
  private readonly exitCode: Promise<number>;

  private constructor(
    private readonly jvm: Jvm,
    run: Promise<number>,
    private readonly logPaths: Set<string>,
    private readonly onLogFiles?: RobotSessionOptions['onLogFiles'],
    onExit?: (code: number) => void,
  ) {
    this.exitCode = run.then(
      (code) => code,
      () => 1,
    ).then(async (code) => {
      this.exited = true;
      await this.collectLogs();
      onExit?.(code);
      return code;
    });
  }

  private collectLogs(): Promise<void> {
    this.logsCollected ??= (async () => {
      if (!this.onLogFiles || this.logPaths.size === 0) return;
      const files: { path: string; data: Uint8Array }[] = [];
      for (const path of this.logPaths) {
        if (!path.startsWith(`${ROBOT_DIR}/`)) continue;
        const relative = path.slice(ROBOT_DIR.length + 1);
        if (!isLogFile(relative)) continue;
        // A log moved to another directory is gone from its first path.
        const data = await this.jvm.readFile(path).catch(() => undefined);
        if (data) files.push({ path: relative, data });
      }
      await this.onLogFiles(files);
    })().catch(() => undefined);
    return this.logsCollected;
  }

  static async start(options: RobotSessionOptions): Promise<RobotSession> {
    const { assets } = options;
    let session: RobotSession | undefined;
    let helloSent = false;
    const pending: string[] = [];
    const logPaths = new Set<string>();
    const javaProperties = ['frcdevwasm.bridge=js', `user.dir=${ROBOT_DIR}`, ...(ROBOT_JVM_PROPERTIES[assets.year] ?? [])];
    if (options.deploy) javaProperties.push(`frcdevwasm.deploy=${DEPLOY_ZIP}`);
    const jvm = await Jvm.create({
      loader: assets.jvmLoader,
      javaProperties,
      onOutput: options.onOutput,
      natives: {
        async Java_dev_project516_frcdevwasm_bridge_Bridge_send(_lib: unknown, json: string) {
          // The first batch shows Java's bridge and NetworkTables are up, so ask for full state now.
          if (!helloSent) {
            helloSent = true;
            (session ? session.inbox : pending).push(JSON.stringify({ type: 'hello', device: '', data: {} }));
          }
          const messages = (JSON.parse(json) as BridgeMessage[]).filter((m) => {
            if (m.type === 'datalog.file') logPaths.add(String(m.data.path));
            return m.type !== 'datalog.file';
          });
          if (messages.length) options.onMessages?.(messages);
        },
        async Java_dev_project516_frcdevwasm_bridge_Bridge_receive() {
          const queue = session ? session.inbox : pending;
          if (queue.length === 0) return '[]';
          const json = `[${queue.join(',')}]`;
          queue.length = 0;
          return json;
        },
      },
    });
    try {
      const robotJar = jvm.addFile('robot.jar', options.jar);
      if (options.deploy) jvm.writeFile(DEPLOY_ZIP, options.deploy);
      const vendorPaths: string[] = [];
      for (const vendorJar of options.vendorJars ?? []) {
        const path = vendorJarPath(vendorJar);
        jvm.writeFile(path, await vendorJar.data());
        vendorPaths.push(path);
      }
      const classpath = [assets.shim, robotJar, ...vendorPaths, ...(options.classpath ?? []), ...assets.wpilibClasspath];
      const run = jvm.runMain(options.mainClass ?? 'frc.robot.Main', classpath);
      session = new RobotSession(jvm, run, logPaths, options.onLogFiles, options.onExit);
      session.inbox.push(...pending);
      return session;
    } catch (error) {
      jvm.dispose();
      throw error;
    }
  }

  get running(): boolean {
    return !this.exited;
  }

  send(message: BridgeMessage): void {
    this.inbox.push(JSON.stringify(message));
  }

  /** Asks the robot program to exit, then removes its JVM. Resolves with the exit code, or null if it had to be killed. */
  async stop(): Promise<number | null> {
    if (!this.exited) this.send({ type: 'stop', device: '', data: {} });
    const timeout = new Promise<null>((resolve) => setTimeout(() => resolve(null), STOP_TIMEOUT_MS));
    const code = await Promise.race([this.exitCode, timeout]);
    await this.collectLogs();
    this.jvm.dispose();
    return code;
  }

  kill(): void {
    this.jvm.dispose();
  }
}
