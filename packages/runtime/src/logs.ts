// A robot program picks the paths of its DataLogs, so only a file under the project's logs folder is copied out.
export const isLogFile = (path: string): boolean => path.startsWith('logs/') && !path.split('/').includes('..');
