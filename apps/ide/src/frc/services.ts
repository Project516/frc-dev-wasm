import type * as vscode from 'vscode'

export interface FrcTemplate {
  id: string
  label: string
  description?: string
  kind: 'template' | 'example'
}

export interface FrcTool {
  id: string
  label: string
}

// Build, simulate and the vendor library manager work on the open folder, so this is what they throw when it is not a robot project
export class NotRobotProject extends Error {}

export interface RunOptions {
  // Absolute project folder. Defaults to the open folder.
  root?: string
  // Where build and robot output goes. Defaults to the WPILib terminal.
  write?: (text: string) => void
  // Called when the robot program ends, however it ends
  onExit?: () => void
  // Whether simulate opens the Sim GUI, the halsim_gui extension. Defaults to true.
  gui?: boolean
  // For test: only the tests whose class or class.method matches one of these patterns (gradlew --tests)
  tests?: string[]
  // Stops the run when aborted. Only test uses it.
  signal?: AbortSignal
  // Simulate runs the folder's last successful build instead of building again, as the WPILib extension builds
  // with Gradle before it starts the simulation
  reuseBuild?: boolean
}

// What GradleRIO's simulateExternalJava tasks describe: the wpi.sim settings and main class of build.gradle
export interface SimulationInfo {
  mainClass: string
  extensions: { name: string; artifact: string; defaultEnabled: boolean }[]
  environment: Record<string, string>
}

// What the runtime implements. The extension only prompts, then delegates here.
export interface FrcServices {
  readonly tools: readonly FrcTool[]
  // The WPILib season of the open robot project, or the default one
  runtimeYear(): Promise<string>
  listTemplates(year: string): Promise<FrcTemplate[]>
  // folder is an absolute path in the workspace file system, for example /workspace/MyRobot. The caller opens it.
  createProject(year: string, templateId: string, folder: string, teamNumber: number): Promise<void>
  // Resolves to true when the build succeeded
  build(options?: RunOptions): Promise<boolean>
  // Builds, then runs the JUnit tests of src/test. Resolves to true when the build and every test passed.
  test(options?: RunOptions): Promise<boolean>
  // Resolves to true when the robot program started
  simulate(options?: RunOptions): Promise<boolean>
  // root is an absolute project folder
  simulationInfo(root: string): Promise<SimulationInfo>
  stop(): Promise<void>
  startTool(toolId: string): Promise<void>
  manageVendorLibraries(): Promise<void>
  // Downloads the vendor jars that the open robot projects need and the cache lacks
  prepareVendorLibraries(): Promise<void>
  // Takes vendor jars from disk, for when they cannot be downloaded
  importVendorJars(): Promise<void>
  // Starts the compiler in the background when the open folder is a robot project
  warmUp(): Promise<void>
  // Robot projects stored in the browser workspace, and the open folder when it is one. Absolute paths.
  robotProjects(): Promise<string[]>
}

// What the extension offers the runtime.
export interface FrcHost {
  readonly vscode: typeof vscode
  // Writes to the WPILib terminal and the WPILib output channel
  write(text: string): void
  // Writes to the WPILib output channel only
  log(text: string): void
  showTerminal(): void
  // Opens the Sim GUI editor tab for a robot program of this season
  showSimGui(year: string): Promise<void>
  // Closes it when the robot program ends, as the desktop Sim GUI window goes with the program
  closeSimGui(): void
  // The iframe hosting the Sim GUI page, while its tab is open
  getSimFrame(): HTMLIFrameElement | undefined
  // Runs git in a folder of the workspace. Undefined when git cannot run there.
  git(args: string[], cwd: string): Promise<{ code: number; out: string } | undefined>
}

export type FrcServicesFactory = (host: FrcHost) => FrcServices
