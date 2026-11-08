// Gives the app access to the workspace, which the hosting page owns. The app
// sees workspace paths under /ws (so /workspace/a.wpilog is /ws/workspace/a.wpilog).
//
// Messages to window.parent, all with frcToolFiles: true:
//   { type: 'pick', id, kind: 'open' | 'save' | 'folder', title, path, filters, multiple }
//       Ask for a dialog. filters is a list of description, patterns pairs.
//   { type: 'write', path, data }
//       A file under /ws was written. path is the workspace path.
//   { type: 'read', id, paths }
//       Ask for files by workspace path. Missing ones are left out.
// Reply to a pick or a read:
//   { type: 'picked', id, files: [{ path, data? }] }
//       Empty when cancelled. Open dialogs send the file contents in data.
Module.preRun = Module.preRun || [];
Module.preRun.push(() => {
  const ROOT = '/ws';
  const host = window.parent !== window ? window.parent : undefined;
  const requests = new Map();
  let nextId = 1;
  let inbound = false;

  const send = (message, transfer) => host?.postMessage({ frcToolFiles: true, ...message }, location.origin, transfer);

  FS.mkdirTree(ROOT);

  window.addEventListener('message', ({ source, origin, data }) => {
    if (source !== host || origin !== location.origin || !data?.frcToolFiles || data.type !== 'picked') return;
    const request = requests.get(data.id);
    if (!request) return;
    const paths = [];
    for (const { path, data: bytes } of data.files) {
      const local = ROOT + path;
      FS.mkdirTree(request.kind === 'folder' ? local : local.slice(0, local.lastIndexOf('/')));
      if (bytes) {
        inbound = true;
        FS.writeFile(local, new Uint8Array(bytes));
        inbound = false;
      }
      paths.push(local);
    }
    request.result = paths.map((p) => `${p}\n`).join('');
    request.done = true;
  });

  Module.frcFiles = {
    start(kind, title, path, filters, multiple) {
      const id = nextId++;
      const request = { kind, done: !host, result: '' };
      requests.set(id, request);
      send({
        type: 'pick',
        id,
        kind,
        title,
        path: path.startsWith(`${ROOT}/`) ? path.slice(ROOT.length) : '',
        filters: filters.split('\x1f').slice(0, -1),
        multiple,
      });
      return id;
    },
    read(paths) {
      const id = nextId++;
      requests.set(id, { kind: 'open', done: !host, result: '' });
      send({ type: 'read', id, paths });
      return id;
    },
    done: (id) => requests.get(id)?.done ?? true,
    take(id) {
      const result = requests.get(id)?.result ?? '';
      requests.delete(id);
      return result;
    },
    cancel: (id) => requests.delete(id),
  };

  // A file written under /ws goes back to the workspace once it is closed.
  const dirty = new Map();
  const close = FS.close;
  FS.close = (stream) => {
    const { path, flags } = stream;
    close.call(FS, stream);
    if (inbound || (flags & 3) === 0 || !path?.startsWith(`${ROOT}/`)) return;
    clearTimeout(dirty.get(path));
    dirty.set(
      path,
      setTimeout(() => {
        dirty.delete(path);
        if (!FS.analyzePath(path).exists) return;
        const data = FS.readFile(path);
        send({ type: 'write', path: path.slice(ROOT.length), data }, [data.buffer]);
      }, 100)
    );
  };
});
