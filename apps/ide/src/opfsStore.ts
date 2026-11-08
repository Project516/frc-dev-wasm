// The folder in the origin private file system that holds everything under file:///
export const OPFS_STORE = 'frc-dev-wasm-files'

// Safari's FileSystemWritableFileStream.write() writes the whole buffer behind a typed array, not
// just the view. Workbench writes arrive as views into larger buffers.
export const exactBytes = (data: Uint8Array): Uint8Array<ArrayBuffer> =>
  data.byteOffset === 0 && data.byteLength === data.buffer.byteLength && data.buffer instanceof ArrayBuffer
    ? (data as Uint8Array<ArrayBuffer>)
    : data.slice()
