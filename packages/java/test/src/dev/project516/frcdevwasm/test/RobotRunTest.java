package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.tools.Compiler;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Compiles WPILib templates and examples with the compile driver and runs them on the desktop JDK
 * with the shim first on the classpath, driven by scripted driver station messages.
 */
public final class RobotRunTest {
  private static final String PROBE =
      """
      package frc.robot;

      public final class Probe {
        private static edu.wpi.first.wpilibj.motorcontrol.PWMSparkMax motor;
        private static int periodic;
        private static int teleop;

        private Probe() {}

        public static void periodic() {
          periodic++;
          if (periodic % 25 == 0) {
            System.out.println("PROBE periodic " + periodic);
          }
        }

        public static void teleop() {
          if (motor == null) {
            motor = new edu.wpi.first.wpilibj.motorcontrol.PWMSparkMax(0);
          }
          teleop++;
          motor.set(teleop % 2 == 0 ? 0.25 : -0.25);
        }
      }
      """;

  private RobotRunTest() {}

  private static String runtime() {
    return System.getProperty("frcdevwasm.runtime");
  }

  private static String wpilibClasspath() throws Exception {
    String manifest = Files.readString(Path.of(runtime(), "manifest.json"));
    List<String> jars = new ArrayList<>();
    int at = manifest.indexOf("\"classpath\"");
    int open = manifest.indexOf('[', at);
    int close = manifest.indexOf(']', open);
    for (String part : manifest.substring(open + 1, close).split(",")) {
      String p = part.trim().replace("\"", "");
      if (!p.isEmpty()) {
        jars.add(Path.of(runtime(), p).toString());
      }
    }
    return String.join(File.pathSeparator, jars);
  }

