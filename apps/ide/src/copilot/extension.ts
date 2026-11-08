import { registerExtension, type IExtensionManifest } from '@codingame/monaco-vscode-api/extensions'
import { ExtensionHostKind } from '@codingame/monaco-vscode-extensions-service-override'
import type * as vscode from 'vscode'
import type { AgentTerminal } from '../frc/terminal'
import { SIGN_IN_COMMAND as SIGN_IN, SIGN_OUT_COMMAND as SIGN_OUT } from './account'
import { chat, CopilotError, listModels, type ChatMessage } from './api'
import { copilotEnabled, setCopilotEnabled } from './enabled'
import { projectSeason } from './season'
import { READ_ONLY_TOOLS, registerTools, TOOL_TAG, TOOLS } from './tools'

// The GitHub sign-in git and gh use
export interface CopilotAuth {
  token(prompt: boolean): Promise<string | undefined>
  login(): Promise<boolean>
  logout(): Promise<boolean>
}

// Largest file the chat sends when one is attached
const MAX_FILE = 100_000
// Model requests in one agent turn
const MAX_ROUNDS = 40

const INSTRUCTIONS = `You are GitHub Copilot in FRC Dev, a browser IDE for FIRST Robotics Competition robot code. \
Projects use WPILib and Java, with the same layout and Gradle build as the desktop WPILib tools. \
Match the WPILib imports and APIs the project already uses. \
Answer in Markdown and keep answers short. Put code in fenced blocks with a language.`

// WPILib moved its packages in 2027, and models default to the 2026 names
const SEASONS: Record<string, string> = {
  '2026': 'This project uses WPILib 2026 and Java 17. WPILib packages are under edu.wpi.first.',
  '2027':
    'This project uses WPILib 2027, an alpha, and Java 25. WPILib packages are under org.wpilib, not edu.wpi.first, ' +
    'and many classes moved or changed since 2026.'
}

async function seasonLine(api: typeof vscode): Promise<string | undefined> {
  const year = await projectSeason(api)
  return year && (SEASONS[year] ?? `This project uses WPILib ${year}.`)
}

const ASK_INSTRUCTIONS = `You can look at the open project with tools: find and read files before you answer \
questions about them. When you are unsure of a WPILib class or method, look it up with wpilib_api or \
wpilib_docs instead of guessing. You cannot change files in Ask mode; show the changes as code, and tell the user that \
Agent mode can make them.`

const AGENT_INSTRUCTIONS = `You can act on the open project with tools. Look at the code before you change it: \
find files, read them, then edit them. When you are unsure of a WPILib class or method, look it up with \
wpilib_api or wpilib_docs instead of guessing. Make the changes the user asks for instead of describing them. \
Build with ./gradlew build in the terminal after changing Java code, and fix the errors it reports. \
Do not start the robot simulation; tell the user to run "WPILib: Simulate Robot Code" instead. \
Paths are relative to the project folder.`

const CATEGORY = 'GitHub Copilot'
const TURN_ON = 'frc-dev-wasm.copilot.turnOn'
const TURN_OFF = 'frc-dev-wasm.copilot.turnOff'
const ASK_PARTICIPANT = 'frc-dev-wasm.copilot'
const AGENT_PARTICIPANT = 'frc-dev-wasm.copilot.agent'

// With chat off only the command that turns it on is contributed
function manifest(enabled: boolean): IExtensionManifest {
  return {
    name: 'copilot',
    publisher: 'frc-dev-wasm',
    displayName: 'GitHub Copilot',
    version: '0.0.1',
    engines: { vscode: '*' },
    activationEvents: ['*'],
    enabledApiProposals: enabled ? ['chatProvider', 'defaultChatParticipant', 'chatParticipantAdditions', 'languageModelSystem'] : [],
    contributes: enabled
      ? {
          commands: [
            { command: SIGN_IN, title: 'Sign in to Use Copilot', category: CATEGORY },
            { command: SIGN_OUT, title: 'Sign Out of GitHub', category: CATEGORY },
            { command: TURN_OFF, title: 'Turn Off Chat', category: CATEGORY }
          ],
          // One default participant per mode, as the Copilot extension has
          chatParticipants: [
            { id: ASK_PARTICIPANT, name: 'copilot', fullName: 'GitHub Copilot', description: 'Ask Copilot', isDefault: true, locations: ['panel'], modes: ['ask'] },
            { id: AGENT_PARTICIPANT, name: 'agent', fullName: 'GitHub Copilot', description: 'Edit files and run commands', isDefault: true, locations: ['panel'], modes: ['agent'] }
          ],
          languageModelChatProviders: [{ vendor: 'copilot', displayName: 'GitHub Copilot' }],
          languageModelTools: TOOLS
        }
      : { commands: [{ command: TURN_ON, title: 'Turn On Chat', category: CATEGORY }] }
  }
}

