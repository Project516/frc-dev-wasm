import { zipSync } from 'fflate';
import type { RuntimeAssets } from './assets.ts';
import { CHEERPJ_SWING_LOADER } from './cheerpj.ts';
import { Jvm } from './jvm.ts';
import type { BridgeMessage } from './robot.ts';

/** A file the app wrote or deleted. `data` is null for a deleted file. */
export interface FileChange {
  /** Path relative to the app's files folder. */
  path: string;
  data: Uint8Array | null;
}

/** The page's end of a bridge (docs/bridge-protocol.md) between an app's Java code and the page. */
export interface SwingBridge {
  /** Receives the messages the app sends. `reply` queues a message for the app. */
  onMessages(messages: BridgeMessage[], reply: (message: BridgeMessage) => void): void;
  /** Called when the app's JVM is gone. */
  close(): void;
}

export interface SwingAppOptions {
  assets: RuntimeAssets;
  /** The iframe that shows the app's windows. It must already be in the document. */
  frame: HTMLIFrameElement;
  /** CheerpJ paths of the app's jars. */
  classpath: string[];
  mainClass: string;
  args?: string[];
  /** Gives the app's Java code a bridge, for apps that rely on the shim jar's bridge. */
  bridge?: SwingBridge;
  /** Names the app's folder in persistent storage, so use one id per app. */
  id: string;
  /** The folder the app sees as its home and working directory, as paths relative to it. */
  files: Record<string, Uint8Array>;
  /** Receives the files the app changed, shortly after each change. */
  onFileChanges: (changes: FileChange[]) => void | Promise<void>;
  /** Output CheerpJ prints itself, including everything the app writes to System.out and System.err. */
  onOutput?: (text: string) => void;
  /** Called when copying the app's files fails. The app keeps running and the copy is retried. */
  onError?: (message: string) => void;
  onExit?: (code: number) => void;
}

const HOST_CLASS = 'dev.project516.frcdevwasm.tools.SwingHost';
const POLL_MS = 1000;
// CheerpJ keeps its own preferences and font cache under the home directory.
const IGNORED = /^\.java\//;

/**
 * A Java Swing application running in its own JVM and display. The app works
 * in a folder under `/files/` that starts as a copy of `options.files`. A timer
 * reports what the app changed in that folder.
 */
export class SwingApp {
  private known = new Map<string, string>();
  private pending: Promise<void> = Promise.resolve();
  private timer?: ReturnType<typeof setInterval>;
  private disposed = false;

  private constructor(
    private readonly jvm: Jvm,
    private readonly host: any,
    private readonly dir: string,
    private readonly options: SwingAppOptions,
  ) {}

  static async start(options: SwingAppOptions): Promise<SwingApp> {
    const dir = `/files/frcdevwasm/swing/${options.id}`;
    const { bridge } = options;
    const inbox: string[] = [];
    const jvm = await Jvm.create({
      frame: options.frame,
      loader: CHEERPJ_SWING_LOADER,
      javaProperties: [`user.home=${dir}`, `user.dir=${dir}`, ...(bridge ? ['frcdevwasm.bridge=js'] : [])],
      onOutput: options.onOutput,
      natives: bridge && {
        async Java_dev_project516_frcdevwasm_bridge_Bridge_send(_lib: unknown, json: string) {
          bridge.onMessages(JSON.parse(json) as BridgeMessage[], (message) => inbox.push(JSON.stringify(message)));
        },
        async Java_dev_project516_frcdevwasm_bridge_Bridge_receive() {
          const json = `[${inbox.join(',')}]`;
          inbox.length = 0;
          return json;
        },
      },
    });
    try {
      const lib = await jvm.runLibrary([options.assets.tools]);
      const host = await lib.dev.project516.frcdevwasm.tools.SwingHost;
      const zipPath = `/str/frcdevwasm-swing-${options.id}.zip`;
      jvm.writeFile(zipPath, zipSync(options.files, { level: 0 }));
      await host.extract(zipPath, dir);

      const app = new SwingApp(jvm, host, dir, options);
      app.known = parseListing(await host.list(dir));
      const win = jvm.window;
      const display = win.document.createElement('div');
      win.document.body.style.cssText = 'margin:0;overflow:hidden';
      display.style.cssText = 'width:100vw;height:100vh';
      win.document.body.append(display);
      win.cheerpjCreateDisplay(-1, -1, display);

      jvm.runMain(HOST_CLASS, [options.assets.tools, ...options.classpath], options.mainClass, ...(options.args ?? [])).then(
        (code) => code,
        () => 1,
      ).then(async (code) => {
        clearInterval(app.timer);
        bridge?.close();
        await app.syncNow().catch(app.reportError);
        options.onExit?.(code);
      });
      app.timer = setInterval(() => void app.syncNow().catch(app.reportError), POLL_MS);
      return app;
    } catch (error) {
      bridge?.close();
      jvm.dispose();
      throw error;
    }
  }

  /** Reports the changes made since the last call. Resolves once `onFileChanges` has returned. */
  syncNow(): Promise<void> {
    const run = this.pending.then(() => this.sync());
    this.pending = run.catch(() => undefined);
    return run;
  }

  /** Reports the last changes, then removes the app's JVM. */
  async dispose(): Promise<void> {
    if (this.disposed) return;
    this.disposed = true;
    clearInterval(this.timer);
    await this.pending;
    await this.sync().catch(this.reportError);
    this.options.bridge?.close();
    this.jvm.dispose();
  }

  private readonly reportError = (error: unknown) => {
    this.options.onError?.(`Could not copy the app's files: ${error instanceof Error ? error.message : String(error)}`);
  };

  private async sync(): Promise<void> {
    const now = parseListing(await this.host.list(this.dir));
    const changes: FileChange[] = [];
    const next = new Map(this.known);
    for (const [path, stamp] of now) {
      if (this.known.get(path) === stamp) continue;
      changes.push({ path, data: await this.jvm.readFile(`${this.dir}/${path}`) });
      next.set(path, stamp);
    }
    for (const path of this.known.keys()) {
      if (now.has(path)) continue;
      changes.push({ path, data: null });
      next.delete(path);
    }
    if (changes.length === 0) return;
    await this.options.onFileChanges(changes);
    this.known = next;
  }
}

function parseListing(listing: string): Map<string, string> {
  const files = new Map<string, string>();
  for (const line of listing.split('\n')) {
    const [path, size, modified] = line.split('\t');
    if (path && !IGNORED.test(path)) files.set(path, `${size}:${modified}`);
  }
  return files;
}
