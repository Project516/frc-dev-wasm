# frc-dev-wasm

A development and learning environment for FRC robot code that runs in a web
browser. A VS Code workbench edits a WPILib project, javac compiles it, and the
robot program runs in simulation, all client-side. No backend server: the app
is a static site.

Target: Java, WPILib 2026 (2026.2.1) and 2027 side by side. 2026 is the default
season. 2027 is offered as an alpha (`seasonChoice` in `apps/ide/src/frc/wpilibYear.ts` labels
any pre-release season from its manifest). A new project picks its year, and an existing one runs
with the year of its GradleRIO plugin. Only the newest pre-release of a season is served: a project on
another one builds with it, with a warning that names both versions. 2026 support is removed once 2027 is stable. C++
comes later. 2027 runs on java-web (Java 25), 2026 on CheerpJ (Java 17).

## Direction

- Runs on low-end hardware. An inexpensive laptop with a browser should be
  able to edit, build and simulate. Prefer lazy loading and small
  downloads over features that need all of them up front.
- Works for any WPILib Java robot project of a supported year, not a chosen
  few. Prefer general mechanisms (GradleRIO conventions, the vendor
  marketplace, generic JNI shims) over fixes for one project.
- Behaves like the desktop WPILib tools. Same project layout, same templates
  and examples, same javac diagnostics, same HAL simulation semantics. When in
  doubt, match what GradleRIO and the WPILib VS Code extension do.
- Fidelity to the real WPILib Java API over convenience. User code compiles
  against the unmodified WPILib jars. Only JNI classes are replaced.
- Static and self-hostable. Everything except the CheerpJ runtime and vendor
  libraries is served from this site. The CheerpJ community license only
  allows loading it from its CDN, so the app needs a network connection for
  that file. We may not redistribute vendor jars (CTRE, REV and others), so
  the user's browser downloads them from the vendor and transforms them on the
  device.
- Feels like opening VS Code. The page loads straight into the standard
  workbench, and runtime downloads happen in the background. FRC features use
  stock VS Code surfaces: commands, the status bar, notifications, the
  terminal and editor tabs. No custom landing pages or dashboards.
- One robot project per window, as on the desktop. The workbench folder is the
  project. Create, clone and zip import open the new project folder, which
  reloads the page. Build, simulate, vendor libraries and `./gradlew` act on
  the open folder, with no project picker.
- Simple code. No speculative abstractions.

## Architecture

- `apps/ide`: the static site. A Vite app on `@codingame/monaco-vscode-api`
  (VS Code workbench). It hosts a built-in FRC extension that runs in the main
  page (extension host kind `LocalProcess`), so it can talk to CheerpJ
  directly.
