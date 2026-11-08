package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import dev.project516.frcdevwasm.sim.DriverStationData;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.SimBoolean;
import org.wpilib.hardware.hal.SimDevice;
import org.wpilib.hardware.hal.SimDouble;
import org.wpilib.hardware.hal.SimInt;
import org.wpilib.hardware.hal.AllianceStationID;
import org.wpilib.hardware.hal.DriverStationJNI;
import org.wpilib.hardware.hal.OpModeOption;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.util.WPIUtilJNI;
import org.wpilib.driverstation.DriverStation;
import org.wpilib.system.Notifier;
import org.wpilib.system.Timer;
import org.wpilib.simulation.DriverStationSim;
import org.wpilib.simulation.SimDeviceSim;
import org.wpilib.simulation.SimHooks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Driver station, notifier timing, SimDevice, wpiutil sync and the bridge, via real WPILib classes. */
public final class CoreSimTest {
  private CoreSimTest() {}

  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static boolean waitFor(java.util.function.BooleanSupplier c, long ms) throws Exception {
    long end = System.currentTimeMillis() + ms;
    while (System.currentTimeMillis() < end) {
      if (c.getAsBoolean()) {
        return true;
      }
      Thread.sleep(5);
    }
    return c.getAsBoolean();
  }

  private static List<Object> programStates(LocalTransport transport) {
    List<Object> states = new java.util.ArrayList<>();
    for (var m : transport.sent()) {
      if (m.get("type").equals("DriverStation") && ((Map<?, ?>) m.get("data")).containsKey("<program_state")) {
        states.add(((Map<?, ?>) m.get("data")).get("<program_state"));
      }
    }
    return states;
  }

