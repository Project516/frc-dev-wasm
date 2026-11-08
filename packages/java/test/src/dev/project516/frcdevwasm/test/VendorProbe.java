package dev.project516.frcdevwasm.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;

/**
 * Compiles a probe class against a vendor library and runs it on the shim. The vendor jars are the
 * ones that build.mjs --test transformed under .cache, so the probe sees what a robot sees.
 */
final class VendorProbe {
  private VendorProbe() {}

  /**
   * Runs {@code probe.Probe.run()} from {@code source} and returns its text, or null when the
   * vendor index has no vendordep whose name starts with {@code vendordepName}.
   */
  static String run(String vendordepName, String source) throws Exception {
    File runtime = new File(System.getProperty("frcdevwasm.runtime"));
    File vendor = new File(System.getProperty("frcdevwasm.vendor"));
    File transformed = new File(System.getProperty("frcdevwasm.vendorTransformed"));
    File indexFile = new File(vendor, "index.json");
    if (!indexFile.exists()) {
      return null;
    }
    JsonNode index = new ObjectMapper().readTree(indexFile);
    Set<File> jars = new LinkedHashSet<>();
    for (JsonNode dep : index.get("vendordeps")) {
      if (dep.get("name").asText().startsWith(vendordepName)) {
        for (JsonNode key : dep.get("java")) {
          collect(index.get("maven"), key.asText(), transformed, jars);
        }
      }
    }
    if (jars.isEmpty()) {
      return null;
    }

    List<File> classpath = new ArrayList<>();
    classpath.add(new File(runtime, "frcdevwasm-shim.jar"));
    classpath.addAll(jars);
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
    return (String) loader.loadClass("probe.Probe").getMethod("run").invoke(null);
  }

  private static void collect(JsonNode maven, String key, File transformed, Set<File> into) {
    JsonNode entry = maven.get(key);
    if (entry == null || entry.isNull()) {
      return;
    }
    if (into.add(new File(transformed, entry.get("jar").asText()))) {
      for (JsonNode dep : entry.get("deps")) {
        collect(maven, dep.asText(), transformed, into);
      }
    }
  }

  /** Fails unless each expected line is in the probe output. */
  static void expectLines(String result, String... expected) {
    for (String line : expected) {
      Tests.check(result.contains(line + "\n"), "expected " + line + " in:\n" + result);
    }
  }
}