- `packages/runtime`: TypeScript host for CheerpJ and java-web. Each season picks its JVM
  (`JAVA_WEB_YEARS` in `years.ts`, `RuntimeAssets.jvmLoader`): `Jvm.create` loads CheerpJ's
  loader for 2026 and `runtime/java-web/java-web.js` for 2027, both defining the same globals.
  Swing tools stay on CheerpJ 4.3 (2026 only). It runs the compile JVM,
  the robot JVM and Java Swing tools (`SwingApp`), implements the bridge
  natives, and exposes NetworkTables to dashboards. Like a robot's own server, the
  IDE's NT4 server serves only while a robot program runs: it refuses connections
  before, and drops them when the program stops, so dashboards show disconnected
  and reconnect on the next run. `Nt4JvmClient` connects a
  Swing tool's NetworkTables client (SmartDashboard) to the same NT4 server. DataLogs a robot program
  writes are copied into the project's `logs/` folder when it exits.
  `vendor.ts` (`VendorLibraries`) gets vendor jars: the site does not serve
  them. For each `javaDependency` of a project's vendordeps the browser
  downloads the jar from the vendordep's `mavenUrls` (directly, or through the
  proxy when the server sends no CORS headers), transforms it in the compile
  JVM (`JavaCompiler.transformVendorJar`) and keeps it in Cache Storage (memory
  where that is unavailable), keyed by the serving repository host, group,
  artifact, version and the transform version (`runtime/<year>/vendor/transform.json`),
  so one vendordep cannot replace what another project gets for a coordinate.
  Dependencies come from `runtime/<year>/vendor/index.json` or the jar's pom. Group,
  artifact and version are checked against `[A-Za-z0-9._+-]` first. Jars are written to each JVM as
  `/str/` files. Without network the user imports the jars from disk
  (`wpilib.importVendorJars`).
  `gversion.ts` writes the class of the gversion Gradle plugin (`com.peterabeles.gversion`, 1.10.x) into the
  compile input, and into the project folder as Gradle does, when `build.gradle` applies it: the `gversion`
  block sets package, class name, date format and time zone, the git values come from the IDE's git
  (`FrcHost.git`) and fall back as the plugin does without git. A file already in the project is replaced
  only when `build.gradle` runs `createVersionFile` before `compileJava`.
  As Gradle's up-to-date check does, a build reuses the classes of the last one when nothing it compiles from has
  changed (`apps/ide/src/frc/buildCache.ts`): they are kept with a hash of the sources, classpath, processors and
  compiler in the project's `build/frc-dev-wasm/`, so `./gradlew clean` removes them. gversion's build time does not count.
  A build of the same sources waits for one that is compiling them and reuses its classes, as a second Gradle build
  waits for the first one's lock.
  `gradle.ts` also reads the `dependencies` and `repositories` blocks of
  `build.gradle` (Gradle does not run): string and map notations of
  implementation, api, compileOnly, runtimeOnly, annotationProcessor and the
  test configurations (read, not used), with variables from `def`, `ext`,
  `gradle.properties` and `new JsonSlurper().parseText(new File(...).text)`
  over project files such as `vendordeps/AdvantageKit.json`. Anything it
  cannot read becomes a warning that names the line. `libraries.ts` drops what
  WPILib or a vendordep provides (their version wins), adds the repositories
  of the build.gradle and the vendordeps, Maven Central and GradleRIO's
  frcmaven, and sorts the rest into `RobotProject.libraries`: `compile` and
  `runtime` go on the compile classpath and the robot's, `processors` only on
  javac's processor path. They go through the same `VendorLibraries`, with
  transitive compile and runtime dependencies from poms. As in Gradle, the
  highest version of a library wins, except that a vendordep keeps the
  versions it lists. These plain jars skip
  the native transform unless `classfile.ts` finds native methods or loaders
  in them. Maven Central and frcmaven send no CORS headers, so those downloads
  use the proxy, and a custom repository the proxy does not list works only
  when it sends CORS headers itself.
  `./gradlew test` (and Test Robot Code) builds the robot code, compiles `src/test/java` against it
  (`CompileOptions.classes`), and runs JUnit in a JVM of its own (`tester.ts`, the season's JVM) whose
  classpath is the shim, the tests, the robot code, the project's runtime jars and the
  `testRuntime` ones, then WPILib and the `frcdevwasm-junit.jar` driver. The shim stands in for the
  native HAL that GradleRIO's `configureTestTasks` puts on the test JVM's library path. `testing.ts` picks the jars:
  `testImplementation` and `testRuntimeOnly`, versions that a build.gradle leaves out from its
  `platform(...)` BOM (`VendorLibraries.managedVersions`), the launcher at the version of the
  platform engine (as JUnit's BOM gives it in Gradle), and the vintage engine for JUnit 4. The `test` block's
  `useJUnitPlatform()` and `systemProperty` lines are read (`gradle.ts`), and `./gradlew test` warns about its
  other settings (`testLogging`, `ignoreFailures`, tag filters). TestNG and `testAnnotationProcessor` are not
  read. `build` and `check` do not run the tests.
- `packages/sim-gui`: the earlier hand-written Preact replacement for the
  WPILib Sim GUI, kept as `wpilib.simGui: "classic"` until the WebAssembly
  build below has proven itself. Seasons in `WASM_SIM_YEARS` (`apps/ide/src/frc/simView.ts`)
  get the WebAssembly build, the others the classic GUI. Both seasons are in it. The classic
  GUI picks the HAL message keys by the project's year
  (`SimHost.year()`, `src/protocol.ts`; keys in `docs/bridge-protocol.md`).
- `packages/java`: Java code that runs inside CheerpJ.
  - `shim/`: pure-Java replacements for every WPILib JNI class (`HAL`,
    `*JNI`, `NetworkTablesJNI`, ...). It is placed ahead of the WPILib jars on
    the classpath, so the real WPILib classes call Java instead of native code.
  - `shim-2027/`: the same for WPILib 2027 (`org.wpilib.*` classes, Java 25). Its
    `shim/vendor/` has Impl classes for Phoenix 6 (the JNI release 26.50 and the
    java.lang.foreign release 26.70, which share the simulated devices in `ctre/phoenix6/sim`)
    and AdvantageKit's conduit. Every other 2027 vendor native is stubbed. Its tests are
    `shim-2027/test/java` (shim) and `test-2027/src` (HAL, and Phoenix 6 and AdvantageKit on the
    shim jar).
  - `shim/.../shim/vendor/`: hand-written replacements for vendor library
    natives, named `shim.vendor.<vendor>.<package>.<Class>Impl` (the vendor
    package without its `com.`/`org.` prefix). They are compiled against the
    vendor jars and shipped in the shim jar.
  - `vendor/`: `NativeStubber`, the bytecode transform (ASM) that makes every
    vendor jar run without native code. Each native method calls the matching
    static `Impl` method when one exists, or returns 0, false, an empty string
    or array, or null. A library that calls C through `java.lang.foreign` gets the
    same: its static downcall wrappers (a call of a handle in a static field) are replaced
    like natives, named by their class (`...ffi.PlatformNative` has `PlatformNativeImpl`),
    and the methods that create downcall handles return null, since java-web has no
    library to look symbols up in. Calls that load native libraries are removed, and
    `HttpClient.newHttpClient()` and `newBuilder()` are pointed at a client that
    fails requests, because CheerpJ has no sockets. It runs in the browser on
    jars the user downloaded: `VendorTransform` is the entry point, and the
    tools jar carries ASM and `impls.txt`, the list of Impl methods that the
    build writes from the shim classes. The build also runs it over the
    downloaded jars under `.cache/<year>/vendor-transformed` for the desktop tests.
  - `opencv/`: the natives of WPILib's OpenCV jar (`opencv-java`, on every robot classpath), in
    Java, for both seasons. It is what PhotonVision's simulation (`PhotonCameraSim`) needs: Mats in
    arrays (submat, row and col are copies), `projectPoints`, `undistortPoints`,
    `solvePnPGeneric` (a pose from a homography or DLT, refined on the reprojection error, with a
    second pose for IPPE flags), `boundingRect`, `minAreaRect`, `convexHull`, `contourArea`,
    `reduce`, `add`, `subtract`, `multiply`, `divide`, and `cvtColor` between gray, BGR and RGB. Every other native returns its default, so drawing and filtering calls do
    nothing. `build:java` runs `NativeStubber` over the jar as downloaded (kept in
    `.cache/<year>/opencv/`) and writes the result over `runtime/<year>/lib/opencv-java-*.jar`.
  - `tools/`: the compile driver that wraps javac, and `SwingHost`, which runs
    a Swing app and lists and unpacks its files.
  - `junit/`: `TestRunner`, the JUnit Platform launcher that `./gradlew test` runs in its own JVM. It
    prints what Gradle's console prints with the logging GradleRIO sets (every result, test output,
    failures with their stack down to the test class, `N tests completed, M failed`). It compiles against
    the launcher that fetch-deps puts in `.cache/junit`, and runs with the one of the project's
    dependencies. Built into `runtime/<year>/frcdevwasm-junit.jar`.
  - `bridge/`: `Bridge`, the message channel between Java and the page. See
    `docs/bridge-protocol.md`.
  - `smartdashboard/`: SmartDashboard bundles the NetworkTables 3 API of
    2020, so it gets its own replacements for `NetworkTablesJNI` and
    `WPIUtilJNI` instead of the shim's. `NtClient` is its entry table and
    talks to the page's NT4 server over the Bridge (`nt.hello` and the
    messages in "NetworkTables client" of `docs/bridge-protocol.md`).
