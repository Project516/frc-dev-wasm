// Libraries and apps built from allwpilib 2026. Paths are relative to the patched
// source tree unless they start with GLUE (our own sources) or GEN (generated).
//
// Apps sit on the libraries below. Adding another tool means adding an app
// entry and any libraries it needs.

export const WPILIB_VERSION = '2026.2.1';

// Directories of allwpilib that are copied and patched.
export const sourceDirs = ['wpiutil', 'wpimath', 'ntcore', 'glass', 'wpigui', 'fieldImages', 'thirdparty/imgui_suite', 'outlineviewer', 'datalogtool', 'sysid', 'hal', 'simulation'];

export const compileFlags = ['-std=c++20', '-Os', '-DNDEBUG', '-fwasm-exceptions', '--use-port=contrib.glfw3'];
export const linkFlags = ['-sMIN_WEBGL_VERSION=2', '-sMAX_WEBGL_VERSION=2'];
// Scripts of src/shell that every app gets after persist.js and files.js.
export const prejs = [];

const W = 'wpiutil/src/main/native';
const M = 'wpimath/src/main/native';
const N = 'ntcore/src/main/native';
const I = 'thirdparty/imgui_suite';
const H = 'hal/src/main/native';
const S = 'simulation';

export const libs = {
  wpiutil: {
    inc: [
      `${W}/include`,
      ...['argparse', 'debugging', 'expected', 'json', 'llvm', 'mpack', 'nanopb', 'sigslot', 'fmtlib'].map((d) => `${W}/thirdparty/${d}/include`),
    ],
    defs: ['NO_PROTOBUF'],
    src: [
      { dir: `${W}/cpp`, recursive: true, exclude: [/\/jni\//, /\/protobuf\//] },
      `${W}/cpp/protobuf/Protobuf.cpp`,
      { dir: `${W}/thirdparty/debugging/src` },
      { dir: `${W}/thirdparty/llvm/cpp`, recursive: true },
      { dir: `${W}/thirdparty/mpack/src` },
      { dir: `${W}/thirdparty/nanopb/src` },
      { dir: `${W}/thirdparty/fmtlib/src` },
      { dir: `${W}/unix` },
    ],
    resources: [{ dir: `${W}/resources`, prefix: 'WPI', namespace: 'wpi' }],
  },

  wpimath: {
    deps: ['wpiutil'],
    inc: [
      `${M}/include`,
      'wpimath/src/generated/main/native/cpp',
      `${M}/thirdparty/eigen/include`,
      `${M}/thirdparty/gcem/include`,
      `${M}/thirdparty/sleipnir/include`,
      `${M}/thirdparty/sleipnir/src`,
    ],
    src: [`${M}/cpp/MathShared.cpp`, `${M}/cpp/StateSpaceUtil.cpp`, `${M}/cpp/controller/LinearQuadraticRegulator.cpp`, `${M}/cpp/geometry/Pose2d.cpp`, `${M}/cpp/geometry/Rotation2d.cpp`, `${M}/cpp/geometry/Translation2d.cpp`],
  },

  // The transport is replaced by GLUE/ntcore, so libuv and wpinet are not needed.
  ntcore: {
    deps: ['wpiutil'],
    inc: [`${N}/include`, 'ntcore/src/generated/main/native/include'],
    privateInc: [`${N}/cpp`, 'GLUE/ntcore'],
    src: [
      { dir: `${N}/cpp`, exclude: [/\/NetworkClient\.cpp$/, /\/NetworkServer\.cpp$/] },
      { dir: `${N}/cpp/local` },
      { dir: `${N}/cpp/net`, exclude: [/\/WebSocketConnection\.cpp$/] },
      { dir: `${N}/cpp/networktables` },
      { dir: 'ntcore/src/generated/main/native/cpp', exclude: [/\/jni\//] },
      'GLUE/ntcore/WasmNetwork.cpp',
    ],
  },

  // The real HAL simulation. Timing is replaced by GLUE/hal/MockHooks.cpp.
  hal: {
    deps: ['wpiutil'],
    inc: [`${H}/include`, 'hal/src/generated/main/native/include'],
    privateInc: [`${H}/sim`],
    src: [
      { dir: `${H}/cpp` },
      { dir: `${H}/cpp/cpp` },
      { dir: `${H}/cpp/handles` },
      { dir: `${H}/sim`, exclude: [/\/MockHooks\.cpp$/, /\/Notifier\.cpp$/] },
      { dir: `${H}/sim/mockdata` },
      'GLUE/hal/MockHooks.cpp',
    ],
  },

  // The halsim_ws message providers. The websocket server and the SimDevice
  // provider are not used, see GLUE/simgui/Mirror.cpp.
  halsimws: {
    deps: ['hal'],
    inc: [`${S}/halsim_ws_core/src/main/native/include`],
    src: [{ dir: `${S}/halsim_ws_core/src/main/native/cpp`, exclude: [/\/WSProvider_SimDevice\.cpp$/, /\/WSProvider_HAL\.cpp$/] }],
  },

  imgui: {
    inc: [
      `${I}/imgui/include`,
      `${I}/imgui/include/misc/cpp`,
      `${I}/imgui/include/backends`,
      `${I}/implot/include`,
      `${I}/generated/fonts/include`,
      `${I}/stb/include`,
    ],
    defs: ['IMGUI_IMPL_OPENGL_ES3'],
    src: [
      { dir: `${I}/imgui/cpp`, exclude: [/imgui_demo\.cpp$/] },
      { dir: `${I}/imgui/cpp/misc/cpp` },
      { dir: `${I}/implot/cpp` },
      { dir: `${I}/generated/fonts/src` },
      `${I}/imgui/cpp/backends/imgui_impl_glfw.cpp`,
      `${I}/imgui/cpp/backends/imgui_impl_opengl3.cpp`,
      `${I}/stb/cpp/stb_image.cpp`,
    ],
  },

  wpigui: {
    deps: ['imgui'],
    inc: ['wpigui/src/main/native/include'],
    src: [
      'wpigui/src/main/native/cpp/wpigui.cpp',
      'GLUE/wpigui/portable-file-dialogs.cpp',
      'GLUE/wpigui/wpigui_emscripten.cpp',
    ],
  },

  fieldImages: {
    inc: ['fieldImages/src/main/native/include'],
    // Images are fetched on demand, see GLUE/fieldImages/fields.cpp.
    src: ['GLUE/fieldImages/fields.cpp'],
    files: [{ from: 'fieldImages/src/main/native/resources/edu/wpi/first/fields', to: 'fields' }],
  },

  libglass: {
    deps: ['wpigui', 'wpimath', 'wpiutil', 'fieldImages'],
    inc: ['glass/src/lib/native/include'],
    src: [{ dir: 'glass/src/lib/native/cpp', recursive: true }],
  },

  libglassnt: {
    deps: ['ntcore', 'libglass'],
    inc: ['glass/src/libnt/native/include'],
    src: [{ dir: 'glass/src/libnt/native/cpp', recursive: true }],
  },
};

export const apps = {
  glass: {
    deps: ['libglassnt', 'libglass'],
    src: ['glass/src/app/native/cpp/main.cpp', 'GEN/WPILibVersion.cpp'],
    resources: [{ dir: 'glass/src/app/native/resources', prefix: 'GLASS', namespace: 'glass' }],
    title: 'Glass',
    settingsDir: '/settings/glass',
  },

  outlineviewer: {
    deps: ['libglassnt', 'libglass'],
    src: ['outlineviewer/src/main/native/cpp/main.cpp', 'GEN/WPILibVersion.cpp'],
    resources: [{ dir: 'outlineviewer/src/main/native/resources', prefix: 'OV', namespace: 'ov' }],
    title: 'OutlineViewer',
    settingsDir: '/settings/outlineviewer',
  },

  // The roboRIO downloader needs ssh and threads, so only the log exporter is built.
  datalogtool: {
    deps: ['libglass'],
    src: [{ dir: 'datalogtool/src/main/native/cpp', exclude: [/\/Downloader\.cpp$/, /\/Sftp\.cpp$/] }, 'GEN/WPILibVersion.cpp'],
    resources: [{ dir: 'datalogtool/src/main/native/resources', prefix: 'DLT', namespace: 'dlt' }],
    title: 'Datalog Tool',
    settingsDir: '/settings/datalogtool',
  },

  sysid: {
    deps: ['libglass'],
    inc: ['sysid/src/main/native/include'],
    src: [{ dir: 'sysid/src/main/native/cpp', recursive: true }, 'GEN/WPILibVersion.cpp'],
    resources: [{ dir: 'sysid/src/main/native/resources', prefix: 'SYSID', namespace: 'sysid' }],
    title: 'System Identification',
    settingsDir: '/settings/sysid',
  },

  // halsim_gui, which desktop WPILib loads into the robot process. Here it runs
  // against a mirror of the robot's HAL state, see GLUE/simgui/Mirror.cpp.
  simgui: {
    deps: ['hal', 'halsimws', 'libglassnt', 'libglass'],
    inc: [`${S}/halsim_gui/src/main/native/include`],
    src: [{ dir: `${S}/halsim_gui/src/main/native/cpp` }, 'GLUE/simgui/ExtraProviders.cpp', 'GLUE/simgui/Mirror.cpp', 'GLUE/simgui/main.cpp'],
    prejs: ['simgui.js'],
    title: 'Robot Simulation',
    settingsDir: '/settings/simgui',
  },
};
