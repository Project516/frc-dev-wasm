package dev.project516.frcdevwasm.test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.spi.ToolProvider;

/**
 * Compiles a probe class against a vendor library and runs it on the shim. The vendor jars are the
 * ones that build.mjs --test transformed under .cache, so the probe sees what a robot sees.
 */
final class VendorProbe {
  private VendorProbe() {}

  /**
   * Runs {@code probe.Probe.run()} from {@code source} against the transformed jar of a library:
   * {@code artifactDir} is its group and artifact as a path under the vendor folder, such as
   * {@code com/ctre/phoenix6/wpiapi-java}, and {@code versionPrefix} picks the version. Returns the
   * text of the probe, or null when that library was not downloaded.
   */
  static String run(String artifactDir, String versionPrefix, String source) throws Exception {
    File runtime = new File(System.getProperty("frcdevwasm.runtime"));
    File transformed = new File(System.getProperty("frcdevwasm.vendorTransformed"));
    File[] versions = new File(transformed, "m2/" + artifactDir).listFiles(f -> f.getName().startsWith(versionPrefix));
    if (versions == null || versions.length == 0) {
      return null;
    }
    Arrays.sort(versions);
    File versionDir = versions[versions.length - 1];
    File[] jars = versionDir.listFiles(f -> f.getName().endsWith(".jar"));
    if (jars == null || jars.length == 0) {
      return null;
    }

    List<File> classpath = new ArrayList<>();
    classpath.add(new File(runtime, "frcdevwasm-shim.jar"));
    classpath.addAll(Arrays.asList(jars));
    for (File f : new File(runtime, "lib").listFiles()) {
      if (f.getName().endsWith(".jar") && !f.getName().startsWith("opencv")) {
        classpath.add(f);
      }
    }
    StringBuilder path = new StringBuilder();
    List<URL> urls = new ArrayList<>();
    for (File f : classpath) {
      path.append(f.getPath()).append(File.pathSeparator);
      urls.add(f.toURI().toURL());
    }

    Path dir = Files.createTempDirectory("vendor-probe");
    Path file = dir.resolve("probe/Probe.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, source);
    int status =
        ToolProvider.findFirst("javac")
            .orElseThrow()
            .run(System.out, System.err, "-d", dir.toString(), "-cp", path.toString(), file.toString());
    Tests.eq(0, status, "javac status");
    urls.add(dir.toUri().toURL());

    URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    // Service loaders look through the context class loader
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(loader);
    try {
      return (String) loader.loadClass("probe.Probe").getMethod("run").invoke(null);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /** Fails unless each expected line is in the probe output. */
  static void expectLines(String result, String... expected) {
    for (String line : expected) {
      Tests.check(result.contains(line + "\n"), "expected " + line + " in:\n" + result);
    }
  }

  static double num(String result, String key) {
    for (String line : result.split("\n")) {
      if (line.startsWith(key + "=")) {
        return Double.parseDouble(line.substring(key.length() + 1));
      }
    }
    throw new AssertionError("no " + key + " in:\n" + result);
  }
}
