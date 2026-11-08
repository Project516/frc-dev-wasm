// The page's fetch through the IDE (frcDevWasmWpilib.fetch), which goes through the vendor proxy for
// servers that send no CORS headers, such as frcmaven and plugins.gradle.org
export async function fetch(url, init) {
  const host = await globalThis.frcDevWasmWpilib
  return host ? host.fetch(String(url), init) : globalThis.fetch(url, init)
}