async function confirmReload(api: typeof vscode, on: boolean): Promise<void> {
  const action = on ? 'Turn On' : 'Turn Off'
  const detail = on
    ? 'The Chat view answers with your Copilot plan after you sign in to GitHub. The page reloads to load it.'
    : 'The page reloads without the Chat view, so it starts faster.'
  if ((await api.window.showInformationMessage(`${action} GitHub Copilot Chat?`, { modal: true, detail }, action)) === action) setCopilotEnabled(on)
}

function toMessages(api: typeof vscode, messages: readonly vscode.LanguageModelChatRequestMessage[]): ChatMessage[] {
  const out: ChatMessage[] = []
  for (const message of messages) {
    let text = ''
    const calls: { id: string; type: 'function'; function: { name: string; arguments: string } }[] = []
    for (const part of message.content) {
      if (part instanceof api.LanguageModelTextPart) text += part.value
      else if (part instanceof api.LanguageModelToolCallPart) {
        calls.push({ id: part.callId, type: 'function', function: { name: part.name, arguments: JSON.stringify(part.input) } })
      } else if (part instanceof api.LanguageModelToolResultPart) {
        const content = part.content.map((c) => (c instanceof api.LanguageModelTextPart ? c.value : '')).join('')
        out.push({ role: 'tool', tool_call_id: part.callId, content })
      }
    }
    if (message.role === api.LanguageModelChatMessageRole.Assistant) {
      if (text || calls.length) out.push({ role: 'assistant', content: text, ...(calls.length ? { tool_calls: calls } : {}) })
    } else if (text) {
      out.push({ role: message.role === api.LanguageModelChatMessageRole.System ? 'system' : 'user', content: text })
    }
  }
  return out
}

async function referenceText(api: typeof vscode, reference: vscode.ChatPromptReference): Promise<string | undefined> {
  const value = reference.value
  if (typeof value === 'string') return value
  const uri = value instanceof api.Uri ? value : value instanceof api.Location ? value.uri : undefined
  if (!uri) return undefined
  const document = await api.workspace.openTextDocument(uri).then(
    (d) => d,
    () => undefined
  )
  if (!document) return undefined
  const text = value instanceof api.Location ? document.getText(value.range) : document.getText()
  const name = api.workspace.asRelativePath(uri)
  const lines = value instanceof api.Location ? ` lines ${value.range.start.line + 1}-${value.range.end.line + 1}` : ''
  return `${name}${lines}:\n\`\`\`${document.languageId}\n${text.slice(0, MAX_FILE)}\n\`\`\``
}

function historyMessages(api: typeof vscode, context: vscode.ChatContext): vscode.LanguageModelChatMessage[] {
  const out: vscode.LanguageModelChatMessage[] = []
  for (const turn of context.history) {
    if (turn instanceof api.ChatRequestTurn) out.push(api.LanguageModelChatMessage.User(turn.prompt))
    else if (turn instanceof api.ChatResponseTurn) {
      const text = turn.response.map((part) => (part instanceof api.ChatResponseMarkdownPart ? part.value.value : '')).join('')
      if (text) out.push(api.LanguageModelChatMessage.Assistant(text))
    }
  }
  return out
}

