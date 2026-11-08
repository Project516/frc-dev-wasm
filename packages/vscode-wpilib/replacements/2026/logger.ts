// The browser console instead of winston's console and log file
export interface ILogger {
  error(message: string, ...meta: unknown[]): void
  warn(message: string, ...meta: unknown[]): void
  info(message: string, ...meta: unknown[]): void
  log(message: string, ...meta: unknown[]): void
}

export function closeLogger() {}

export function getMainLogFile(): string {
  return ''
}

export function setLoggerDirectory(_dirname: string) {}

export const logger: ILogger = {
  error: (message, ...meta) => console.error('[WPILib]', message, ...meta),
  warn: (message, ...meta) => console.warn('[WPILib]', message, ...meta),
  info: (message, ...meta) => console.info('[WPILib]', message, ...meta),
  log: (message, ...meta) => console.debug('[WPILib]', message, ...meta)
}