  /** Sources of one template or example, repackaged to frc.robot, plus the probe. */
  private static Path sourcesZip(String zipName, String kind, String name, boolean probe)
      throws Exception {
    Path out = Files.createTempFile("robot-" + name, ".zip");
    String base = kind + "/" + name + "/";
    String oldPackage = "edu.wpi.first.wpilibj." + kind + "." + name;
    try (ZipFile zf = new ZipFile(Path.of(runtime(), "wpilib", zipName).toFile());
        ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
      for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
        ZipEntry e = en.nextElement();
        if (e.isDirectory() || !e.getName().startsWith(base)) {
          continue;
        }
        String rel = e.getName().substring(base.length());
        byte[] bytes = zf.getInputStream(e).readAllBytes();
        if (rel.endsWith(".java")) {
          String src = new String(bytes, StandardCharsets.UTF_8).replace(oldPackage, "frc.robot");
          if (!ntImplemented() && rel.equals("Robot.java")) {
            src = src.replace("SmartDashboard.putData(\"Auto choices\", m_chooser);", "");
          }
          if (probe && rel.equals("Robot.java")) {
            src = src.replace("public void robotPeriodic() {", "public void robotPeriodic() {\n    frc.robot.Probe.periodic();");
            src = src.replace("public void teleopPeriodic() {", "public void teleopPeriodic() {\n    frc.robot.Probe.teleop();");
          }
          bytes = src.getBytes(StandardCharsets.UTF_8);
        }
        zos.putNextEntry(new ZipEntry("frc/robot/" + rel));
        zos.write(bytes);
        zos.closeEntry();
      }
      if (probe) {
        zos.putNextEntry(new ZipEntry("frc/robot/Probe.java"));
        zos.write(PROBE.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
    }
    return out;
  }

  /** Until the NetworkTables natives exist, SmartDashboard publishing cannot work. */
  private static boolean ntImplemented() {
    try {
      Class.forName("dev.project516.frcdevwasm.shim.networktables.NetworkTablesJNIImpl");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private static Path compile(Path zip, String name) throws Exception {
    Path jar = Files.createTempFile("robot-" + name, ".jar");
    String classpath = wpilibClasspath();
    String processors = "";
    try (ZipFile zf = new ZipFile(zip.toFile())) {
      for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
        ZipEntry e = en.nextElement();
        if (e.getName().endsWith(".java")
            && new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8).contains("edu.wpi.first.epilogue")) {
          processors =
              Path.of(runtime(), "bundled/epilogue-processor-java-2026.2.1.jar")
                  + File.pathSeparator + classpath;
          break;
        }
      }
    }
    String json = Compiler.compile(zip.toString(), classpath, processors, jar.toString());
    Tests.check(json.contains("\"success\":true"), "compile failed: " + json);
    return jar;
  }

  /** Runs the robot in a child JVM and returns its output lines and exit code. */
  private static Map<String, String> runRobot(Path robotJar, long teleopMs) throws Exception {
    String testClasses =
        Path.of(RobotRunTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    String shimJar = Path.of(runtime(), "frcdevwasm-shim.jar").toString();
    String java = Path.of(System.getProperty("frcdevwasm.jdk"), "bin", "java").toString();
    List<String> cmd = new ArrayList<>(List.of(
        java, "-Xmx256m", "-XX:+UseSerialGC", "-Djava.awt.headless=true",
        "-cp", String.join(File.pathSeparator, shimJar, wpilibClasspath(), robotJar.toString(), testClasses),
        RobotHarness.class.getName(), "frc.robot.Main", String.valueOf(teleopMs)));
    Path workDir = Files.createTempDirectory("robot-run");
    Process p = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true).start();
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    Thread pump = new Thread(() -> {
      try (InputStream in = p.getInputStream(); OutputStream out = captured) {
        in.transferTo(out);
      } catch (java.io.IOException e) {
        // process ended
      }
    });
    pump.start();
    if (!p.waitFor(90, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      pump.join(1000);
      throw new AssertionError("robot did not exit after stop:\n" + captured.toString(StandardCharsets.UTF_8));
    }
    pump.join(2000);
    String output = captured.toString(StandardCharsets.UTF_8);
    Map<String, String> result = new HashMap<>();
    result.put("output", output);
    result.put("code", String.valueOf(p.exitValue()));
    for (String line : output.split("\n")) {
      if (line.startsWith("RESULT ")) {
        String[] kv = line.substring(7).trim().split("=", 2);
        result.put(kv[0], kv[1]);
      }
    }
    return result;
  }

  private static void checkClean(Map<String, String> r) {
    String out = r.get("output");
    Tests.check(!out.contains("UnsatisfiedLinkError"), "native library use:\n" + out);
    Tests.check(!out.contains("Exception in thread"), "uncaught exception:\n" + out);
    Tests.check(!out.contains("Unhandled exception"), "robot code threw:\n" + out);
    Tests.check(!out.contains("quit unexpectedly"), "robot quit:\n" + out);
    Tests.eq("0", r.get("code"), "process exit code, output:\n" + out);
    Tests.eq("0", r.get("exit"), "exit message code, output:\n" + out);
  }

  private static void runTemplate(String name) throws Exception {
    Path jar = compile(sourcesZip("templates.zip", "templates", name, true), name);
    Map<String, String> r = runRobot(jar, 2000);
    checkClean(r);
    int pwm = Integer.parseInt(r.getOrDefault("pwm", "0"));
    Tests.check(pwm >= 40, "teleopPeriodic should drive the motor about 100 times, saw " + pwm + " PWM messages\n" + r.get("output"));
    int probe = Integer.parseInt(r.getOrDefault("probe", "0"));
    Tests.check(probe >= 2, "robotPeriodic should run for 2 s, saw " + probe + " probe lines\n" + r.get("output"));
    int announces = Integer.parseInt(r.getOrDefault("ntAnnounce", "0"));
    int values = Integer.parseInt(r.getOrDefault("ntValue", "0"));
    Tests.check(announces >= 1 && values >= 1,
        "NetworkTables topics should reach the page after hello, saw " + announces + " announces and " + values + " values\n" + r.get("output"));
  }

  private static void runExample(String name) throws Exception {
    Path jar = compile(sourcesZip("examples.zip", "examples", name, false), name);
    Map<String, String> r = runRobot(jar, 1000);
    checkClean(r);
  }

  /** A spread of examples; set FRCDEVWASM_ALL_EXAMPLES=1 to run every example that has no vision or vendor code. */
  private static List<String> examples() throws java.io.IOException {
    if (System.getenv("FRCDEVWASM_ALL_EXAMPLES") == null) {
      return List.of(
          "motorcontrol", "gettingstarted", "tankdrive", "mecanumdrive", "armsimulation", "eventloop",
          "mechanism2d", "encoder", "relay", "solenoid", "addressableled", "differentialdrivebot",
          "elevatorsimulation", "canpdp", "dutycycleencoder", "swervebot", "rapidreactcommandbot");
    }
    List<String> all = new ArrayList<>();
    try (ZipFile zf = new ZipFile(Path.of(runtime(), "wpilib", "examples.zip").toFile())) {
      for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
        String n = en.nextElement().getName();
        if (n.length() > 9 && n.startsWith("examples/") && n.indexOf('/', 9) == n.length() - 1) {
          String name = n.substring("examples/".length(), n.length() - 1);
          if (!List.of("httpcamera", "quickvision", "intermediatevision", "apriltagsvision", "romireference", "xrpreference", "unittest").contains(name)) {
            all.add(name);
          }
        }
      }
    }
    return all;
  }

  public static void run(Tests t) throws java.io.IOException {
    t.test("timed template runs in teleop and stops cleanly", () -> runTemplate("timed"));
    t.test("commandbased template runs in teleop and stops cleanly", () -> runTemplate("commandbased"));
    for (String example : examples()) {
      t.test("example " + example + " runs for a second", () -> runExample(example));
    }
  }
}
