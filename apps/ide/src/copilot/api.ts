// GitHub Copilot's chat API (api.githubcopilot.com), which allows browser requests. It takes a GitHub
// OAuth token directly. Kept free of VS Code and DOM imports and of TypeScript parameter properties, so
// tests/copilotApi.mjs runs it under Node.

const COPILOT_API = 'https://api.githubcopilot.com'
const API_VERSION = '2025-10-01'

export interface CopilotModel {
  id: string
  name: string
  family: string
  version: string
  maxInputTokens: number
  maxOutputTokens: number
  // GitHub's default for the account
  isDefault: boolean
  // Premium requests one message uses, when the model is premium
  multiplier?: number
}

interface ApiModel {
  id: string
  name: string
  version?: string
  preview?: boolean
  is_chat_default?: boolean
  model_picker_category?: string
  supported_endpoints?: string[]
  policy?: { state?: string }
  billing?: { is_premium?: boolean; multiplier?: number }
  capabilities?: {
    type?: string
    family?: string
    limits?: { max_prompt_tokens?: number; max_output_tokens?: number }
  }
}

export class CopilotError extends Error {
  readonly status: number
  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

export interface ToolCall {
  id: string
  name: string
  arguments: string
}

export type ChatMessage =
  | { role: 'system' | 'user'; content: string }
  | { role: 'assistant'; content: string; tool_calls?: { id: string; type: 'function'; function: { name: string; arguments: string } }[] }
  | { role: 'tool'; content: string; tool_call_id: string }

export interface ChatTool {
  name: string
  description: string
  parameters?: object
}

export type ChatChunk = { text: string } | { toolCall: ToolCall }

type Fetch = typeof fetch

function headers(token: string): Record<string, string> {
  return {
    authorization: `Bearer ${token}`,
    'content-type': 'application/json',
    'x-github-api-version': API_VERSION,
    'openai-intent': 'conversation-panel'
  }
}

async function failure(response: Response): Promise<CopilotError> {
  const text = await response.text().catch(() => '')
  let message = text.trim()
  try {
    const body = JSON.parse(text) as { message?: string; error?: { message?: string } | string }
    message = (typeof body.error === 'string' ? body.error : body.error?.message) ?? body.message ?? message
  } catch {
    // not JSON
  }
  return new CopilotError(message || `Copilot answered ${response.status}`, response.status)
}

// The chat models the account can pick. GitHub lists dated duplicates, internal models, models the user
// has not enabled and models only the Responses API serves; those are left out.
export async function listModels(token: string, fetchFn: Fetch = fetch): Promise<CopilotModel[]> {
  const response = await fetchFn(`${COPILOT_API}/models`, { headers: headers(token) })
  if (!response.ok) throw await failure(response)
  const { data } = (await response.json()) as { data: ApiModel[] }
  const names = new Set<string>()
  const models: CopilotModel[] = []
  for (const m of data) {
    const family = m.capabilities?.family ?? m.id
    if (m.capabilities?.type !== 'chat' || m.preview || m.policy?.state === 'disabled') continue
    if (m.supported_endpoints && !m.supported_endpoints.includes('/chat/completions')) continue
    if (!m.model_picker_category && m.id !== family) continue
    if (names.has(m.name)) continue
    names.add(m.name)
    models.push({
      id: m.id,
      name: m.name,
      family,
      version: m.version ?? m.id,
      maxInputTokens: m.capabilities?.limits?.max_prompt_tokens ?? 8192,
      maxOutputTokens: m.capabilities?.limits?.max_output_tokens ?? 4096,
      isDefault: m.is_chat_default ?? false,
      multiplier: m.billing?.is_premium ? m.billing.multiplier : undefined
    })
  }
  return models
}

// Streams a chat completion. Tool calls come whole, after their arguments have streamed in.
export async function* chat(
  token: string,
  request: { model: string; messages: ChatMessage[]; tools?: ChatTool[]; maxTokens?: number },
  signal?: AbortSignal,
  fetchFn: Fetch = fetch
): AsyncGenerator<ChatChunk> {
  const tools = request.tools?.map((t) => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters ?? { type: 'object', properties: {} } } }))
  const response = await fetchFn(`${COPILOT_API}/chat/completions`, {
    method: 'POST',
    headers: {
      ...headers(token),
      // Copilot bills a premium request for what the user asks, not for the tool results that follow
      'x-initiator': request.messages.at(-1)?.role === 'tool' ? 'agent' : 'user'
    },
    body: JSON.stringify({ model: request.model, messages: request.messages, tools: tools?.length ? tools : undefined, stream: true, max_tokens: request.maxTokens }),
    signal
  })
  if (!response.ok || !response.body) throw await failure(response)

  const calls = new Map<number, ToolCall>()
  const decoder = new TextDecoder()
  const reader = response.body.getReader()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value, { stream: !done })
    const lines = buffer.split('\n')
    buffer = done ? '' : lines.pop()!
    for (const line of lines) {
      if (!line.startsWith('data:')) continue
      const data = line.slice(5).trim()
      if (data === '[DONE]') continue
      let event: {
        choices?: { delta?: { content?: string | null; tool_calls?: { index: number; id?: string; function?: { name?: string; arguments?: string } }[] } }[]
        error?: { message?: string }
      }
      try {
        event = JSON.parse(data)
      } catch {
        // A line that is not JSON carries nothing to show
        continue
      }
      if (event.error) throw new CopilotError(event.error.message ?? 'Copilot failed', 500)
      for (const choice of event.choices ?? []) {
        if (choice.delta?.content) yield { text: choice.delta.content }
        for (const call of choice.delta?.tool_calls ?? []) {
          const entry = calls.get(call.index) ?? { id: '', name: '', arguments: '' }
          entry.id ||= call.id ?? ''
          entry.name ||= call.function?.name ?? ''
          entry.arguments += call.function?.arguments ?? ''
          calls.set(call.index, entry)
        }
      }
    }
    if (done) break
  }
  for (const call of calls.values()) yield { toolCall: call }
}
