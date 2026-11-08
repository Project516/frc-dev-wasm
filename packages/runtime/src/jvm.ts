import { CHEERPJ_LOADER, CHEERPJ_SWING_LOADER, type CheerpJInitOptions, type CheerpJLibrary, type CheerpJWindow } from './cheerpj.ts';

export interface JvmOptions {
  /** An iframe already in the document, for a JVM with a display. By default a hidden one is created. */
  frame?: HTMLIFrameElement;
  /** The script that defines the CheerpJ API: CheerpJ's loader (the default) or java-web's. */
  loader?: string;
  natives?: CheerpJInitOptions['natives'];
  javaProperties?: string[];
  onOutput?: (text: string) => void;
}

let fileCounter = 0;

/**
 * One CheerpJ instance in a hidden same-origin iframe. CheerpJ cannot be torn
 * down in place, so disposing the iframe is how a JVM is stopped.
 */
export class Jvm {
  private constructor(
    private readonly frame: HTMLIFrameElement,
    readonly window: CheerpJWindow,
  ) {}

  static async create(options: JvmOptions = {}): Promise<Jvm> {
    let frame = options.frame;
    if (!frame) {
      frame = document.createElement('iframe');
      frame.setAttribute('aria-hidden', 'true');
      frame.tabIndex = -1;
      frame.style.cssText = 'position:absolute;width:0;height:0;border:0;visibility:hidden';
      document.body.append(frame);
    }
    const win = frame.contentWindow as CheerpJWindow;
    const loader = options.loader ?? CHEERPJ_LOADER;

    // CheerpJ writes System.out and System.err to console.log.
    if (options.onOutput) {
      const forward = (...args: unknown[]) => options.onOutput!(args.map(String).join(' '));
      win.console.log = forward;
      win.console.error = forward;
    }

    try {
      await new Promise<void>((resolve, reject) => {
        const script = win.document.createElement('script');
        script.src = loader;
        script.onload = () => resolve();
        script.onerror = () => reject(new Error(`could not load the JVM from ${loader}`));
        win.document.head.append(script);
      });
      // CheerpJ fails on options that are present but undefined. java-web runs Java 25 and takes no version.
      await win.cheerpjInit({
        ...((loader === CHEERPJ_LOADER || loader === CHEERPJ_SWING_LOADER) && { version: 17 }),
        status: 'none',
        ...(options.natives && { natives: options.natives }),
        ...(options.javaProperties && { javaProperties: options.javaProperties }),
      });
    } catch (err) {
      frame.remove();
      throw err;
    }
    return new Jvm(frame, win);
  }

  runMain(className: string, classpath: string[], ...args: string[]): Promise<number> {
    return this.window.cheerpjRunMain(className, classpath.join(':'), ...args);
  }

  runLibrary(classpath: string[]): Promise<CheerpJLibrary> {
    return this.window.cheerpjRunLibrary(classpath.join(':'));
  }

  /** Makes data readable by Java and returns its path. `/str/` is flat, so names get a unique prefix. */
  addFile(name: string, data: string | Uint8Array): string {
    const path = `/str/${++fileCounter}-${name}`;
    this.writeFile(path, data);
    return path;
  }

  /** Writes a `/str/` file, replacing any earlier one at the same path. */
  writeFile(path: string, data: string | Uint8Array): void {
    // CheerpJ checks instanceof against its own window, so bytes must come from the iframe's realm.
    this.window.cheerpOSAddStringFile(path, typeof data === 'string' ? data : new this.window.Uint8Array(data));
  }

  async readFile(path: string): Promise<Uint8Array> {
    const blob = await this.window.cjFileBlob(path);
    return new Uint8Array(await blob.arrayBuffer());
  }

  dispose(): void {
    this.frame.remove();
  }
}