- `packages/wpigui-tools`: WPILib's ImGui tools (Glass, OutlineViewer,
  DataLogTool, SysId, and `simgui`, the real `halsim_gui` Sim GUI) built from allwpilib C++ to WebAssembly with Emscripten,
  no threads, once per season into `runtime/<year>/tools/`. Shared libraries (wpiutil,
  ntcore with a browser WebSocket transport, imgui, wpigui, libglass) plus one small
  entry per app in `targets/<year>.mjs`. Upstream changes are small patches in
  `patches/<year>/`, applied to a copy of the sources. Where a tool would start a
  thread, a patch runs the work on the main thread, in slices for log reading.
  Our own sources are in `src/` for 2026 and `src/2027/` for 2027 (`src/shell/` is
  shared). 2026 is allwpilib v2026.2.1 on GLFW (the emscripten port, WebGL2 through
  imgui's OpenGL3 backend). 2027 is v2027.0.0-alpha-8 on SDL3 (the emscripten port) with
  its 2D renderer: wpigui's own code is patched, there is no SDL GPU in a browser. 2027
  has a new JSON library, nanopb and upb protobuf, and nanosecond HAL time.
  - File access: `portable-file-dialogs` is replaced by `src/wpigui`'s version,
    which sends a `pick` message to `window.parent`. `src/shell/files.js` holds
    the protocol. The workspace shows up in MEMFS under `/ws` (workspace path
    `/workspace/a.wpilog` is `/ws/workspace/a.wpilog`). Files a tool writes
    there go back to the workspace when closed. The IDE side is
    `apps/ide/src/frc/toolFiles.ts`, which uses VS Code's own file dialogs.
    New tools get this for free.
  - Sim GUI (`simgui`): `halsim_gui` linked with the real HAL simulation
    (`hal` in `targets/<year>.mjs`, timing replaced by `MockHooks.cpp`). It
    finds the `SimHost` the IDE offers at `window.parent.frcDevWasmSim`.
    `simgui/Mirror.cpp` applies the robot's halsim_ws messages to the HAL
    data and sends the GUI's input changes back, through the `halsim_ws_core`
    providers (the `<` output keys and SimDevices it handles itself). Values
    from the robot are applied with a flag set so they are not echoed. NT views
    use the in-page NT4 server like Glass. Only what halsim_ws carries is
    mirrored: no CAN or gyro data, and SimDevice numbers are doubles. Like
    halsim_gui it keeps `simgui.json`, `simgui-ds.json` and `simgui-window.json`
    in the robot project folder (the SimHost gives it the path, `files.js` reads
    and writes them through `/ws`), and in the browser without a project. The
    GUI draws at most 30 frames per second, and 10 after a second without input
    (a connected gamepad counts as input), because the robot JVM shares its thread
    and presenting the canvas is the expensive part. In 2027 the Teleoperated entry only
    selects the mode and the Enable button enables the robot, once the program has started
    (the Mirror sets it). Op modes are mirrored through three DriverStation keys of ours
    (`>opmode`, `<opmode_options`, `<program_state`, see `docs/bridge-protocol.md`), so the OpMode
    box lists what an `OpModeRobot` publishes and the GOOD, BAD and NONE mark follows the program's
    control word. A robot that publishes no options (timed and command-based) enables as before.
- `apps/ide/src/frc/terminal/`: the FRC Shell, the default terminal profile. A
  bash shell behind a `vscode.Pseudoterminal` (`pty.ts`, `readline.ts`). The
  language is just-bash, a bash interpreter in TypeScript (`shell.ts`), running
  on the workspace files (`workspacefs.ts`). Commands are just-bash's own (echo,
  sed, awk, jq, grep, find, diff, xargs, gzip), uutils coreutils in a worker
  (`coreutils.ts`, `wasi.ts`), `tar` (`tar.ts`), `git` (below), `./gradlew` (`gradle.ts`, on the
  services behind the WPILib build and simulate commands) or `gh` (`ghcli.ts`
  over the GitHub REST API, `gh.ts` for the glue). It loads when the first
  terminal opens.
