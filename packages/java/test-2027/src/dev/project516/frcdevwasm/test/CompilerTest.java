package dev.project516.frcdevwasm.test;

import static dev.project516.frcdevwasm.test.Tests.check;
import static dev.project516.frcdevwasm.test.Tests.eq;

import dev.project516.frcdevwasm.tools.Compiler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.simulation.DriverStationSim;

/** Tests for {@link Compiler} with the 2027 annotation processors, run on the desktop JDK. */
public final class CompilerTest {
  private CompilerTest() {}

  public static void run(Tests t) {
    t.test("compiler 2027: command-based template compiles with the WPILib annotation processors",
        CompilerTest::commandTemplate);
    t.test("compiler 2027: OpModeRobot finds the op modes of a compiled project", CompilerTest::opModeTemplate);
    t.test("compiler 2027: avaje-jsonb generates an adapter", CompilerTest::avajeAdapter);
  }

  private static void commandTemplate() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      List<String> entries = new ArrayList<>();
      try (ZipFile templates = new ZipFile(runtime().resolve("wpilib/templates.zip").toFile())) {
        for (Enumeration<? extends ZipEntry> e = templates.entries(); e.hasMoreElements();) {
          ZipEntry entry = e.nextElement();
          String name = entry.getName();
          if (entry.isDirectory()) {
            continue;
          }
          if (name.startsWith("templates/commandv2/") && name.endsWith(".java")) {
            entries.add("first/robot/" + name.substring("templates/commandv2/".length()));
            entries.add(text(templates, entry).replace("org.wpilib.templates.commandv2", "first.robot"));
          } else if (name.equals("Main.java")) {
            entries.add("first/Main.java");
            entries.add(text(templates, entry).replace("package org.wpilib;", "package first;")
                .replace("org.wpilib.templates.timed.Robot::new", "first.robot.Robot::new"));
          }
        }
      }
      Path zip = zip(dir, entries.toArray(new String[0]));
      Path jar = dir.resolve("out.jar");
      String result = Compiler.compile(zip.toString(), classpath(), processorPath(), jar.toString());
      check(result.startsWith("{\"success\":true"), result);
      try (JarFile jf = new JarFile(jar.toFile())) {
        check(jf.getEntry("first/robot/Robot.class") != null, "Robot.class");
        check(jf.getEntry("first/Main.class") != null, "Main.class");
      }
      check(!Files.exists(Path.of(jar + ".proc")), "scratch folder removed");
    } finally {
      delete(dir);
    }
  }

  private static void opModeTemplate() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      List<String> entries = new ArrayList<>();
      String prefix = "templates/opmode/";
      try (ZipFile templates = new ZipFile(runtime().resolve("wpilib/templates.zip").toFile())) {
        for (Enumeration<? extends ZipEntry> e = templates.entries(); e.hasMoreElements();) {
          ZipEntry entry = e.nextElement();
          if (!entry.isDirectory() && entry.getName().startsWith(prefix) && entry.getName().endsWith(".java")) {
            entries.add("first/robot/" + entry.getName().substring(prefix.length()));
            entries.add(text(templates, entry).replace("org.wpilib.templates.opmode", "first.robot"));
          }
        }
      }
      Path jar = dir.resolve("out.jar");
      String result = Compiler.compile(zip(dir, entries.toArray(new String[0])).toString(), classpath(), "",
          jar.toString());
      check(result.startsWith("{\"success\":true"), result);
      HAL.initialize();
      DriverStationSim.resetData();
      try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
          CompilerTest.class.getClassLoader())) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
          loader.loadClass("first.robot.Robot").getConstructor().newInstance();
        } finally {
          thread.setContextClassLoader(previous);
        }
      }
      eq(2, DriverStationSim.getOpModeOptions().length, "registered op modes");
    } finally {
      delete(dir);
    }
  }

  private static void avajeAdapter() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      Path zip = zip(dir, "demo/Point.java", "package demo;\n"
          + "import io.avaje.jsonb.Json;\n"
          + "@Json\n"
          + "public record Point(double x, double y) {}\n");
      Path jar = dir.resolve("out.jar");
      String result = Compiler.compile(zip.toString(), classpath(), processorPath(), jar.toString());
      check(result.startsWith("{\"success\":true"), result);
      try (JarFile jf = new JarFile(jar.toFile())) {
        check(jf.getEntry("demo/PointJsonAdapter.class") != null, "adapter class");
      }
    } finally {
      delete(dir);
    }
  }

  private static Path runtime() {
    return Path.of(System.getProperty("frcdevwasm.runtime"));
  }

  private static String classpath() throws IOException {
    List<String> jars = new ArrayList<>();
    try (Stream<Path> lib = Files.list(runtime().resolve("lib"))) {
      lib.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(p -> jars.add(p.toString()));
    }
    jars.add(bundled("commandsv2-java-"));
    return String.join(":", jars);
  }

  /** The classpath plus the WPILib annotation processors (what wpilibAnnotations() adds). */
  private static String processorPath() throws IOException {
    return classpath() + ":" + bundled("epilogue-processor-java-") + ":" + bundled("javac-plugin-java-")
        + ":" + bundled("avaje-jsonb-generator-");
  }

  private static String bundled(String prefix) throws IOException {
    try (Stream<Path> files = Files.list(runtime().resolve("bundled"))) {
      return files.filter(p -> p.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow().toString();
    }
  }

  private static String text(ZipFile zip, ZipEntry entry) throws IOException {
    return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
  }

  /** Writes a zip from alternating entry names and contents. */
  private static Path zip(Path dir, String... namesAndContents) throws IOException {
    Path zip = dir.resolve("sources.zip");
    try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
      for (int i = 0; i < namesAndContents.length; i += 2) {
        z.putNextEntry(new ZipEntry(namesAndContents[i]));
        z.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
      }
    }
    return zip;
  }

  private static void delete(Path dir) throws IOException {
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }
}
