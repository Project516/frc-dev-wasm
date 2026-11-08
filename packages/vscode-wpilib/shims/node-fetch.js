// node-fetch over the page's fetch. The IDE (frcDevWasmWpilib.fetch) goes through the vendor proxy for
// servers that send no CORS headers, such as frcmaven and plugins.gradle.org.
import { Buffer } from 'buffer'
import { EventEmitter } from 'events'

export class FetchError extends Error {
  constructor(message, type) {
    super(message)
    this.type = type
  }
}

// What the extension reads of a download's body: data events and pipe into a file stream
function nodeBody(response) {
  const body = new EventEmitter()
  body.pipe = (stream) => {
    response.arrayBuffer().then(
      (bytes) => {
        stream.end(Buffer.from(bytes))
        body.emit('end')
      },
      (error) => body.emit('error', error)
    )
    return stream
  }
  return body
}

export default async function fetch(url, init = {}) {
  const host = await globalThis.frcDevWasmWpilib
  const { timeout, agent, ...options } = init
  let response
  try {
    response = await (host ? host.fetch(String(url), options) : globalThis.fetch(String(url), options))
  } catch (error) {
    throw new FetchError(`request to ${url} failed, reason: ${error instanceof Error ? error.message : String(error)}`, 'system')
  }
  return {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText,
    headers: response.headers,
    url: response.url,
    json: () => response.json(),
    text: () => response.text(),
    arrayBuffer: () => response.arrayBuffer(),
    buffer: async () => Buffer.from(await response.arrayBuffer()),
    body: nodeBody(response)
  }
}