- `packages/git-wasm`: git itself, built to WebAssembly with Emscripten
  (`build.mjs`, the patch in `patches/`). There are no processes, so a child command
  (stash and merge start several) runs in a new instance created synchronously
  while its parent waits, and all instances share one in-memory file system
  (`src/host.mjs`). A child whose input is a file, such as the pack index-pack reads
  during a clone, opens that file itself instead of getting a copy. The terminal's `git` runs it in a worker
  (`apps/ide/src/git/wasm/`) on projects in the origin private file system: before
  a command the worker opens every file of the repository with a synchronous access
  handle, git reads the bytes it needs through them, and what it changed is written
  back afterwards. The files stay open for the next command in the same repository.
  A file open in the worker cannot be written from the page, so writes from the page
  wait while a command runs (`OpfsFileSystemProvider.exclusive`) and first have the
  worker close its files (`releaseFiles`). Remotes are reached with synchronous HTTP
  through a CORS proxy (`git.corsProxy`, or the git route of `wpilib.vendorProxy`),
  by a stand-in for git-remote-https in `src/pre.js`: fetches use protocol v2, and a
  push runs send-pack in stateless RPC mode as git-remote-https does, its request
  sent in one POST when it starts reading the reply. Every request goes through
  the proxy (the project's own by default; `git.corsProxy` and `wpilib.vendorProxy` can point to a
  self-hosted one), so a saved GitHub token, which is sent only on requests to github.com, passes through
  it too. When GitHub refuses a request and none is saved, the IDE signs in and runs the command again. In folders opened from disk, which have no
  synchronous access handles, git reads File snapshots with FileReaderSync, and the
  files are opened again for each command, since they can change outside the browser.
  Browsers without OPFS use isomorphic-git (`apps/ide/src/git/cli.ts`) and the IDE's
  own Source Control (`apps/ide/src/git/`).
- `packages/vscode-git`: VS Code's own Git, Git Base and GitHub extensions, from the VS Code
  commit the workbench is built from, bundled for the browser (`build.mjs`). Node's
  modules are replaced by `shims/`: `child_process` starts git through
  `frcDevWasmGit`, which the IDE sets (`apps/ide/src/git/vscodeGit.ts`), and `fs` is
  the workbench file system. They run in the page's extension host for projects in
  the origin private file system and folders opened from disk, in place of the IDE's
  own Source Control. Clones go
  into `/workspace` and open in place (`git.defaultCloneDirectory`,
  `git.openAfterClone`). A link with `?github=owner/repo` (`apps/ide/src/frc/githubLink.ts`) asks, then
  runs `git.clone` on that repository the same way, or offers a project of that name already in the browser;
  `test:github-link` checks the prompt without cloning. The IDE grants workspace trust requests
  (`apps/ide/src/workspaceTrust.ts`), since the extension opens only trusted
  repositories. The GitHub extension (Clone from GitHub, Publish to GitHub) signs in
  through a `github` authentication provider the IDE registers
  (`apps/ide/src/git/githubSessions.ts`), backed by the token and sign-in gh and git
  use.
- `packages/vscode-wpilib`: the WPILib VS Code extension (vscode-wpilib, the release each
  season's installer ships), built per season (`build.mjs`, `--year`) into
  `runtime/<year>/vscode-wpilib/` and bundled for the browser: its TypeScript at the
  release tag with small `patches/<year>/`, and the resources (templates, examples, webview
  pages) of the released vsix. Node's modules are `shims/` (`fs` serves the extension's
  own folder from the site, which also answers the few synchronous calls; 2027 adds
  `fs/promises`, a promise `glob` and the global `fetch`, which goes through the IDE's
  proxy), and the modules that start processes are `replacements/<year>/`. A window loads
  the extension of the open project's season (`gradleRioYear`; the default season without
  a project), read before the workbench starts (`apps/ide/src/frc/wpilibYear.ts`). Create a New
  Project asks for the season when the site serves more than one. For the other season it
  stores `frc-dev-wasm.creatorYear` and reloads, and that season's extension loads and opens
  its creator. Each season behaves as its desktop extension does. They call the IDE through
  `frcDevWasmWpilib` (`host.d.ts`, set in `apps/ide/src/frc/vscodeWpilib.ts`): Gradle
  command lines run in the FRC Shell in a task terminal, as VS Code runs shell tasks,
  simulate runs the robot JVM with the HAL extensions picked (the Sim GUI for
  `halsim_gui`) on the jar that its Gradle task just built, without a second build, and
  Start Tool lists the IDE's tools. `./gradlew simulateExternalJavaRelease` (2026) writes `build/sim/release_java.json`, and
  `simulateExternalJava` (2027) writes `build/sim/java.json` with `taskPath` and `debugPort`,
  as GradleRIO does.
  `Test Robot Code` and `./gradlew test` run JUnit tests (below).
  There is no C++, deploy, debugging or RioLog. It contributes the WPILib
  commands, project creator and vendor dependency view; the IDE's own extension keeps
  the browser-only commands, and has its earlier versions of the others for when the
  extension is not built. Its commands need the `isWorkspaceTrusted` context key, which
  the IDE sets.
