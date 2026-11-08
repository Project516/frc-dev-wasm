# frc-dev-wasm

An FRC robot code development environment that runs in the browser. Edit a
WPILib Java project in a VS Code workbench, compile it with javac, and run it
in simulation, without installing anything. The app is a static site, so all
of this runs client-side.

Status: alpha. Expect rough edges, and report what breaks. Java only. WPILib 2026 (2026.2.1) is the default
season, and WPILib 2027 (the newest alpha) is offered too.

## Using it

Open [frcdev.project516.dev](https://frcdev.project516.dev) in a Chromium browser (Chrome, Edge or a Chromebook). It opens a VS Code workbench with the WPILib
extension. From the command palette (Ctrl+Shift+P):

- **WPILib: Create a new project** from a template or example, or clone a team repository with **Git: Clone**.
- **WPILib: Build Robot Code** compiles it, with javac's own errors.
- **WPILib: Simulate Robot Code** runs it, with the WPILib Sim GUI in a panel.
- **WPILib: Start Tool** opens Glass, OutlineViewer, AdvantageScope, Elastic, SmartDashboard and the other tools.

A link opens a team repository directly: `https://frcdev.project516.dev/?github=owner/repo` asks to clone it
and opens it, as vscode.dev/github/owner/repo does.

Projects stay in your browser's storage. Push them to GitHub, or use **WPILib: Export Project as Zip** to keep a
copy. For projects in the browser, all git traffic, including a GitHub token, passes through a proxy because
GitHub sends no CORS headers for git. By default that is the project's own proxy (`packages/proxy`). To avoid
it, deploy your own and set `wpilib.vendorProxy` to its address, which moves git, GitHub sign-in and vendor
library downloads to it. See [docs/git.md](docs/git.md).
See [docs/known-limits.md](docs/known-limits.md) for what does not work yet.

## Development

Requirements: Node 26 or newer and pnpm. The setup script downloads the JDK of each season
(17 for 2026, 25 for 2027), so a local Java install is not required.

```sh
pnpm install
pnpm fetch-deps
pnpm build:java
```

Run the IDE:

```sh
pnpm dev       # dev server
pnpm build     # static site in apps/ide/dist
pnpm preview   # serve the build
```

The Simulation GUI is WPILib's own `halsim_gui` compiled to WebAssembly
(`pnpm build:simgui`, about 4 MB, loaded when the GUI opens). The setting
`wpilib.simGui` switches to `classic`, the earlier hand-written GUI. Develop
the classic one against a mock robot, and run its browser test:

```sh
pnpm --filter @frc-dev-wasm/sim-gui dev
pnpm --filter @frc-dev-wasm/sim-gui test
```

AdvantageScope is built from source in a separate, heavier step, so
`pnpm fetch-deps` stays fast. It needs Python 3 and npm besides the usual
tools. The script fetches AdvantageScope v26.0.2 into `.cache/advantagescope`,
applies the patches in `tools/advantagescope/patches`, builds AdvantageScope
Lite with rollup, installs Emscripten 4.0.12 into `.cache/emsdk-4.0.12` for the log
indexer, and writes the result to
`apps/ide/public/runtime/tools/advantagescope`. The first run takes several
minutes and about 2 GB of memory. Later runs skip finished steps. Set
`NODE_OPTIONS=--max-old-space-size=3072` to cap rollup's memory. Without this
step the IDE works, but "WPILib: Start Tool" cannot open AdvantageScope.

```sh
pnpm build:advantagescope
```

The terminal's coreutils (`ls`, `cat`, `sort` and so on) are uutils compiled to
WebAssembly. The build installs Rust 1.90 into `.cache/rust`, so it needs only a C
linker on the host, and takes several minutes. Without it the terminal still
works, but coreutils commands report that the binary is missing.

```sh
pnpm build:terminal
```

`pnpm build:all` runs every step above, plus the WPILib C++ tools
(`pnpm build:wpigui-tools`), to produce the full site. `WPIGUI_JOBS=N` sets
how many C++ files compile in parallel (default 2).

`BASE_PATH=/some/path/ pnpm build` builds for hosting under a subpath. The
default base is relative, so the output also works from any directory.

## GitHub Copilot

The Copilot item in the status bar turns on the Chat view (Ctrl+Alt+I). Chat
is off by default because VS Code's chat code makes the page about a second
slower to start on a low-end computer. It answers with GitHub Copilot, on the
user's own Copilot plan, and uses the same GitHub sign-in as git and `gh`. The
Copilot status item then shows the plan and its premium request usage.

In Agent mode Copilot reads, creates, edits and deletes project files, and
runs commands such as `./gradlew build` in a terminal named Copilot. It asks
before it runs a command or deletes a file. Ask mode only reads the project.
GitHub serves only its older models (GPT-4o, GPT-4o mini, GPT 3.5 Turbo) to
OAuth apps it has not approved for Copilot, so those are what the model picker
lists.

Copilot chat uses GitHub endpoints that are not a public API, so it may stop working without notice.

## License

GNU Affero General Public License v3.0, see [LICENSE](LICENSE).

## Third-party software

- Java runs on [CheerpJ](https://cheerpj.com) by Leaning Technologies, loaded
  from its CDN under the CheerpJ Community License, for WPILib 2026 projects.
  WPILib 2027 projects run on [java-web](https://github.com/Project516/java-web)
  (AGPL-3.0).
- [WPILib](https://github.com/wpilibsuite/allwpilib) jars, templates and
  examples are BSD-3-Clause licensed.
- Vendor libraries (CTRE, REV and others) are not hosted here. Your browser
  downloads each jar from the vendor, directly or through the CORS proxy in
  `packages/proxy`, and rewrites its native methods on your device. The
  rewrite uses [ASM](https://asm.ow2.io) (BSD-3-Clause).
- The in-browser compiler is javac from OpenJDK (GPLv2 with the Classpath
  Exception), the one each JVM brings: OpenJDK 17 in CheerpJ for 2026, and the
  OpenJDK 25 of java-web's JDK image ([Eclipse Temurin](https://adoptium.net)
  build) for 2027.
- Glass, OutlineViewer, DataLogTool, SysId and the Simulation GUI (`halsim_gui`) run as WebAssembly built from
  allwpilib (BSD-3-Clause) with
  [Emscripten](https://emscripten.org), [Dear ImGui](https://github.com/ocornut/imgui)
  and [ImPlot](https://github.com/epezent/implot) (MIT). The 2026 build uses GLFW through the
  [emscripten-glfw](https://github.com/pongasoft/emscripten-glfw) port
  (Apache-2.0), the 2027 build [SDL3](https://libsdl.org) (zlib) through Emscripten's port.
- The editor is built on
  [monaco-vscode-api](https://github.com/CodinGame/monaco-vscode-api) (MIT),
  which packages VS Code (MIT).
- The terminal runs [uutils coreutils](https://github.com/uutils/coreutils)
  (MIT) compiled to WebAssembly (WASI preview 1) for `wasm32-wasip1`, with a
  small WASI host written for this project. The binary also contains uutils'
  Rust dependencies, which are permissively licensed (MIT, Apache-2.0 and
  similar). The build uses [Rust](https://www.rust-lang.org) (MIT or Apache-2.0)
  and [binaryen](https://github.com/WebAssembly/binaryen) (Apache-2.0).
  `git` in the terminal is [git](https://git-scm.com) (GPLv2) built to WebAssembly with the patch in
  `packages/git-wasm/patches`. Browsers without the origin private file system use
  [isomorphic-git](https://isomorphic-git.org) (MIT). The shell language is
  [just-bash](https://github.com/vercel-labs/just-bash) (Apache-2.0).
- Source Control and the Clone from GitHub commands are VS Code's own Git, Git Base and GitHub extensions (MIT),
  bundled for the browser at build time.
- The WPILib commands, project creator and vendor dependency view are
  [vscode-wpilib](https://github.com/wpilibsuite/vscode-wpilib) (BSD-3-Clause), the release each season's
  installer ships, built for the browser with small patches in `packages/vscode-wpilib/patches`.
- [Elastic](https://github.com/Gold872/elastic_dashboard) by Gold872 (MIT) is
  a dashboard that "WPILib: Start Tool" opens. The setup script downloads its
  web build.
- [AdvantageScope](https://github.com/Mechanical-Advantage/AdvantageScope) by
  Littleton Robotics (BSD-3-Clause) is built from source as AdvantageScope
  Lite, with the patches in `tools/advantagescope/patches`.
- [RobotBuilder](https://github.com/wpilibsuite/RobotBuilder) by WPILib
  (BSD-3-Clause) runs unmodified in CheerpJ as a "WPILib: Start Tool" tool.
  The setup script downloads its jar from the WPILib Maven repository.
- [SmartDashboard](https://github.com/wpilibsuite/SmartDashboard) by WPILib
  (BSD-3-Clause) runs the same way. Only its NetworkTables native library is
  replaced, by Java code that talks to the page.
