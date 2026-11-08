// Java runs in CheerpJ, a Java 17 runtime, so there is no JDK to look for
export const BROWSER_JAVA_HOME = '/cheerpj/jdk17'

export async function getJavaVersion(): Promise<number> {
  return 17
}

export async function findJdkPath(): Promise<string | undefined> {
  return BROWSER_JAVA_HOME
}