- `apps/ide/src/copilot/`: GitHub Copilot in VS Code's own Chat view. Chat is opt-in
  (`enabled.ts`, localStorage `frc-dev-wasm.copilot`): its services (`services.ts`: chat, MCP,
  notebook) are imported before the workbench starts only when it is on, since they add about a
  second to startup on a low-end computer. The real Copilot extension cannot run: GitHub refuses its
  token exchange (`copilot_internal/v2/token`) for other apps. Instead a built-in extension
  (`extension.ts`) registers the `copilot` language model vendor and one default participant per mode,
  as Copilot does: Ask gets the read-only tools, Agent all of them. `api.ts` calls
  `api.githubcopilot.com` with the GitHub sign-in token, which it accepts directly and allows from
  browsers; GitHub gives tokens of the project's OAuth app only its older models. The participant runs
  the tool loop. `tools.ts` holds tools named like Copilot's (read_file, replace_string_in_file,
  run_in_terminal, ...) that work on the open folder; commands run in an FRC Shell named Copilot
  (`createAgentPty`). VS Code's own terminal tools need a shell process, so they are not offered. Edits
  are applied directly, not as chat editing session edits: that session's external edits never start
  here. `account.ts` replaces VS Code's default account with the sign-in and the plan from
  `copilot_internal/user`, so the Copilot status item shows real usage, and points VS Code's Copilot
  setup command at the sign-in. OpenCode Zen's free models are off limits: OpenCode serves them only to
  its own client.
  Two read-only tools, `wpilib_api` and `wpilib_docs` (`wpilibDocs.ts`), give the model the open project's
  season (`season.ts`, the default season without a project): the javadoc class page or member of
  github.wpilib.org (`release` for 2026, `beta` for 2027, found through javadoc's search indexes and its
  modules) and frc-docs pages found through Sphinx's `searchindex.js` (`stable` and `latest`). Both sites
  allow CORS, so the page fetches them directly without the proxy, and each index loads once per session.
- `packages/java-web`: `build.mjs` copies java-web (github.com/Project516/java-web, AGPL-3.0, a JVM in TypeScript
  that runs Java 25 bytecode on the real OpenJDK 25 class library) from its npm package `@project516/java-web`, at the
  exact version in `package.json`, into `apps/ide/public/runtime/java-web/`: `java-web.js`, its worker, `boot.pack`
  and `jdk/`: `image.jar` (java.base and the JDK image's index, 31 MB) and one `<module>.jar` per other module, all
  read with HTTP Range (`.jar` so hosts such as GitHub Pages do not compress them, since they apply Range to the
  compressed bytes), `app.pack` (what a typical program reads beyond boot, loaded on first use), `javac.pack` (javac
  loads it on first use) and the OpenJDK license notices, shared by the seasons that use it. `jdk.localedata.jar`
  (26 MB) stays out: the IDE does not turn on java-web's `localeData`, so other locales format as English. A browser
  fetches only the parts of the modules a program uses. It skips unless a season
  of `WPILIB_YEARS` runs on it (`--force` copies anyway). `check-dist.sh` refuses a dist without it.
  `pnpm --filter @frc-dev-wasm/java-web test` (`test/frc.mjs`) runs the 2027 HAL and shim tests on java-web under
  Node with the package's `runNode` and checks that as many run as on HotSpot, that javac compiles WPILib's
  templates to the same bytes, and that a TimedRobot (`test/robot`) keeps its loop time. It needs
  `fetch-deps --year 2027`, rebuilds the 2027 shim and its tests first, and takes up to 2.2 GB. java-web's own tests have no FRC code, so after each java-web version bump run this and
  `test:2027`. Failures caused by java-web are reported upstream with a minimal Java reproducer.
- `packages/proxy`: a Cloudflare Worker, the one server-side piece. It
  forwards vendor maven downloads (and the vendordep marketplace and GradleRIO
  versions the WPILib extension reads), git requests and the two requests of GitHub's
  device sign-in, none of which send CORS headers. Allowlisted repositories and
  endpoints only. The
  project's instance is `https://frc-proxy.project516.dev`.
- `scripts/fetch-deps.mjs`: for each year in `packages/runtime/src/years.ts`
  (`WPILIB_YEARS`, or `--year <y>`), downloads the WPILib jars, templates,
  examples, the newest vendor libraries (as published, into `.cache/<year>/vendor`,
  for the `Impl` classes and desktop tests only; `olderVendorJars` adds a release that projects
  still use, such as Phoenix 6 26.50, which the Impl classes also compile against) and the year's JDK. Year-specific files go to
  `apps/ide/public/runtime/<year>/`: the vendordep descriptions and `index.json`
  in `vendor/`, the redistributable jars (WPILib vendordeps, annotation
  processors) in `bundled/`, and Elastic's web build in `tools/elastic/` (the release of that
  season, checksum pinned). In 2027, CameraServer, cscore, AprilTag and OpenCV are vendordeps, as in
  GradleRIO: their jars (`optional` in `manifest.json`) stay off the robot classpath, are served from
  `lib/`, and `bundled` in `vendor/index.json` points a project's vendordep at them. The shim build
  uses them. No vendor jar is served. `pnpm build:java` (also
  `--year`) then writes the shim jar, the tools jar and `vendor/transform.json`
  there. Shared files (tools, git, terminal, extensions) stay in `runtime/`.
  Outputs go to `.cache/` and `apps/ide/public/runtime/`, both ignored by git.
- `scripts/build-terminal.mjs`: builds uutils coreutils for `wasm32-wasip1` into
  `apps/ide/public/runtime/terminal/coreutils.wasm`. Separate from `fetch-deps`
  because it installs a Rust toolchain into `.cache/rust`.

## Glossary

- **CheerpJ**: a JVM that runs in the browser (WebAssembly and JS). Loaded
  from `https://cjrtnc.leaningtech.com/5.0/loader.js`, and Swing apps from 4.3,
  since they do not open on 5.0. Its Java 17 runtime includes `jdk.compiler`, as does
  java-web's, so the site serves no javac. It runs no finalizers or Cleaners, and clears weak
  references only sometimes and never enqueues them, so shim code cannot rely on
  them to free anything. Its `Arrays.equals` and `Arrays.mismatch` on `double[]`
  give wrong results when the first elements match (4.3 and 5.0), so shim code
  compares double arrays itself.