  public static void run(Tests t) {
    LocalTransport transport = BridgeLoop.local();
    HAL.initialize();
    DriverStationSim.resetData();

    t.test("notifier runs in real time when not paused", () -> {
      AtomicInteger runs = new AtomicInteger();
      try (Notifier n = new Notifier(runs::incrementAndGet)) {
        n.startPeriodic(0.02);
        Thread.sleep(1000);
      }
      Tests.check(runs.get() >= 40 && runs.get() <= 55, "about 50 runs, was " + runs.get());
    });

    t.test("single shot notifier fires once", () -> {
      AtomicInteger runs = new AtomicInteger();
      try (Notifier n = new Notifier(runs::incrementAndGet)) {
        n.startSingle(0.05);
        Thread.sleep(300);
      }
      Tests.eq(1, runs.get(), "single run");
    });

    t.test("deploy zip is extracted where Filesystem.getDeployDirectory looks", () -> {
      java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("deploy-test");
      java.nio.file.Path stale = tmp.resolve("src/main/deploy/stale.txt");
      java.nio.file.Files.createDirectories(stale.getParent());
      java.nio.file.Files.writeString(stale, "old");
      java.nio.file.Path zip = tmp.resolve("deploy.zip");
      try (var zos = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
        zos.putNextEntry(new java.util.zip.ZipEntry("pathplanner/paths/Foo.path"));
        zos.write("{\"waypoints\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.putNextEntry(new java.util.zip.ZipEntry("a.txt"));
        zos.write("a".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.putNextEntry(new java.util.zip.ZipEntry("../evil.txt"));
        zos.write("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
      }
      String oldDir = System.getProperty("user.dir");
      try {
        System.setProperty("user.dir", tmp.toString());
        System.setProperty("frcdevwasm.deploy", zip.toString());
        dev.project516.frcdevwasm.sim.Deploy.extractIfRequested();
        java.io.File deploy = org.wpilib.system.Filesystem.getDeployDirectory();
        Tests.eq(tmp.resolve("src/main/deploy").toFile().getCanonicalPath(), deploy.getCanonicalPath(), "deploy dir");
        java.io.File foo = new java.io.File(deploy, "pathplanner/paths/Foo.path");
        Tests.check(foo.isFile(), "File sees the file");
        Tests.eq("{\"waypoints\":[]}", java.nio.file.Files.readString(foo.toPath()), "nio reads the file");
        try (var in = new java.io.FileInputStream(new java.io.File(deploy, "a.txt"))) {
          Tests.eq("a", new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), "io reads the file");
        }
        Tests.check(!new java.io.File(deploy, "stale.txt").exists(), "old contents removed");
        Tests.check(!tmp.resolve("src/main/evil.txt").toFile().exists(), "no path traversal");
      } finally {
        System.setProperty("user.dir", oldDir);
        System.clearProperty("frcdevwasm.deploy");
      }
    });

    t.test("sim timing messages pause and resume", () -> {
      transport.inject("sim.timing", "", map("op", "pause"));
      Tests.check(waitFor(SimHooks::isTimingPaused, 2000), "paused");
      Tests.check(waitFor(() -> transport.sent().stream().anyMatch(m ->
          "sim.timing".equals(m.get("type")) && Boolean.TRUE.equals(((Map<?, ?>) m.get("data")).get("paused"))), 2000), "paused reported");
      transport.inject("sim.timing", "", map("op", "resume"));
      Tests.check(waitFor(() -> !SimHooks.isTimingPaused(), 2000), "resumed");
    });

    t.test("an op mode id above 2^53 round trips through the bridge as a string", () -> {
      var ds = DriverStationData.get();
      ds.reset();
      long id = OpModeOption.makeId(RobotMode.TELEOPERATED, 0x00FEDCBA98765432L);
      transport.clearSent();
      transport.inject("DriverStation", "", map(">ds", true, ">robotMode", 2, ">opmode", Long.toString(id)));
      // The next message applies only after the first one, and anything it echoed is queued by then
      transport.inject("DriverStation", "", map(">match_time", 3.0));
      Tests.check(waitFor(() -> ds.matchTime.get() == 3.0, 2000), "messages applied");
      Tests.eq(id, ds.opMode.get(), "op mode set");
      long word = ds.uncachedControlWord();
      Tests.eq(0x00FEDCBA98765432L, word & 0x00FFFFFFFFFFFFFFL, "hash bits of the control word");
      Tests.eq(2L, (word >> 56) & 0x3, "robot mode bits");
      BridgeLoop.flush();
      Tests.check(transport.sent().stream().noneMatch(m -> m.get("type").equals("DriverStation")
          && ((Map<?, ?>) m.get("data")).containsKey(">opmode")), "a value from the page is not echoed");
      ds.opMode.set(0);
      Tests.check(waitFor(() -> transport.sent().stream().anyMatch(m -> m.get("type").equals("DriverStation")
          && "0".equals(((Map<?, ?>) m.get("data")).get(">opmode"))), 2000), "a change from the robot side is sent");
      transport.inject("DriverStation", "", map(">opmode", "junk"));
      transport.inject("DriverStation", "", map(">match_time", 7.0));
      Tests.check(waitFor(() -> ds.matchTime.get() == 7.0, 2000), "later messages still apply");
      Tests.eq(0L, ds.opMode.get(), "an unparsable id is ignored");
    });

    t.test("op mode options are sent with string ids and in the full state", () -> {
      var ds = DriverStationData.get();
      long id = OpModeOption.makeId(RobotMode.AUTONOMOUS, 0x00FEDCBA98765432L);
      transport.clearSent();
      ds.setOpModeOptions(new OpModeOption[] {new OpModeOption(id, "Auto One", "Group", "About", 0xFF0000, -1)});
      Tests.check(waitFor(() -> transport.sent().stream().anyMatch(m -> m.get("type").equals("DriverStation")
          && ((Map<?, ?>) m.get("data")).containsKey("<opmode_options")), 2000), "options sent");
      Map<?, ?> option = null;
      for (var m : transport.sent()) {
        if (m.get("type").equals("DriverStation") && ((Map<?, ?>) m.get("data")).get("<opmode_options") instanceof List<?> l) {
          option = (Map<?, ?>) l.get(0);
        }
      }
      Tests.eq(Long.toString(id), option.get("id"), "id is a decimal string");
      Tests.eq("Auto One", option.get("name"), "name");
      Tests.eq("Group", option.get("group"), "group");
      Tests.eq("About", option.get("description"), "description");
      Tests.eq(0xFF0000L, option.get("textColor"), "text color");
      Tests.eq(-1L, option.get("backgroundColor"), "default color");
      transport.clearSent();
      transport.inject("{\"type\":\"hello\"}");
      Tests.check(waitFor(() -> transport.sent().stream().anyMatch(m -> m.get("type").equals("DriverStation")
          && ((Map<?, ?>) m.get("data")).get("<opmode_options") instanceof List<?> l && l.size() == 1), 2000),
          "hello resends the options");
      ds.setOpModeOptions(new OpModeOption[0]);
    });

    t.test("the observed program state is sent only when it changes", () -> {
      long word = OpModeOption.makeId(RobotMode.TELEOPERATED, 0x00FEDCBA98765432L) | 0x0400000000000000L;
      DriverStationJNI.observeUserProgram(0);
      Thread.sleep(50);
      transport.clearSent();
      // Flushes between the calls, so the bridge cannot merge a repeated send away
      DriverStationJNI.observeUserProgram(word);
      BridgeLoop.flush();
      DriverStationJNI.observeUserProgram(word);
      BridgeLoop.flush();
      Tests.eq(List.of(Long.toString(word)), programStates(transport), "one message, the word as a decimal string");
      transport.clearSent();
      DriverStationJNI.observeUserProgram(word);
      BridgeLoop.flush();
      DriverStationJNI.observeUserProgram(0);
      BridgeLoop.flush();
      Tests.eq(List.of("0"), programStates(transport), "only the change");
    });

    t.test("a new data announcement that says ds false does not connect the driver station", () -> {
      var ds = dev.project516.frcdevwasm.sim.DriverStationData.get();
      ds.reset();
      transport.inject("DriverStation", "", map(">ds", false, ">station", "unknown", ">new_data", true));
      transport.inject("DriverStation", "", map(">match_time", 42.0));
      Tests.check(waitFor(() -> ds.matchTime.get() == 42.0, 2000), "the next message is applied");
      Tests.check(!ds.dsAttached.get(), "still disconnected");
      transport.inject("DriverStation", "", map(">ds", true, ">station", "red1", ">new_data", true));
      Tests.check(waitFor(() -> {
        ds.refresh();
        return ds.dsAttached.get() && ds.currentAllianceStation() != 0;
      }, 2000), "connected with an alliance");
    });
  }
}
