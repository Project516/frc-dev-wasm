// Also run by test/project.mjs under Node.

/** Converts a URL on this site into the path CheerpJ sees under its `/app/` mount. */
export function appPath(url: string | URL): string {
  const resolved = new URL(url, document.baseURI);
  if (resolved.origin !== location.origin) throw new Error(`CheerpJ can only read same-origin files: ${resolved}`);
  return `/app${decodeURIComponent(resolved.pathname)}`;
}