- **Shim**: the jar of replacement JNI classes in `packages/java/shim`.
- **Year**: a WPILib season, such as 2026. Each has its own runtime folder
  (`runtime/<year>/`), shim and tools jars, JDK and vendor index. A project's
  year is the year its GradleRIO plugin version starts with (`gradleRioYear`).
- **Java packages**: our own Java code lives under `dev.project516.frcdevwasm`.
  Shim implementations follow `dev.project516.frcdevwasm.shim.<package>.<Class>Impl`,
  where `<package>` is the WPILib package without `edu.wpi.first.` (2026) or `org.wpilib.` (2027).
- **Bridge**: the two native methods (`Bridge.send`, `Bridge.receive`) that
  the runtime implements in JS. Everything Java tells the page, and the page
  tells Java, goes through them as JSON.
- **Sim GUI**: the simulation window that the desktop tools open with
  `simulateJava`. Here it is a panel in the workbench, by default `halsim_gui`
  compiled to WebAssembly. It closes when the robot program ends, as the desktop
  window does. A layout saved in a bigger window is drawn scaled down to fit the
  panel.
- **HAL mirror**: the real HAL simulation, compiled into the Sim GUI. The
  robot's halsim_ws messages are applied to it, and the inputs the GUI changes
  go back as messages. The Java shim stays the authority.
- **java-web**: the browser JVM of WPILib 2027 (Java 25), a separate project. It offers
  CheerpJ's API (`cheerpjInit`, `cheerpjRunLibrary`, natives), so `Jvm` drives both.
- **Robot JVM / compile JVM**: separate CheerpJ or java-web instances (by year), each in its own
  same-origin iframe. Stopping a robot program means removing its iframe.
- **FRC Shell**: the terminal profile (`wpilib.shell`) in the workbench. A bash
  shell (just-bash) in TypeScript, not a program in the robot JVM.
- **Snapshot**: how a WASI command sees files. Before a coreutils command runs,
  the files it can touch are copied into an in-memory tree. After it runs, the
  changes are written back. A stub is a file loaded as name and size only, for
  commands that never read contents (ls, rm, mkdir, touch).
- **Open project**: the workbench folder (`workspaceFolders[0]`). Projects are
  stored as folders under `/workspace` in the origin private file system
  (`apps/ide/src/opfs.ts`, folder `frc-dev-wasm-files`), or in IndexedDB where the
  browser cannot write to it from the page. Opening one saves its path
  in `localStorage` (`frc-dev-wasm.folder`) and reloads, the same way Open Folder
  does for folders on disk (`apps/ide/src/workspace.ts`, `frc/projects.ts`).
- **CheerpJ mounts**: `/app/` is the site root (read only), `/str/` is a flat
  JS-to-Java file area (no directories), `/files/` is persistent IndexedDB
  storage.

## Working here

- Package manager: pnpm. Node 26 or newer. A JDK is not needed on the host:
  `pnpm fetch-deps` downloads each year's into `.cache/jdk/<release>` and the Java
  build uses it (path in `.cache/<year>/java-home`).
- `pnpm build:terminal` needs a C linker on the host (gcc or clang; Rust build scripts link
  with it). It compiles 2 crates at a time; `TERMINAL_JOBS=N` changes that.
- 2026 Java code targets Java 17 (`--release 17`) because CheerpJ runs Java 17.
  2027 targets Java 25 (`--release 25`), as WPILib 2027 and its JDK do.
- A new WPILib release: `node scripts/diff-natives.mjs <old-jar-dir> <new-jar-dir>` lists the native
  methods that its jars add or remove, so the shim can follow. `node packages/java/build.mjs --year <y>`
  then prints how many natives have an Impl.
- Deploys: `.github/workflows/deploy.yml` runs `pnpm build:all` and publishes
  `apps/ide/dist` to GitHub Pages (`frcdev.project516.dev`) when a release is published (and by
  hand from the Actions tab). It caches `.cache` and `apps/ide/public/runtime`, so only what
  changed is rebuilt. It runs `scripts/check-dist.sh` first, which refuses a dist without every
  separately built part. `.github/workflows/deploy-proxy.yml` deploys the proxy Worker when its code changes.
- CI: `.github/workflows/ci.yml` runs the type checks and Node-only tests, then `pnpm build:all`
  with the same cache, then the suites in three parallel groups (`scripts/ci-suites.sh ide|java|teams`)
  on each pull request (and a manual run). The `teams` group (real team projects) reports without failing the run.
  Only master and the weekly scheduled run save the build cache, and restoring it each week keeps GitHub from
  evicting it. `scripts/ci-changes.sh` picks the groups from the changed files: a pull request
  that changes only docs or the proxy builds nothing, and a file no rule names runs every group. CI reaches the proxy at
  its own address, the `CI_PROXY_URL` secret: `scripts/ci-proxy.mjs` (loaded with `NODE_OPTIONS=--import`) sends
  the test browsers' proxy requests there, and pull requests from forks, which get no secrets, can fail on
  clones and vendor downloads. Tests that type into a VS Code input box
  first wait for `quickInputReady` (`apps/ide/tests/quickInput.mjs`): keys sent before the box has focus are lost. On master it only builds, which keeps the cache warm, and uploads the site to
  the R2 bucket behind `frcdev-nightly.project516.dev`, the nightly, which calls itself `X.Y.Z-nightly`
  in Help: About (`FRC_DEV_CHANNEL`). Secrets live in the `cloudflare`
  environment, which only master can use.
