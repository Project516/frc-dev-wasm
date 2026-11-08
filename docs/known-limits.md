# Known limits

What the browser IDE does not do yet, or does differently from the desktop WPILib tools.

## Browsers

- Use a Chromium browser: Chrome, Edge or a Chromebook. That is where it is tested.
- Firefox: checked with Firefox 159, where the workbench opens and the WPILib project creator works. Creating, building
  and simulating a project are not tested there: the browser tests cannot drive webviews in Playwright's Firefox build.
- Safari: checked only with WebKit on Linux, not on a Mac. Creating, building and simulating a project, the tools and
  the Sim GUI work. Ctrl+Shift+P does not open the Command Palette there, F1 does. Cmd+Shift+P on a Mac is not tested.
- **Open Folder** for a folder on your disk needs the File System Access API, which only Chromium browsers have.
  Elsewhere, keep projects in the browser and use **Export Project as Zip**.

## Your projects

- Projects you create, clone or import live in the browser's own storage for this site. **WPILib: Clear Site
  Data**, or clearing the site's data in the browser, deletes them. Push to GitHub or use **Export Project as Zip**
  to keep a copy.
- One robot project per window, as on the desktop.

## Network

- The 2026 Java runtime (CheerpJ) loads from its own CDN, so the page needs a connection when it starts.
- Vendor libraries (CTRE, REV and others) download from the vendors the first time a project needs them, then come
  from the browser's cache. Without a connection, import the jars with **WPILib: Import Vendor Jars from Disk**.

## Building

- Gradle does not run. The IDE reads `build.gradle` and does what GradleRIO would for building and simulating:
  dependencies, repositories, annotation processors, the gversion plugin and `./gradlew build` and `simulateJava` (`run` in 2027).
  Other plugins and custom tasks (Spotless and the like) are skipped, and the build prints a warning for lines it
  cannot read.
- `./gradlew test` and Test Robot Code run JUnit 4, 5 and 6 tests. Mockito does not work yet: Mockito 5 needs an instrumentation
  agent that neither JVM has, and Mockito 4 fails on a CheerpJ gap in 2026. `build` and `check` do not run the tests,
  and no HTML report or `build/test-results` is written.
- No C++, deploy to a robot, debugging or RioLog.
- A WPILib 2027 project builds with the newest 2027 alpha the site serves, even when `build.gradle` names an older
  one. The build warns when the versions differ.

## Simulation

- The Sim GUI only shows what the robot sends over the simulation WebSocket protocol: no CAN or gyro data, and
  SimDevice numbers are doubles.
- Vendor motor controllers and sensors are simulated by this project, not by the vendors' firmware, and some
  behaviour is approximated. See [vendor-sim.md](vendor-sim.md).
- OpenCV runs without its native library: what PhotonVision's camera simulation needs (matrices, projection, pose
  estimation, contours) is computed, and drawing, filtering and camera frames do nothing.
- 2026: CheerpJ runs no finalizers or Cleaners, so an OpenCV Mat is never freed. A zero-filled Mat gets memory only
  when something writes to it, so PhotonVision's blank camera frames cost nothing, but every other Mat stays allocated.
- 2026: `Arrays.equals` and `Arrays.mismatch` on `double[]` give wrong results or throw when the first elements match
  (a CheerpJ bug), so robot code that relies on them can see two different arrays as equal.
- 2027: REVLib devices are not simulated.

## Speed

- The first build in a window starts the Java compiler, which takes longest. Later builds are faster, and a build
  with no changes since the last one reuses its classes, as Gradle does.
- Lombok's `@ExtensionMethod` makes builds much slower than on the desktop: Lombok checks the whole method again for
  each method call in a class that uses it. A team project that uses it in 8 files takes more than 20 minutes to build.
- 2027 compiles on java-web are slower and use more memory than 2026 ones: a large team project takes close to a
  minute and more than 1 GB on a laptop.
