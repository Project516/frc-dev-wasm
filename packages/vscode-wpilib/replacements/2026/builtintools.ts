// The IDE's tools instead of the scripts in ~/wpilib/2026/tools
import type { IExternalAPI } from 'vscode-wpilibapi'
import { host } from '../host'

export class BuiltinTools {
  public static async Create(api: IExternalAPI): Promise<BuiltinTools> {
    const toolApi = api.getToolAPI()
    for (const name of host().tools) {
      toolApi.addTool({ runTool: () => host().startTool(name), getDisplayName: () => name, getDescription: () => '' })
    }
    return new BuiltinTools()
  }

  public dispose() {}
}
