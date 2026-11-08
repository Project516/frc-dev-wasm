// File System Access API members that lib.dom does not declare
interface FileSystemPermissionDescriptor {
  mode?: 'read' | 'readwrite'
}

interface FileSystemHandle {
  queryPermission(descriptor?: FileSystemPermissionDescriptor): Promise<PermissionState>
  requestPermission(descriptor?: FileSystemPermissionDescriptor): Promise<PermissionState>
  // Not in every browser
  move?(parent: FileSystemDirectoryHandle, name: string): Promise<void>
}

interface Window {
  showDirectoryPicker(options?: FileSystemPermissionDescriptor): Promise<FileSystemDirectoryHandle>
}

// Workers only
interface FileSystemSyncAccessHandle {
  read(buffer: ArrayBufferView, options?: { at?: number }): number
  write(buffer: ArrayBufferView, options?: { at?: number }): number
  truncate(size: number): void
  getSize(): number
  flush(): void
  close(): void
}

interface FileSystemFileHandle {
  createSyncAccessHandle(): Promise<FileSystemSyncAccessHandle>
}

// Workers only
declare class FileReaderSync {
  readAsArrayBuffer(blob: Blob): ArrayBuffer
}
