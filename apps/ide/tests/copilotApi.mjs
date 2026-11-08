// Runs the Copilot API client under Node against a stubbed fetch
import assert from 'node:assert/strict'
import { chat, CopilotError, listModels } from '../src/copilot/api.ts'

let passed = 0
async function test(name, body) {
  await body()
  passed++
  console.log(`ok   ${name}`)
}

const model = (id, name, extra = {}, capabilities = {}) => ({
  id,
  name,
  version: id,
  capabilities: { type: 'chat', family: id, limits: { max_prompt_tokens: 64000, max_output_tokens: 4096 }, ...capabilities },
  ...extra
})
const reply = (status, body) => async () => new Response(typeof body === 'string' ? body : JSON.stringify(body), { status })

// What GitHub lists for a token from an OAuth app it has not approved for newer models
const OAUTH_APP = [
  model('gpt-4o-mini-2024-07-18', 'GPT-4o mini', {}, { family: 'gpt-4o-mini' }),
  model('gpt-4o-2024-11-20', 'GPT-4o', {}, { family: 'gpt-4o' }),
  model('gpt-41-copilot', 'GPT-4.1 Copilot', { model_picker_category: 'versatile' }, { type: 'completion', family: 'gpt-4.1' }),
  model('gpt-3.5-turbo-0613', 'GPT 3.5 Turbo', {}, { family: 'gpt-3.5-turbo' }),
  model('gpt-3.5-turbo', 'GPT 3.5 Turbo', { is_chat_default: true }),
  model('gpt-4o-mini', 'GPT-4o mini'),
  model('gpt-4o', 'GPT-4o')
]
// Part of what a first-party token sees
const FULL = [
  model('claude-sonnet-5', 'Claude Sonnet 5', { model_picker_category: 'versatile', policy: { state: 'disabled' }, supported_endpoints: ['/v1/messages', '/chat/completions'] }),
  model('claude-haiku-4.5', 'Claude Haiku 4.5', { model_picker_category: 'lightweight', policy: { state: 'enabled' }, billing: { is_premium: true, multiplier: 0.33 } }),
  model('copilot-search-a', 'Copilot Agent A', { preview: true }, { family: 'search-agent' }),
  model('gpt-6-luna', 'GPT-6 Luna', { model_picker_category: 'lightweight', supported_endpoints: ['/responses', 'ws:/responses'] }),
  model('gpt-4.1-2025-04-14', 'GPT-4.1', { policy: { state: 'enabled' } }, { family: 'gpt-4.1' }),
  model('mai-code-1-flash-secondary', 'MAI-Code-1.1-Flash', { model_picker_category: 'lightweight', supported_endpoints: ['/chat/completions'] }, { family: 'vscModelF' }),
  model('mai-code-1-flash-tertiary', 'MAI-Code-1.1-Flash', { model_picker_category: 'lightweight', supported_endpoints: ['/chat/completions'] }, { family: 'vscModelF' }),
  model('gpt-4.1', 'GPT-4.1', { model_picker_category: 'versatile', is_chat_default: true, policy: { state: 'enabled' } }),
  model('text-embedding-3-small', 'Embedding V3 small', {}, { type: 'embeddings' })
]

await test('an OAuth app token lists each chat model once', async () => {
  const models = await listModels('t', reply(200, { data: OAUTH_APP }))
  assert.deepEqual(models.map((m) => m.id), ['gpt-3.5-turbo', 'gpt-4o-mini', 'gpt-4o'])
  assert.deepEqual(models.filter((m) => m.isDefault).map((m) => m.id), ['gpt-3.5-turbo'])
})

await test('disabled, preview, Responses-only and dated models are left out', async () => {
  const models = await listModels('t', reply(200, { data: FULL }))
  assert.deepEqual(models.map((m) => m.id), ['claude-haiku-4.5', 'mai-code-1-flash-secondary', 'gpt-4.1'])
  assert.equal(models[0].multiplier, 0.33)
  assert.equal(models[2].multiplier, undefined)
  assert.equal(models[2].isDefault, true)
})

await test('the token and API version go with the request', async () => {
  let seen
  await listModels('secret', async (url, init) => ((seen = { url, headers: init.headers }), new Response('{"data":[]}')))
  assert.equal(seen.url, 'https://api.githubcopilot.com/models')
  assert.equal(seen.headers.authorization, 'Bearer secret')
  assert.ok(seen.headers['x-github-api-version'])
})

