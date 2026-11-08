// What the IDE offers the WPILib extension. globalThis.frcDevWasmWpilib is a promise of it: the
// extension is registered before the workbench starts, and the IDE provides this afterwards.
export interface WpilibHost {
  // Where the site serves the extension folder (ends with a slash), and its files
  extensionBase: string
  extensionFiles: readonly string[]
  // Where the project creator starts: the browser workspace
  projectsFolder: string
  // Asks which season's project creator to open. Resolves to true when this extension's should open now,
  // and to false when the user cancelled or the IDE reloads for another season's extension.
  startCreator(): Promise<boolean>
  // fetch, through the vendor proxy when the server sends no CORS headers
  fetch(url: string, init?: RequestInit): Promise<Response>
  // Runs a command line (./gradlew ...) in a task terminal named `name`. Resolves to the exit code.
  execute(command: string, name: string, cwd: string, env?: Record<string, string>): Promise<number>
  // Asks which running tasks to stop. Resolves to how many were stopped.
  cancel(): Promise<number>
  // Runs the robot program of the project in `cwd`. `extensions` are the HAL sim extensions picked, by name.
  simulate(options: { cwd: string; extensions: string[]; environment?: Record<string, unknown> }): Promise<void>
  tools: readonly string[]
  startTool(name: string): Promise<boolean>
}
