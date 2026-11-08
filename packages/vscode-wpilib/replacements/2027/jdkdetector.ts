// Java runs in java-web, a Java 25 runtime, so there is no JDK to look for
export const BROWSER_JAVA_HOME = '/java-web/jdk25'

export async function getJavaVersion(): Promise<{ major: number; full: string }> {
  return { major: 25, full: '25' }
}

export async function findJdkPath(): Promise<string | undefined> {
  return BROWSER_JAVA_HOME
}
