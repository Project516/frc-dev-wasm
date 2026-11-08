// Utilities in the uutils coreutils WASI binary. scripts/build-terminal.mjs builds exactly these.
// echo, pwd, sleep, du, grep and find are shell built-ins, and so are mv and rm -r (see fileops.ts).
export const UTILITIES = [
  'b2sum', 'base64', 'basename', 'cat', 'cksum', 'comm', 'cp', 'cut', 'date', 'dd', 'dirname', 'expand', 'expr',
  'false', 'fmt', 'fold', 'head', 'join', 'ls', 'md5sum', 'mkdir', 'nl', 'od', 'paste', 'printenv', 'printf',
  'rm', 'rmdir', 'seq', 'sha1sum', 'sha224sum', 'sha256sum', 'sha384sum', 'sha512sum', 'shuf', 'sort', 'split',
  'tac', 'tail', 'tee', 'test', 'touch', 'tr', 'true', 'truncate', 'tsort', 'unexpand', 'uniq', 'unlink', 'wc', 'yes'
]