- CheerpJ needs HTTP Range support from the server. Vite's dev and preview
  servers have it; `python -m http.server` does not.
- On a CheerpJ classpath a directory entry needs a trailing slash, and a
  directory under `/app/` does not work at all. Use jars.
- Tests: Java shim and HAL tests run on the desktop JDK of each year
  (`pnpm --filter @frc-dev-wasm/java test`, `--year 2027` for one season). Browser tests use
  `playwright-core` with a Chromium that is already installed; do not download
  browsers in scripts. `pnpm --filter @frc-dev-wasm/ide test:spectrum` clones
  a real team project (Spectrum3847/2026-Spectrum) at a pinned commit (`SHA` in `tests/spectrum.mjs`), builds
  and simulates it (3 GitHub API requests per run). `SPECTRUM_HAR=<file>` records the clone
  once and replays it afterwards. `pnpm --filter @frc-dev-wasm/ide test:tools` runs
  DataLogTool in the IDE against the browser workspace. `pnpm --filter @frc-dev-wasm/ide test:smartdashboard` opens
  SmartDashboard against a simulated robot.
  `pnpm --filter @frc-dev-wasm/runtime test:vendor` tests vendor jar downloads
  under Node with a fake network. `pnpm --filter @frc-dev-wasm/ide test:copilot`
  runs the Copilot client under Node and chats in the IDE against a mocked Copilot API.
  `pnpm --filter @frc-dev-wasm/ide test:2027` creates, builds and
  simulates 2027 projects on java-web; it skips unless the builds
  (`fetch-deps`, `build:java`, `build:vscode-wpilib`, `build:java-web`, `pnpm build`) exist.
  `pnpm --filter @frc-dev-wasm/ide test:teams2026` and `test:teams2027` clone real team projects of
  that season at pinned commits (`tests/teams.mjs`) and report each stage (clone, pin, vendor download and
  transform, build, simulate start, robot loop) on its own. Known gaps print as expected failures;
  `TEAMS_STRICT=1` makes them fail the run. `TEAMS_HAR=<folder>` records and replays the network traffic,
  `TEAMS_REPOS=<names>` picks repos by name (comma separated).
  `pnpm --filter @frc-dev-wasm/ide test:junit` creates a project of each season, adds a passing and a failing
  JUnit test, and runs `./gradlew test` and Test Robot Code. `test:junit-team` clones a real project with tests
  (`JUNIT_REPO`) and runs `./gradlew test` on it. `node test/run.mjs junit` in `packages/runtime` (with
  `WPILIB_YEAR`, and `JUNIT_VARIANT=mockito-<version>` or `jupiter-<version>`) runs the test steps without the
  workbench. `pnpm --filter @frc-dev-wasm/java test` also checks the launcher's output against
  `packages/java/junit/test/expected.txt`, which is what Gradle prints for those fixtures.

### Commits and pull requests

