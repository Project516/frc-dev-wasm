// The IDE's tools instead of the programs in the WPILib home's tools folder
import type { IExternalAPI } from 'vscode-wpilib-src/api'
import { host } from '../host'

export async function registerBuiltinTools(api: IExternalAPI): Promise<void> {
  const toolApi = api.getToolAPI()
  for (const name of host().tools) {
    toolApi.addTool({ runTool: () => host().startTool(name), getDisplayName: () => name, getDescription: () => '' })
  }
}
