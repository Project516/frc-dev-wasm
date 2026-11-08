// Gradle runs in the IDE's task terminal instead of a VS Code shell task
import type * as vscode from 'vscode'
import type { IExecuteAPI } from 'vscode-wpilib-src/api'
import { host } from '../host'

export class ExecuteAPI implements IExecuteAPI {
  public executeCommand(command: string, name: string, rootDir: string, _workspace: vscode.WorkspaceFolder, env?: { [key: string]: string }): Promise<number> {
    return host().execute(command, name, rootDir, env)
  }

  public cancelCommands(): Promise<number> {
    return host().cancel()
  }
}
