// Libraries and apps built from allwpilib 2027. Paths are relative to the patched
// source tree unless they start with GLUE (our own sources, src/2027) or GEN
// (generated). The 2027 GUI stack is SDL3 (emscripten port) with its 2D renderer.
//
// Apps sit on the libraries below. Adding another tool means adding an app
// entry and any libraries it needs.

export const WPILIB_VERSION = '2027.0.0-alpha-8';

// Directories of allwpilib that are copied and patched.
export const sourceDirs = [
  'wpiutil',
  'datalog',
  'wpimath',
  'telemetry',
  'tunables',
  'ntcore',
  'glass',
  'wpigui',
  'fields',
  'thirdparty/imgui_suite',
  'tools/outlineviewer',
  'tools/datalogtool',
  'tools/sysid',
  'hal',
  'simulation',
];

export const compileFlags = ['-std=c++23', '-Os', '-DNDEBUG', '-fwasm-exceptions', '--use-port=sdl3'];
// SDL asks for a WebGL 1 context from its OpenGL ES 2 renderer.
export const linkFlags = [];
export const prejs = [];

const W = 'wpiutil/src/main/native';
const M = 'wpimath/src/main/native';
const N = 'ntcore/src/main/native';
const I = 'thirdparty/imgui_suite';
const H = 'hal/src/main/native';
const S = 'simulation';
const F = 'fields/src';
const T = 'tools';

