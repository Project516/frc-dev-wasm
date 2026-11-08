import { unzipSync } from 'fflate';

const cache = new Map<string, Promise<Record<string, Uint8Array>>>();

/** Fetches and unpacks a zip once per page. */
export function fetchZip(url: URL): Promise<Record<string, Uint8Array>> {
  const key = url.href;
  let entries = cache.get(key);
  if (!entries) {
    entries = fetch(url).then(async (res) => {
      if (!res.ok) throw new Error(`GET ${url}: ${res.status}`);
      return unzipSync(new Uint8Array(await res.arrayBuffer()));
    });
    entries.catch(() => cache.delete(key));
    cache.set(key, entries);
  }
  return entries;
}
