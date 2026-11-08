// There is no execute bit to set here. Upstream stats gradlew first, and a stat that fails stops the Gradle task.
export function setExecutePermissions(_file: string): Promise<void> {
  return Promise.resolve()
}
