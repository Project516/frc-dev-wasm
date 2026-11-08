# Shim tests

Desktop JDK 17 tests for the NetworkTables, wpimath, cscore, apriltag and wpinet shims. They run
against the real shim jar, so the unmodified WPILib Java API is exercised through the generated JNI
classes.

```sh
pnpm --filter @frc-dev-wasm/java test             # everything, including the HAL tests in ../../test
pnpm --filter @frc-dev-wasm/java test NtJniTest   # only classes whose name contains the filter
```

`java/dev/project516/frcdevwasm/shim/test/*Test.java`: every `public static void test*()` runs.
