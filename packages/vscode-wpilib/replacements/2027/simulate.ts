// The robot program runs in the robot JVM instead of a Java debug session
import type * as vscode from 'vscode'
import { host } from '../host'

export interface ISimulateCommands {
  extensions: string
  environment?: { [key: string]: unknown }
  librarydir: string
  mainclass: string
  stopOnEntry: boolean
  workspace: vscode.WorkspaceFolder
}

export async function startSimulation(commands: ISimulateCommands): Promise<void> {
  await host().simulate({
    cwd: commands.workspace.uri.fsPath,
    extensions: commands.extensions.split(':').filter(Boolean),
    environment: commands.environment
  })
}
