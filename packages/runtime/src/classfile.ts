// Also run by test/vendor.mjs under Node, so no parameter properties.
import { unzipSync } from 'fflate';

const ACC_NATIVE = 0x0100;
// What NativeStubber rewrites: native methods, java.lang.foreign downcalls, library loading, and the JDK HttpClient factories.
const TRIGGERS = ['edu/wpi/first/util/RuntimeLoader', 'edu/wpi/first/util/CombinedRuntimeLoader', 'java/net/http/HttpClient', 'java/lang/foreign/Linker', 'loadLibrary', 'isRecord', 'getRecordComponents'];

/**
 * Whether NativeStubber could change this jar: some class has a native method or names a library
 * loader. Pure Java libraries and annotation processors answer no, so they skip the transform in the
 * compile JVM. Reading the class files here is much faster than running ASM there.
 */
export function mayNeedTransform(jar: Uint8Array): boolean {
  try {
    const classes = unzipSync(jar, { filter: (file) => file.name.endsWith('.class') });
    return Object.values(classes).some(classMayNeedTransform);
  } catch {
    // Not something this can read, so leave the decision to the transform
    return true;
  }
}

export function classMayNeedTransform(data: Uint8Array): boolean {
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const decoder = new TextDecoder();
  let pos = 8;
  const count = view.getUint16(pos);
  pos += 2;
  for (let i = 1; i < count; i++) {
    const tag = view.getUint8(pos);
    pos++;
    if (tag === 1) {
      const length = view.getUint16(pos);
      pos += 2;
      if (TRIGGERS.some((t) => t.length === length && decoder.decode(data.subarray(pos, pos + length)) === t)) return true;
      pos += length;
    } else if (tag === 5 || tag === 6) {
      pos += 8;
      i++;
    } else if (tag === 15) {
      pos += 3;
    } else if (tag === 7 || tag === 8 || tag === 16 || tag === 19 || tag === 20) {
      pos += 2;
    } else {
      pos += 4;
    }
  }
  pos += 6;
  pos += 2 + 2 * view.getUint16(pos);
  for (let section = 0; section < 2; section++) {
    const members = view.getUint16(pos);
    pos += 2;
    for (let m = 0; m < members; m++) {
      if (section === 1 && (view.getUint16(pos) & ACC_NATIVE) !== 0) return true;
      pos += 6;
      const attributes = view.getUint16(pos);
      pos += 2;
      for (let a = 0; a < attributes; a++) {
        pos += 2;
        pos += 4 + view.getUint32(pos);
      }
    }
  }
  return false;
}
