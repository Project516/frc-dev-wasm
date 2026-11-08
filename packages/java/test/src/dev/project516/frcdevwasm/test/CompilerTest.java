package dev.project516.frcdevwasm.test;

import static dev.project516.frcdevwasm.test.Tests.check;
import static dev.project516.frcdevwasm.test.Tests.eq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Tests for {@link Compiler}, run on the desktop JDK. */
public final class CompilerTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private CompilerTest() {}

  public static void run(Tests t) {
    t.test("compiler: valid project becomes a jar with classes and resources", CompilerTest::validProject);
    t.test("compiler: syntax error is reported with position", CompilerTest::syntaxError);
    t.test("compiler: missing symbol is reported with position", CompilerTest::missingSymbol);
    t.test("compiler: warnings and notes do not fail the build", CompilerTest::warnings);
    t.test("compiler: compiles against WPILib jars", CompilerTest::againstWpilib);
    t.test("compiler: JSON escapes quotes, newlines and unicode", CompilerTest::jsonEscaping);
    t.test("compiler: Lombok generates getters", CompilerTest::lombok);
    t.test("compiler: Epilogue generates logger classes", CompilerTest::epilogue);
    t.test("compiler: processors can resolve Filer resource URIs to paths", CompilerTest::processorResolvesPaths);
    t.test("compiler: bad zip path is an error diagnostic", CompilerTest::badZip);
  }

  private static void validProject() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      Path zip = zip(dir, "frc/robot/Robot.java", "package frc.robot;\n"
          + "public class Robot {\n"
          + "  static class Inner {}\n"
          + "  public static int answer() { return 42; }\n"
          + "}\n", "frc/robot/deploy/auto.json", "{\"a\":1}");
      Path jar = dir.resolve("out.jar");
      JsonNode result = compile(zip, "", "", jar);
      eq(true, result.get("success").asBoolean(), result.toString());
      eq(0, result.get("diagnostics").size(), "diagnostics");
      try (JarFile jf = new JarFile(jar.toFile())) {
        check(jf.getManifest() != null, "manifest");
        check(jf.getEntry("frc/robot/Robot.class") != null, "Robot.class");
        check(jf.getEntry("frc/robot/Robot$Inner.class") != null, "Robot$Inner.class");
        String resource = new String(jf.getInputStream(jf.getEntry("frc/robot/deploy/auto.json")).readAllBytes(),
            StandardCharsets.UTF_8);
        eq("{\"a\":1}", resource, "resource content");
        check(jf.getEntry("frc/robot/Robot.java") == null, "sources are not copied");
        check(jf.getEntry("frc/") != null && jf.getEntry("frc/").isDirectory(), "directory entry frc/");
        check(jf.getEntry("frc/robot/deploy/") != null, "directory entry frc/robot/deploy/");
        List<String> names = jf.stream().map(e -> e.getName()).toList();
        check(names.indexOf("frc/robot/") < names.indexOf("frc/robot/Robot.class"), "directory before its files");
        eq(1L, names.stream().filter("frc/"::equals).count(), "each directory once");
      }
    } finally {
      delete(dir);
    }
  }

  private static void syntaxError() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      Path zip = zip(dir, "frc/robot/Bad.java", "package frc.robot;\n"
          + "public class Bad {\n"
          + "  int x = 1\n"
          + "}\n");
      Path jar = dir.resolve("out.jar");
      JsonNode result = compile(zip, "", "", jar);
      eq(false, result.get("success").asBoolean(), "success");
      JsonNode d = result.get("diagnostics").get(0);
      eq("ERROR", d.get("kind").asText(), "kind");
      eq("frc/robot/Bad.java", d.get("file").asText(), "file");
      eq(3, d.get("line").asInt(), "line");
      eq(12, d.get("column").asInt(), "column");
      check(d.get("message").asText().contains("';' expected"), d.get("message").asText());
      check(!Files.exists(jar), "no jar on failure");
    } finally {
      delete(dir);
    }
  }

  private static void missingSymbol() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      Path zip = zip(dir, "frc/robot/Missing.java", "package frc.robot;\n"
          + "public class Missing {\n"
          + "  void f() {\n"
          + "    int y = undefined + 1;\n"
          + "  }\n"
          + "}\n");
      JsonNode result = compile(zip, "", "", dir.resolve("out.jar"));
      eq(false, result.get("success").asBoolean(), "success");
      eq(1, result.get("diagnostics").size(), "diagnostic count");
      JsonNode d = result.get("diagnostics").get(0);
      eq("ERROR", d.get("kind").asText(), "kind");
      eq("frc/robot/Missing.java", d.get("file").asText(), "file");
      eq(4, d.get("line").asInt(), "line");
      eq(13, d.get("column").asInt(), "column");
      check(d.get("message").asText().contains("cannot find symbol"), d.get("message").asText());
    } finally {
      delete(dir);
    }
  }

  private static void warnings() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      Path zip = zip(dir, "Warn.java", "import java.util.ArrayList;\n"
          + "import java.util.List;\n"
          + "public class Warn {\n"
          + "  @SuppressWarnings(\"rawtypes\")\n"
          + "  static Integer f() {\n"
          + "    List raw = new ArrayList();\n"
          + "    raw.add(\"x\");\n"
          + "    return new Integer(5);\n"
          + "  }\n"
          + "}\n");
      Path jar = dir.resolve("out.jar");
      JsonNode result = compile(zip, "", "", jar);
      eq(true, result.get("success").asBoolean(), result.toString());
      check(Files.exists(jar), "jar written");
      boolean warning = false;
      boolean note = false;
      for (JsonNode d : result.get("diagnostics")) {
        if (d.get("kind").asText().equals("WARNING")) {
          warning = true;
          eq("Warn.java", d.get("file").asText(), "warning file");
          eq(8, d.get("line").asInt(), "warning line");
        } else if (d.get("kind").asText().equals("NOTE")) {
          note = true;
        }
      }
      check(warning, "removal warning: " + result);
      check(note, "unchecked note: " + result);
    } finally {
      delete(dir);
    }
  }

  private static void againstWpilib() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      Path zip = zip(dir, "frc/robot/Robot.java", "package frc.robot;\n"
          + "import edu.wpi.first.math.geometry.Pose2d;\n"
          + "import edu.wpi.first.math.geometry.Rotation2d;\n"
          + "import edu.wpi.first.wpilibj.TimedRobot;\n"
          + "public class Robot extends TimedRobot {\n"
          + "  Pose2d pose = new Pose2d(1, 2, Rotation2d.fromDegrees(90));\n"
          + "}\n");
      Path jar = dir.resolve("out.jar");
      JsonNode result = compile(zip, wpilibClasspath(), "", jar);
      eq(true, result.get("success").asBoolean(), result.toString());
      try (JarFile jf = new JarFile(jar.toFile())) {
        check(jf.getEntry("frc/robot/Robot.class") != null, "Robot.class");
      }
    } finally {
      delete(dir);
    }
  }

  private static void jsonEscaping() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      String name = "frc/róbot/Q\"x\\y.java";
      Path zip = zip(dir, name, "package frc.róbot;\n"
          + "class Q {\n"
          + "  int f() { return héllo; }\n"
          + "}\n");
      String raw = Compiler.compile(zip.toString(), "", "", dir.resolve("out.jar").toString());
      for (int i = 0; i < raw.length(); i++) {
        check(raw.charAt(i) >= 0x20 && raw.charAt(i) <= 0x7e, "raw JSON is plain ASCII");
      }
      JsonNode d = JSON.readTree(raw).get("diagnostics").get(0);
      eq(name, d.get("file").asText(), "file");
      String message = d.get("message").asText();
      check(message.contains("héllo"), message);
      check(message.contains("\n"), "multi-line message");
    } finally {
      delete(dir);
    }
  }

  private static void lombok() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      Path zip = zip(dir, "demo/Person.java", "package demo;\n"
          + "@lombok.Getter\n"
          + "public class Person {\n"
          + "  private int age;\n"
          + "}\n");
      Path jar = dir.resolve("out.jar");
      JsonNode result = compile(zip, processorClasspath(), processorClasspath(), jar);
      eq(true, result.get("success").asBoolean(), result.toString());
      try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
        loader.loadClass("demo.Person").getMethod("getAge");
      }
    } finally {
      delete(dir);
    }
  }

  private static void epilogue() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      Path zip = zip(dir, "demo/Arm.java", "package demo;\n"
          + "import edu.wpi.first.epilogue.Logged;\n"
          + "@Logged\n"
          + "public class Arm {\n"
          + "  double angle;\n"
          + "}\n");
      Path jar = dir.resolve("out.jar");
      JsonNode result = compile(zip, processorClasspath(), processorClasspath(), jar);
      eq(true, result.get("success").asBoolean(), result.toString());
      try (JarFile jf = new JarFile(jar.toFile())) {
        check(jf.getEntry("demo/ArmLogger.java") != null, "generated source");
        check(jf.getEntry("demo/ArmLogger.class") != null, "generated class");
        check(jf.getEntry("demo/Arm.class") != null, "Arm.class");
      }
    } finally {
      delete(dir);
    }
  }

  // Like avaje-jsonb 3.x, which turns a Filer resource URI into a Path and writes beside the class output.
  private static final String PATH_PROCESSOR = "package probe;\n"
      + "import java.io.IOException;\n"
      + "import java.io.Writer;\n"
      + "import java.nio.file.Files;\n"
      + "import java.nio.file.Path;\n"
      + "import java.util.Set;\n"
      + "import javax.annotation.processing.AbstractProcessor;\n"
      + "import javax.annotation.processing.RoundEnvironment;\n"
      + "import javax.annotation.processing.SupportedAnnotationTypes;\n"
      + "import javax.lang.model.SourceVersion;\n"
      + "import javax.lang.model.element.TypeElement;\n"
      + "import javax.tools.FileObject;\n"
      + "import javax.tools.StandardLocation;\n"
      + "@SupportedAnnotationTypes(\"*\")\n"
      + "public class Probe extends AbstractProcessor {\n"
      + "  private boolean done;\n"
      + "  @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }\n"
      + "  @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {\n"
      + "    if (done) return false;\n"
      + "    done = true;\n"
      + "    try {\n"
      + "      FileObject out = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, \"\", \"probe.txt\");\n"
      + "      Path path = Path.of(out.toUri());\n"
      + "      Files.writeString(path.getParent().getParent().resolve(\"state.txt\"), \"x\");\n"
      + "      Path gen = Path.of(processingEnv.getFiler().createResource(StandardLocation.SOURCE_OUTPUT, \"\", \"gen.txt\").toUri());\n"
      + "      try (Writer w = out.openWriter()) { w.write(path.getFileName() + \" \" + gen.getFileName()); }\n"
      + "    } catch (IOException e) {\n"
      + "      throw new RuntimeException(e);\n"
      + "    }\n"
      + "    return false;\n"
      + "  }\n"
      + "}\n";

  private static void processorResolvesPaths() throws Exception {
    Path dir = Files.createTempDirectory("compilertest");
    try {
      Path procDir = Files.createDirectory(dir.resolve("proc"));
      Path procZip = zip(procDir, "probe/Probe.java", PATH_PROCESSOR,
          "META-INF/services/javax.annotation.processing.Processor", "probe.Probe\n");
      Path procJar = procDir.resolve("probe.jar");
      JsonNode built = compile(procZip, "", "", procJar);
      eq(true, built.get("success").asBoolean(), built.toString());

      Path zip = zip(dir, "demo/A.java", "package demo;\npublic class A {}\n");
      Path jar = dir.resolve("out.jar");
      JsonNode result = compile(zip, "", procJar.toString(), jar);
      eq(true, result.get("success").asBoolean(), result.toString());
      try (JarFile jf = new JarFile(jar.toFile())) {
        eq("probe.txt gen.txt", new String(jf.getInputStream(jf.getEntry("probe.txt")).readAllBytes(),
            StandardCharsets.UTF_8), "resource written through the Filer");
        check(jf.getEntry("state.txt") == null, "files a processor writes itself are not packed");
        check(jf.getEntry("demo/A.class") != null, "A.class");
      }
      check(!Files.exists(Path.of(jar + ".proc")), "scratch folder removed");
    } finally {
      delete(dir);
    }
  }

  private static void badZip() throws Exception {
    JsonNode result = JSON.readTree(Compiler.compile("/nonexistent/sources.zip", "", "", "/nonexistent/out.jar"));
    eq(false, result.get("success").asBoolean(), "success");
    JsonNode d = result.get("diagnostics").get(0);
    eq("ERROR", d.get("kind").asText(), "kind");
    eq("", d.get("file").asText(), "file");
    eq(0, d.get("line").asInt(), "line");
  }

  private static JsonNode compile(Path zip, String classpath, String processorPath, Path jar)
      throws IOException {
    return JSON.readTree(Compiler.compile(zip.toString(), classpath, processorPath, jar.toString()));
  }

  private static String wpilibClasspath() throws IOException {
    Path runtime = Path.of(System.getProperty("frcdevwasm.runtime"));
    JsonNode manifest = JSON.readTree(runtime.resolve("manifest.json").toFile());
    List<String> entries = new ArrayList<>();
    for (JsonNode e : manifest.get("classpath")) {
      entries.add(runtime.resolve(e.asText()).toString());
    }
    return String.join(":", entries);
  }

  /** WPILib jars plus the Lombok and Epilogue processor jars. */
  private static String processorClasspath() throws IOException {
    Path bundled = Path.of(System.getProperty("frcdevwasm.runtime"), "bundled");
    return wpilibClasspath()
        + ":" + bundled.resolve("lombok-1.18.48.jar")
        + ":" + bundled.resolve("epilogue-processor-java-2026.2.1.jar");
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
