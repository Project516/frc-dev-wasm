// Keeps the Sim GUI's layout in the robot project, as halsim_gui does on the
// desktop: simgui.json, simgui-ds.json and simgui-window.json in the project
// folder. The IDE offers the folder through the SimHost. Without one, the
// settings stay in the browser (persist.js).
Module.preRun.push(() => {
  const NAMES = ['simgui.json', 'simgui-ds.json', 'simgui-window.json'];
  let root;
  try {
    root = window.parent !== window ? window.parent.frcDevWasmSim?.projectRoot?.() : undefined;
  } catch {}
  if (!root || !Module.frcFiles) return;

  addRunDependency('simgui-project');
  const dir = `/ws${root}`;
  const id = Module.frcFiles.read(NAMES.map((name) => `${root}/${name}`));
  const start = Date.now();
  const wait = setInterval(() => {
    if (!Module.frcFiles.done(id) && Date.now() - start < 5000) return;
    clearInterval(wait);
    Module.frcFiles.cancel(id);
    FS.mkdirTree(dir);
    ENV.SIMGUI_DIR = dir;
    removeRunDependency('simgui-project');
  }, 20);
});
