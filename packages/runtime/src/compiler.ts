import { zipSync } from 'fflate';
import type { RuntimeAssets } from './assets.ts';
import { Jvm } from './jvm.ts';
import { vendorJarPath, type VendorJar } from './vendor.ts';

export interface Diagnostic {
  kind: 'ERROR' | 'WARNING' | 'MANDATORY_WARNING' | 'NOTE' | 'OTHER';
  /** Source path relative to the source root, e.g. `frc/robot/Robot.java`. */
  file: string | null;
  line: number;
  column: number;
  message: string;
}

export interface CompileOptions {
  /** CheerpJ paths of extra jars, such as vendor libraries. */
  classpath?: string[];
  /** Vendor jars from `VendorLibraries.resolve`. */
  vendorJars?: VendorJar[];
  /** CheerpJ paths of annotation processor jars. They are also on the compile classpath, as Lombok's annotations live in its processor jar. */
  processors?: string[];
  /** Annotation processor jars from `VendorLibraries.resolve`, only on the processor path. */
  processorJars?: VendorJar[];
  /** A jar of compiled classes to compile against, such as the robot code when compiling its tests. */
  classes?: Uint8Array;
}

export interface CompileResult {
  success: boolean;
  diagnostics: Diagnostic[];
  /** The compiled classes and resources, present when `success` is true. */
  jar?: Uint8Array;
  millis: number;
}

/**
 * javac in a long-lived JVM. Keeping one instance warm makes later builds much
 * faster than the first.
 */
export class JavaCompiler {
  private jvm?: Promise<{ jvm: Jvm; compiler: any; vendor: any }>;
  private readonly vendorWritten = new Set<string>();
  private queue: Promise<unknown> = Promise.resolve();

  constructor(private readonly assets: RuntimeAssets) {}

  /** Starts the compiler JVM ahead of the first build. */
  warmUp(): Promise<unknown> {
    return this.start();
  }

  /**
   * Compiles sources against the WPILib classpath plus `options.classpath`.
   * Keys of `sources` are paths relative to the source root; non-Java files
   * are copied into the jar as resources. Annotation processing runs only
   * when `options.processors` lists processor jars.
   */
  compile(sources: Record<string, string | Uint8Array>, options: CompileOptions = {}): Promise<CompileResult> {
    const run = this.queue.then(() => this.run(sources, options));
    this.queue = run.catch(() => undefined);
    return run;
  }

  /** Makes a downloaded vendor jar run without native code. Resolves to the same bytes when nothing needed to change. */
  transformVendorJar(jar: Uint8Array, name: string): Promise<Uint8Array> {
    const run = this.queue.then(async () => {
      const { jvm, vendor } = await this.start();
      const input = '/str/frcdevwasm-vendor-in.jar';
      const output = '/files/frcdevwasm/vendor-out.jar';
      jvm.writeFile(input, jar);
      let result: string;
      try {
        result = await vendor.transform(input, output);
      } finally {
        // CheerpJ keeps a /str/ file in memory until it is replaced
        jvm.writeFile(input, new Uint8Array(0));
      }
      if (result !== 'changed') return jar;
      return jvm.readFile(output);
    });
    this.queue = run.catch(() => undefined);
    return run.catch((error: unknown) => {
      throw new Error(`Could not prepare ${name} for the browser: ${error instanceof Error ? error.message : String(error)}`);
    });
  }

  dispose(): void {
    this.jvm?.then(({ jvm }) => jvm.dispose(), () => undefined);
    this.jvm = undefined;
    this.vendorWritten.clear();
  }

  private start() {
    this.jvm ??= (async () => {
      const jvm = await Jvm.create({ loader: this.assets.jvmLoader });
      const lib = await jvm.runLibrary([this.assets.tools]);
      const compiler = await lib.dev.project516.frcdevwasm.tools.Compiler;
      const vendor = await lib.dev.project516.frcdevwasm.vendor.VendorTransform;
      return { jvm, compiler, vendor };
    })();
    this.jvm.catch(() => (this.jvm = undefined));
    return this.jvm;
  }

  private async writeJars(jvm: Jvm, jars: VendorJar[] = []): Promise<string[]> {
    const paths: string[] = [];
    for (const jar of jars) {
      const path = vendorJarPath(jar);
      // The key is part of the path, so a jar already written is the same jar
      if (!this.vendorWritten.has(path)) {
        jvm.writeFile(path, await jar.data());
        this.vendorWritten.add(path);
      }
      paths.push(path);
    }
    return paths;
  }

  private async run(sources: Record<string, string | Uint8Array>, options: CompileOptions): Promise<CompileResult> {
    const started = performance.now();
    const { jvm, compiler } = await this.start();
    const encoder = new TextEncoder();
    const entries: Record<string, Uint8Array> = {};
    for (const [path, content] of Object.entries(sources)) {
      entries[path] = typeof content === 'string' ? encoder.encode(content) : content;
    }
    // Builds run one at a time, so fixed paths keep memory and storage from growing.
    const zipPath = '/str/frcdevwasm-sources.zip';
    jvm.writeFile(zipPath, zipSync(entries, { level: 0 }));
    const outJar = '/files/frcdevwasm/build.jar';
    const vendorPaths = await this.writeJars(jvm, options.vendorJars);
    const classesPath = '/str/frcdevwasm-classes.jar';
    if (options.classes) jvm.writeFile(classesPath, options.classes);
    const classpath = [...(options.classes ? [classesPath] : []), ...vendorPaths, ...(options.classpath ?? []), ...this.assets.wpilibClasspath];
    const processors = options.processors ?? [];
    const processorPaths = [...processors, ...(await this.writeJars(jvm, options.processorJars))];
    const processorPath = processorPaths.length ? [...processorPaths, ...classpath].join(':') : '';
    const json: string = await compiler.compile(zipPath, [...classpath, ...processors].join(':'), processorPath, outJar);
    const result = JSON.parse(json) as Omit<CompileResult, 'jar' | 'millis'>;
    const jar = result.success ? await jvm.readFile(outJar) : undefined;
    return { ...result, jar, millis: Math.round(performance.now() - started) };
  }
}