export const libs = {
  wpiutil: {
    inc: [`${W}/include`, ...['argparse', 'debugging', 'double-conversion', 'htrie', 'json', 'llvm', 'mpack', 'nanopb', 'sigslot', 'upb'].map((d) => `${W}/thirdparty/${d}/include`)],
    src: [
      { dir: `${W}/cpp`, recursive: true, exclude: [/\/jni\//] },
      { dir: `${W}/thirdparty/debugging/src` },
      { dir: `${W}/thirdparty/double-conversion/src`, recursive: true },
      { dir: `${W}/thirdparty/json/src`, recursive: true },
      { dir: `${W}/thirdparty/llvm/cpp`, recursive: true },
      { dir: `${W}/thirdparty/mpack/src` },
      { dir: `${W}/thirdparty/nanopb/src` },
      { dir: `${W}/thirdparty/upb/src`, recursive: true, ext: '.c' },
      { dir: `${W}/unix` },
    ],
    resources: [{ dir: `${W}/resources`, prefix: 'WPI', namespace: 'wpi' }],
  },

  datalog: {
    deps: ['wpiutil'],
    inc: ['datalog/src/main/native/include'],
    src: [{ dir: 'datalog/src/main/native/cpp' }],
  },

  telemetry: {
    deps: ['wpiutil'],
    inc: ['telemetry/src/main/native/include'],
    src: [{ dir: 'telemetry/src/main/native/cpp' }],
  },

  tunables: {
    deps: ['wpiutil'],
    inc: ['tunables/src/main/native/include'],
    src: [{ dir: 'tunables/src/main/native/cpp' }],
  },

  wpimath: {
    deps: ['wpiutil', 'telemetry', 'tunables'],
    inc: [
      `${M}/include`,
      'wpimath/src/generated/main/native/cpp',
      `${M}/thirdparty/eigen/include`,
      `${M}/thirdparty/gcem/include`,
      `${M}/thirdparty/sleipnir/include`,
      `${M}/thirdparty/sleipnir/src`,
    ],
    src: [
      ...['Pose2d', 'Pose3d', 'Quaternion', 'Rotation2d', 'Rotation3d', 'Translation2d', 'Translation3d'].map((f) => `${M}/cpp/geometry/${f}.cpp`),
      { dir: `${M}/cpp/geometry/struct` },
      ...['LinearSystemUtil', 'MathShared', 'StateSpaceUtil'].map((f) => `${M}/cpp/util/${f}.cpp`),
      `${M}/cpp/controller/LinearQuadraticRegulator.cpp`,
    ],
  },

  // The transport is replaced by GLUE/ntcore, so libuv and wpinet are not needed.
  ntcore: {
    deps: ['wpiutil', 'datalog'],
    inc: [`${N}/include`, 'ntcore/src/generated/main/native/include'],
    privateInc: [`${N}/cpp`, 'GLUE/ntcore'],
    src: [
      { dir: `${N}/cpp`, exclude: [/\/NetworkClient\.cpp$/, /\/NetworkServer\.cpp$/] },
      { dir: `${N}/cpp/local` },
      { dir: `${N}/cpp/net`, exclude: [/\/WebSocketConnection\.cpp$/] },
      { dir: `${N}/cpp/networktables` },
      { dir: 'ntcore/src/generated/main/native/cpp' },
      'GLUE/ntcore/WasmNetwork.cpp',
    ],
  },

  // The real HAL simulation. Timing is replaced by GLUE/hal/MockHooks.cpp.
  hal: {
    deps: ['wpiutil', 'ntcore'],
    inc: [`${H}/include`],
    privateInc: [`${H}/sim`],
    src: [
      { dir: `${H}/cpp` },
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
    src: [
      { dir: `${I}/imgui/cpp`, exclude: [/imgui_demo\.cpp$/] },
      { dir: `${I}/imgui/cpp/misc/cpp` },
      { dir: `${I}/implot/cpp` },
      { dir: `${I}/generated/fonts/src` },
      ...['sdl3', 'sdlgpu3', 'sdlrenderer3'].map((b) => `${I}/imgui/cpp/backends/imgui_impl_${b}.cpp`),
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

  fields: {
    deps: ['wpimath', 'wpiutil'],
    inc: [`${F}/main/native/include`, `${F}/generated/main/native/include`],
    src: [`${F}/generated/main/native/cpp/fields/fields.cpp`, ...['FieldTag', 'FieldImage', 'Field'].map((f) => `${F}/main/native/cpp/${f}.cpp`)],
    resources: [{ dir: `${F}/main/native/resources/org/wpilib/fields`, recursive: true, match: /\.json$/, prefix: 'FIELDS', namespace: 'wpi::fields' }],
  },

  fieldImages: {
    deps: ['fields'],
    // Images are fetched on demand, see GLUE/fields/field_images.cpp.
    src: ['GLUE/fields/field_images.cpp'],
    files: [{ from: `${F}/main/native/resources/org/wpilib/fields`, to: 'fields' }],
  },

  libglass: {
    deps: ['wpigui', 'wpimath', 'wpiutil', 'datalog', 'fields', 'fieldImages'],
    inc: ['glass/src/lib/native/include', 'glass/src/lib/native/thirdparty/mrccomm/include'],
    src: [{ dir: 'glass/src/lib/native/cpp', recursive: true }, { dir: 'glass/src/lib/native/thirdparty/mrccomm/src' }],
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
    resources: [{ dir: 'glass/src/app/native/resources', prefix: 'GLASS', namespace: 'wpi::glass' }],
    title: 'Glass',
    settingsDir: '/settings/glass',
  },

  outlineviewer: {
    deps: ['libglassnt', 'libglass'],
    src: [`${T}/outlineviewer/src/main/native/cpp/main.cpp`, 'GEN/WPILibVersion.cpp'],
    resources: [{ dir: `${T}/outlineviewer/src/main/native/resources`, prefix: 'OV', namespace: 'ov' }],
    title: 'OutlineViewer',
    settingsDir: '/settings/outlineviewer',
  },

  // The roboRIO downloader needs ssh and threads, so only the log exporter is built.
  datalogtool: {
    deps: ['libglass', 'datalog'],
    src: [{ dir: `${T}/datalogtool/src/main/native/cpp`, exclude: [/\/Downloader\.cpp$/, /\/Sftp\.cpp$/] }, 'GEN/WPILibVersion.cpp'],
    resources: [{ dir: `${T}/datalogtool/src/main/native/resources`, prefix: 'DLT', namespace: 'dlt' }],
    title: 'Datalog Tool',
    settingsDir: '/settings/datalogtool',
    // Other arguments are log files to open
    saveDirOption: '--save-dir',
  },

  sysid: {
    deps: ['libglass', 'wpimath', 'datalog'],
    inc: [`${T}/sysid/src/main/native/include`],
    src: [{ dir: `${T}/sysid/src/main/native/cpp`, recursive: true }, 'GEN/WPILibVersion.cpp'],
    resources: [{ dir: `${T}/sysid/src/main/native/resources`, prefix: 'SYSID', namespace: 'sysid' }],
    title: 'System Identification',
    settingsDir: '/settings/sysid',
  },

  // halsim_gui, which desktop WPILib loads into the robot process. Here it runs
  // against a mirror of the robot's HAL state, see GLUE/simgui/Mirror.cpp.
  simgui: {
    deps: ['hal', 'halsimws', 'libglassnt', 'libglass'],
    inc: [`${S}/halsim_gui/src/main/native/include`, `${H}/sim`],
    src: [{ dir: `${S}/halsim_gui/src/main/native/cpp` }, 'GLUE/simgui/ExtraProviders.cpp', 'GLUE/simgui/Mirror.cpp', 'GLUE/simgui/main.cpp'],
    prejs: ['simgui.js'],
    title: 'Robot Simulation',
    settingsDir: '/settings/simgui',
  },
};