await test('an error answer becomes a CopilotError with its message', async () => {
  await assert.rejects(listModels('t', reply(401, { message: 'Bad credentials' })), (e) => e instanceof CopilotError && e.status === 401 && e.message === 'Bad credentials')
  await assert.rejects(listModels('t', reply(400, { error: { message: 'The requested model is not supported.' } })), /not supported/)
  await assert.rejects(listModels('t', reply(502, '')), /502/)
})

const stream = (parts) => async (_url, init) => {
  stream.body = JSON.parse(init.body)
  const encoder = new TextEncoder()
  return new Response(new ReadableStream({ start(c) { for (const p of parts) c.enqueue(encoder.encode(p)); c.close() } }), { status: 200 })
}
const event = (delta) => `data: ${JSON.stringify({ choices: [{ index: 0, delta }] })}\n\n`

const collect = async (generator) => {
  const out = []
  for await (const chunk of generator) out.push(chunk)
  return out
}

await test('a streamed answer arrives in order, also when events split across reads', async () => {
  const text = event({ role: 'assistant', content: '' }) + event({ content: 'Extend ' }) + event({ content: '`TimedRobot`.' }) + event({ content: null }) + 'data: [DONE]\n\n'
  const out = await collect(chat('t', { model: 'gpt-4o', messages: [{ role: 'user', content: 'hi' }] }, undefined, stream([text.slice(0, 50), text.slice(50, 51), text.slice(51)])))
  assert.equal(out.map((c) => c.text).join(''), 'Extend `TimedRobot`.')
  assert.equal(stream.body.model, 'gpt-4o')
  assert.equal(stream.body.stream, true)
  assert.equal(stream.body.tools, undefined)
  assert.deepEqual(stream.body.messages, [{ role: 'user', content: 'hi' }])
})

await test('tool calls arrive whole after their arguments stream in', async () => {
  const call = (delta) => event({ tool_calls: [delta] })
  const parts = [
    event({ content: 'Reading.' }),
    call({ index: 0, id: 'a', type: 'function', function: { name: 'read_file', arguments: '{"filePath":' } }),
    call({ index: 0, function: { arguments: '"Robot.java"}' } }),
    call({ index: 1, id: 'b', type: 'function', function: { name: 'list_dir', arguments: '{"path":""}' } }),
    'data: [DONE]\n\n'
  ]
  const tools = [{ name: 'read_file', description: 'Read a file', parameters: { type: 'object' } }]
  const out = await collect(chat('t', { model: 'gpt-4o', messages: [{ role: 'user', content: 'go' }], tools }, undefined, stream(parts)))
  assert.deepEqual(out, [
    { text: 'Reading.' },
    { toolCall: { id: 'a', name: 'read_file', arguments: '{"filePath":"Robot.java"}' } },
    { toolCall: { id: 'b', name: 'list_dir', arguments: '{"path":""}' } }
  ])
  assert.deepEqual(stream.body.tools, [{ type: 'function', function: { name: 'read_file', description: 'Read a file', parameters: { type: 'object' } } }])
})

await test('a request that answers a tool call is marked as the agent', async () => {
  let initiator
  const fetchFn = async (url, init) => ((initiator = init.headers['x-initiator']), new Response('data: [DONE]\n\n'))
  await collect(chat('t', { model: 'gpt-4o', messages: [{ role: 'user', content: 'go' }] }, undefined, fetchFn))
  assert.equal(initiator, 'user')
  await collect(chat('t', { model: 'gpt-4o', messages: [{ role: 'tool', tool_call_id: 'a', content: 'done' }] }, undefined, fetchFn))
  assert.equal(initiator, 'agent')
})

await test('a line that is not JSON is skipped', async () => {
  const out = await collect(chat('t', { model: 'gpt-4o', messages: [] }, undefined, stream([event({ content: 'a' }), 'data: {"choices":\n\n', event({ content: 'b' })])))
  assert.equal(out.map((c) => c.text).join(''), 'ab')
})

await test('an error event in the stream throws', async () => {
  await assert.rejects(collect(chat('t', { model: 'gpt-4o', messages: [] }, undefined, stream([event({ content: 'a' }), 'data: {"error":{"message":"quota exceeded"}}\n\n']))), /quota exceeded/)
})

console.log(`\n${passed} passed`)