export async function registerCopilot(auth: CopilotAuth, terminal: AgentTerminal): Promise<void> {
  const enabled = copilotEnabled()
  const extension = registerExtension(manifest(enabled), ExtensionHostKind.LocalProcess, { system: true })
  const api = await extension.getApi()
  if (!enabled) {
    api.commands.registerCommand(TURN_ON, () => confirmReload(api, true))
    // Where VS Code's Copilot status shows once chat is on
    const status = api.window.createStatusBarItem('frc-dev-wasm.copilot', api.StatusBarAlignment.Right, 100)
    status.name = 'Copilot'
    status.text = '$(copilot)'
    status.tooltip = 'Turn on GitHub Copilot Chat'
    status.command = TURN_ON
    status.show()
    return
  }
  api.commands.registerCommand(TURN_OFF, () => confirmReload(api, false))
  const changed = new api.EventEmitter<void>()
  registerTools(api, terminal)
  // Chat checks tool input with the JSON language extension's command, which is not built in here. The
  // tools check their required input themselves.
  api.commands.registerCommand('json.validate', () => [])

  api.commands.registerCommand(SIGN_IN, async () => {
    if (await auth.login()) changed.fire()
  })
  api.commands.registerCommand(SIGN_OUT, async () => {
    if (await auth.logout()) changed.fire()
  })

  api.lm.registerLanguageModelChatProvider('copilot', {
    onDidChangeLanguageModelChatInformation: changed.event,
    async provideLanguageModelChatInformation(options) {
      let token = await auth.token(false)
      if (!token && !options.silent && (await auth.login())) token = await auth.token(false)
      if (!token) return []
      const models = await listModels(token).catch(() => [])
      return models.map((m) => ({
        id: m.id,
        name: m.name,
        family: m.family,
        version: m.version,
        maxInputTokens: m.maxInputTokens,
        maxOutputTokens: m.maxOutputTokens,
        detail: m.multiplier === undefined ? undefined : `${m.multiplier}x`,
        isDefault: m.isDefault,
        isUserSelectable: true,
        capabilities: { toolCalling: true }
      }))
    },
    async provideLanguageModelChatResponse(model, messages, options, progress, cancel) {
      const token = await auth.token(false)
      if (!token) throw new Error('Sign in to GitHub to use Copilot.')
      const abort = new AbortController()
      cancel.onCancellationRequested(() => abort.abort())
      const tools = options.tools?.map((t) => ({ name: t.name, description: t.description, parameters: t.inputSchema }))
      try {
        for await (const chunk of chat(token, { model: model.id, messages: toMessages(api, messages), tools, maxTokens: model.maxOutputTokens }, abort.signal)) {
          if ('text' in chunk) progress.report(new api.LanguageModelTextPart(chunk.text))
          else progress.report(new api.LanguageModelToolCallPart(chunk.toolCall.id, chunk.toolCall.name, JSON.parse(chunk.toolCall.arguments || '{}')))
        }
      } catch (error) {
        if (error instanceof CopilotError && error.status === 401) throw api.LanguageModelError.NoPermissions('GitHub rejected the saved token. Sign in again to use Copilot.')
        throw error
      }
    },
    async provideTokenCount(_model, text) {
      const value = typeof text === 'string' ? text : JSON.stringify(text.content)
      return Math.ceil(value.length / 4)
    }
  })

  const handler = (agent: boolean): vscode.ChatRequestHandler => async (request, context, stream, cancel) => {
    if (!(await auth.token(false))) {
      stream.markdown('Copilot uses your GitHub account. Sign in to GitHub to start.')
      stream.button({ command: SIGN_IN, title: 'Sign in to GitHub' })
      return {}
    }
    // The tools the user left on in the tools picker
    const enabledTools = new Set([...request.tools].filter(([, on]) => on).map(([tool]) => tool.name))
    const tools = api.lm.tools.filter((t) => t.tags.includes(TOOL_TAG) && enabledTools.has(t.name) && (agent || READ_ONLY_TOOLS.has(t.name)))
    const instructions = [INSTRUCTIONS, await seasonLine(api), agent ? AGENT_INSTRUCTIONS : ASK_INSTRUCTIONS].filter(Boolean).join('\n\n')
    const messages = [new api.LanguageModelChatMessage(api.LanguageModelChatMessageRole.System, instructions), ...historyMessages(api, context)]
    for (const reference of request.references) {
      const text = await referenceText(api, reference)
      if (!text) continue
      messages.push(api.LanguageModelChatMessage.User(text))
      const value = reference.value
      if (value instanceof api.Uri || value instanceof api.Location) stream.reference(value)
    }
    messages.push(api.LanguageModelChatMessage.User(request.prompt))

    try {
      for (let round = 0; round < MAX_ROUNDS; round++) {
        const response = await request.model.sendRequest(messages, { tools }, cancel)
        let text = ''
        const calls: vscode.LanguageModelToolCallPart[] = []
        for await (const part of response.stream) {
          if (part instanceof api.LanguageModelTextPart) {
            stream.markdown(part.value)
            text += part.value
          } else if (part instanceof api.LanguageModelToolCallPart) calls.push(part)
        }
        if (!calls.length) return {}
        messages.push(api.LanguageModelChatMessage.Assistant([...(text ? [new api.LanguageModelTextPart(text)] : []), ...calls]))
        const results: vscode.LanguageModelToolResultPart[] = []
        for (const call of calls) {
          const content = await api.lm.invokeTool(call.name, { input: call.input, toolInvocationToken: request.toolInvocationToken }, cancel).then(
            (result) => result.content,
            (error: unknown) => [new api.LanguageModelTextPart(`Error: ${error instanceof Error ? error.message : String(error)}`)]
          )
          results.push(new api.LanguageModelToolResultPart(call.callId, content))
        }
        messages.push(api.LanguageModelChatMessage.User(results))
        if (cancel.isCancellationRequested) return {}
      }
      stream.markdown(`\n\nStopped after ${MAX_ROUNDS} steps. Ask me to continue.`)
      return {}
    } catch (error) {
      if (cancel.isCancellationRequested) return {}
      if (error instanceof api.LanguageModelError && error.code === api.LanguageModelError.NoPermissions.name) {
        stream.button({ command: SIGN_IN, title: 'Sign in to GitHub' })
      }
      return { errorDetails: { message: error instanceof Error ? error.message : String(error) } }
    }
  }
  api.chat.createChatParticipant(ASK_PARTICIPANT, handler(false))
  api.chat.createChatParticipant(AGENT_PARTICIPANT, handler(true))
}