Commit messages and pull request titles follow [Conventional Commits](https://www.conventionalcommits.org):
`type(scope): summary`, for example `fix(proxy): refuse git hosts that are addresses`. Pull requests are
squash-merged, so the title becomes the commit on `master`.

- Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`.
- The scope is optional: the package or area it changes (`ide`, `runtime`, `java`, `shim`, `proxy`, `git-wasm`,
  `vscode-git`, `vscode-wpilib`, `wpigui-tools`, `sim-gui`, `java-web`, `terminal`, `copilot`, `deps`).
- The summary is one short sentence in the imperative, lower case, no period.
- A breaking change for users or self-hosters (a removed setting, a proxy route that changes) gets a `!` after
  the type and says what to do in the body.
- Releases: a `chore: release X.Y.Z` pull request sets the root `package.json` version. After it merges,
  `node scripts/release-notes.mjs vPREV vX.Y.Z` prints the merged pull requests grouped by type; put a few
  sentences for users above them and publish with `gh release create vX.Y.Z --notes-file ...`, which deploys
  the site.

A pull request description follows `.github/pull_request_template.md`.

### Building WPILib tools (Glass, OutlineViewer, DataLogTool, SysId, Sim GUI)

`pnpm build:glass` (or `build:outlineviewer`, `build:datalogtool`, `build:sysid`,
`build:simgui`,
`build:wpigui-tools` for all) installs emsdk 6.0.10 into `.cache/emsdk-6.0.10`, fetches the
allwpilib sources of each season (2026 into `.cache/`, 2027 into `.cache/2027/`), and builds into
`apps/ide/public/runtime/<year>/tools/<tool>/`. `--year Y` (repeatable) picks seasons,
the default is every season the site serves. The first build takes a long time; later
ones only recompile what changed. Pass `--jobs N` to change the default of 2
parallel compiles. Needs `git`, `tar` and `python3` on the host, nothing else.
Each built tool gets `source.txt`, a hash of what its season's build reads from this package (`source.mjs`).
`pnpm build` and `check-dist.sh` stop when a tool's hash differs from the sources, and warn about a tool
without one.
A patch must be in plain unified format (`---`/`+++` lines, no `diff --git` or `index`
lines): `git apply` skips a patch with git headers without an error when the work tree is
inside a repository. `pnpm --filter @frc-dev-wasm/wpigui-tools test` loads each season's build in headless
Chromium against a simulated robot (`test --year 2027` for one season; 2027 starts the
2027 shim robot on java-web). Both use a lot of memory, so run them one at a time.

### Git

`pnpm build:git` builds git 2.56.0 into `apps/ide/public/runtime/git/` with the
same emsdk. The first build takes a long time; `--jobs N` (or `GIT_JOBS=N`) as above. Without it the terminal
keeps isomorphic-git. `pnpm build:vscode-git` builds the Git extensions; it needs git
on the host to fetch their sources. `pnpm --filter @frc-dev-wasm/git-wasm test` runs commands with
desktop git and the build under Node and compares the output byte for byte, then
clones, fetches, pulls and pushes over HTTP with desktop git's http-backend. It needs git on
the host.

### WPILib extension

`pnpm build:vscode-wpilib` builds each season of `WPILIB_YEARS` into
`apps/ide/public/runtime/<year>/vscode-wpilib/` (`--year` builds one). It needs git on the host to fetch the sources. Each season's
version, commit and vsix checksum are pinned in `build.mjs`; a new release means updating all
three and checking that `patches/<year>/` still applies. Browser tests create projects and
simulate through it (`apps/ide/tests/wpilib.mjs`). `pnpm --filter @frc-dev-wasm/ide test:year`
tests the season choice and hand-off under Node. `SMOKE_CPU_THROTTLE=10` slows the page of
`test:smoke` tenfold, as a low-end computer does, to bring out races between the workbench, webviews
and the extension host. One such race: when a pick closes, VS Code hands focus back to the webview
that opened it, with a message that can arrive after the next pick has opened. That pick loses
focus and closes. The 2026 project creator asks three picks in a row, so its picks set
`ignoreFocusOut` (`patches/2026/browser.patch`).

### Terminal

- `pnpm build:terminal` builds the WASI binary (about 7 minutes cold). Without
  it the shell still works, and coreutils commands say how to build it. The
  utility list is `apps/ide/src/frc/terminal/utilities.ts`; the script and the
  shell both read it.
- wasi-libc resolves every path against `/`, so the WASI host exposes the
  working directory as `/` and `relativizeArgs` rewrites absolute operands.
  `std::fs::canonicalize` is unsupported on WASI, so `cp -r` is done by the shell
  (`fileops.ts`), and so are `mv` and `rm -r`: moving and deleting trees go straight to
  `workspace.fs.rename` and `delete`, not through a snapshot.
- just-bash runs one script at a time, forgets everything after it, and returns
  the output when the script ends. `shell.ts` carries variables, the working
  directory, functions, arrays, aliases, `shopt` and `set --` from one line to
  the next, and `rewrite.ts` edits each parsed line first: error output is merged
  into the output where both go to the terminal, so they keep their order, and
  commands that may print as they go (`./gradlew`, git, uutils) are renamed to a
  copy that writes to the terminal. Such a statement runs as a script of its own,
  so output before it comes first. Limits: a statement that also runs echo, printf
  or other commands outside the list prints everything when it ends, as does
  anything in a pipe, `$( )` or function, and errors inside those come after
  the output.
- A uutils command that reaches 16 MB of output is stopped with a message and its
  output is dropped, so `yes > f` leaves an empty file.
- Not available: background jobs (`&` runs in the foreground), `trap`, programs
  that read typed input.
- Keep `wasi.ts`, `paths.ts`, `fileops.ts` and `io.ts` free of VS Code and DOM
  imports and of TypeScript parameter properties, and import each other with
  `.ts` extensions: `tests/coreutils.mjs`, `tests/fileops.mjs` and `tests/shell.mjs`
  load them under Node. `tests/shell.mjs` loads the shell itself through Vite, over
  an in-memory workspace. `pnpm --filter @frc-dev-wasm/ide test:terminal` runs
  those and the browser test.

### GitHub CLI

- `gh` is not the real GitHub CLI. The Go build does not fit a low-end browser
  (75 MB of wasm that did not get to run `gh api`), so `ghcli.ts` implements
  `api`, `auth`, `repo view`, `issue list|view|create` and `pr list|view|create|status`
  over `api.github.com`, which allows CORS. Output follows gh's non-TTY format.
  Anything else says it is not supported. Keep it small: add a command when
  someone needs it.
- `ghcli.ts` stays free of VS Code and DOM imports, and takes `fetch`, the token
  and the repository from a host object, so `tests/ghcli.mjs` runs it under Node
  against a stubbed `fetch`. `gh.ts` supplies the real ones. The default repository
  is the origin remote of the working directory, read with the built-in `git`.
- The token is the one `git push` uses, in VS Code secret storage. The IDE keeps that in
  localStorage (`apps/ide/src/secrets.ts`), as VS Code's web build does, so a sign-in lasts
  across reloads. Signing in
  (`apps/ide/src/git/auth.ts`) uses GitHub's device flow through the proxy
  (`github.com/login` has no CORS), with the project's OAuth App client ID, or asks
  for a personal access token when the proxy has no sign-in route. `gh auth login`
  signs in or reads a token with `--with-token`, and `gh auth logout` forgets it. A
  request that needs a token and has none signs in and retries.
- `pnpm --filter @frc-dev-wasm/ide test:gh` runs gh in the shell against a mocked
  `api.github.com`.

### Low-memory machines

The project must stay buildable on machines with little memory. Heavy commands
can take such a machine down.

- No docker or container builds.
- Run builds and browser tests one at a time, and close the headless browser when a test ends.
- Kill processes by PID, never with `pkill -f`. It matches your own shell.
