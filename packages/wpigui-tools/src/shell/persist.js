// Keeps the app's settings directory in IndexedDB (one database per directory).
Module.preRun = Module.preRun || [];
Module.preRun.push(() => {
  const dir = Module.settingsDir;
  FS.mkdirTree(dir);
  FS.mount(IDBFS, {}, dir);

  addRunDependency('idbfs');
  FS.syncfs(true, (err) => {
    if (err) console.warn('settings load failed', err);
    removeRunDependency('idbfs');
  });

  const save = () => FS.syncfs(false, (err) => err && console.warn('settings save failed', err));
  setInterval(save, 5000);
  addEventListener('pagehide', save);
  document.addEventListener('visibilitychange', () => document.hidden && save());
});
