export const CHEERPJ_LOADER = 'https://cjrtnc.leaningtech.com/5.0/loader.js';
// Swing apps do not open on 5.0
export const CHEERPJ_SWING_LOADER = 'https://cjrtnc.leaningtech.com/4.3/loader.js';

export interface CheerpJInitOptions {
  version?: 8 | 11 | 17;
  status?: 'splash' | 'none' | 'default';
  natives?: Record<string, (...args: never[]) => unknown>;
  javaProperties?: string[];
  preloadResources?: Record<string, number[]>;
}

// The globals CheerpJ's loader defines on the window that loaded it.
export type CheerpJWindow = Window & typeof globalThis & {
  cheerpjInit(options?: CheerpJInitOptions): Promise<void>;
  cheerpjRunMain(className: string, classPath: string, ...args: string[]): Promise<number>;
  cheerpjRunLibrary(classPath: string): Promise<CheerpJLibrary>;
  cheerpjCreateDisplay(width: number, height: number, parent?: HTMLElement): HTMLElement;
  cheerpOSAddStringFile(path: string, data: string | Uint8Array): void;
  cjFileBlob(path: string): Promise<Blob>;
};

// Library mode proxies: `await lib.pkg.Class` resolves to a class whose static
// methods return promises.
export type CheerpJLibrary = Record<string, any>;
