package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import dev.project516.frcdevwasm.sim.DriverStationData;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.hal.SimBoolean;
import edu.wpi.first.hal.SimDevice;
import edu.wpi.first.hal.SimDouble;
import edu.wpi.first.hal.SimInt;
import edu.wpi.first.hal.AllianceStationID;
import edu.wpi.first.util.WPIUtilJNI;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Notifier;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import edu.wpi.first.wpilibj.simulation.SimDeviceSim;
import edu.wpi.first.wpilibj.simulation.SimHooks;
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

  public static void run(Tests t) {
    LocalTransport transport = BridgeLoop.local();
    HAL.initialize(500, 0);
    DriverStationSim.resetData();

    t.test("driver station state is visible after new data", () -> {
      DriverStationSim.setEnabled(true);
      DriverStationSim.setAutonomous(false);
      DriverStationSim.setDsAttached(true);
      DriverStationSim.setAllianceStationId(AllianceStationID.Blue2);
      DriverStationSim.setGameSpecificMessage("LRL");
      DriverStationSim.setMatchTime(42.5);
      DriverStationSim.notifyNewData();
      DriverStation.refreshData();
      Tests.check(DriverStation.isEnabled(), "enabled");
      Tests.check(DriverStationData.get().robotEnabled(), "robotEnabled follows enabled");
      Tests.check(DriverStation.isTeleopEnabled(), "teleop enabled");
      Tests.check(DriverStation.isDSAttached(), "ds attached");
      Tests.eq(DriverStation.Alliance.Blue, DriverStation.getAlliance().get(), "alliance");
      Tests.eq(2, DriverStation.getLocation().getAsInt(), "station");
      Tests.eq("LRL", DriverStation.getGameSpecificMessage(), "game data");
      Tests.near(42.5, DriverStation.getMatchTime(), 1e-9, "match time");
      DriverStationSim.setEnabled(false);
      Tests.check(DriverStation.isEnabled(), "unchanged before new data");
      Tests.check(DriverStationData.get().robotEnabled(), "robotEnabled unchanged before new data");
      DriverStationSim.notifyNewData();
      DriverStation.refreshData();
      Tests.check(DriverStation.isDisabled(), "disabled after new data");
      Tests.check(!DriverStationData.get().robotEnabled(), "robotEnabled follows disabled");
    });

    t.test("joystick data and callbacks", () -> {
      DriverStationSim.setJoystickAxisCount(1, 3);
      DriverStationSim.setJoystickPOVCount(1, 1);
      DriverStationSim.setJoystickButtonCount(1, 4);
      DriverStationSim.setJoystickAxis(1, 2, -0.5);
      DriverStationSim.setJoystickPOV(1, 0, 90);
      DriverStationSim.setJoystickButton(1, 2, true);
      DriverStationSim.setJoystickName(1, "Pad");
      DriverStationSim.setJoystickIsXbox(1, true);
      AtomicInteger enabledCalls = new AtomicInteger();
      var cb = DriverStationSim.registerEnabledCallback((n, v) -> enabledCalls.incrementAndGet(), false);
      DriverStationSim.setEnabled(true);
      DriverStationSim.setEnabled(true);
      cb.close();
      Tests.eq(1, enabledCalls.get(), "callback count");
      DriverStationSim.notifyNewData();
      DriverStation.refreshData();
      Tests.eq(3, DriverStation.getStickAxisCount(1), "axis count");
      Tests.near(-0.5, DriverStation.getStickAxis(1, 2), 1e-6, "axis");
      Tests.eq(90, DriverStation.getStickPOV(1, 0), "pov");
      Tests.check(DriverStation.getStickButton(1, 2), "button 2");
      Tests.check(!DriverStation.getStickButton(1, 1), "button 1");
      Tests.eq("Pad", DriverStation.getJoystickName(1), "name");
      Tests.check(DriverStation.getJoystickIsXbox(1), "xbox");
      Tests.check(DriverStation.isJoystickConnected(1), "connected");
    });

    t.test("sim time pause and step drive notifiers exactly", () -> {
      SimHooks.pauseTiming();
      try {
        AtomicInteger runs = new AtomicInteger();
        try (Notifier n = new Notifier(runs::incrementAndGet)) {
          n.startPeriodic(0.02);
          Thread.sleep(50);
          double t0 = Timer.getFPGATimestamp();
          SimHooks.stepTiming(0.1);
          Tests.near(0.1, Timer.getFPGATimestamp() - t0, 1e-6, "fpga time advanced");
          Tests.eq(5, runs.get(), "runs after 0.1 s step");
          SimHooks.stepTiming(0.05);
          Tests.eq(7, runs.get(), "runs after 0.05 s more");
          Thread.sleep(100);
          Tests.eq(7, runs.get(), "nothing runs while paused");
        }
      } finally {
        SimHooks.resumeTiming();
      }
      Tests.check(!SimHooks.isTimingPaused(), "resumed");
    });

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

    t.test("sim devices create, read, write and notify", () -> {
      try (SimDevice dev = SimDevice.create("Gyro:Test", 3)) {
        SimDouble angle = dev.createDouble("angle", SimDevice.Direction.kInput, 1.5);
        SimBoolean init = dev.createBoolean("init", SimDevice.Direction.kOutput, true);
        SimInt count = dev.createInt("count", SimDevice.Direction.kBidir, 7);
        SimDeviceSim sim = new SimDeviceSim("Gyro:Test", 3);
        Tests.eq("Gyro:Test[3]", sim.getName(), "name");
        Tests.near(1.5, sim.getDouble("angle").get(), 0, "initial");
        AtomicInteger changes = new AtomicInteger();
        var cb = sim.registerValueChangedCallback(sim.getDouble("angle"), (n, h, d, v) -> changes.incrementAndGet(), false);
        sim.getDouble("angle").set(2.5);
        Tests.near(2.5, angle.get(), 0, "written by sim");
        angle.set(3.5);
        Tests.eq(2, changes.get(), "changed callbacks");
        cb.close();
        Tests.check(init.get(), "boolean");
        count.set(9);
        Tests.eq(9, sim.getInt("count").get(), "int");
        sim.getInt("count").reset();
        Tests.eq(0, count.get(), "reset");
        Tests.eq(3, sim.enumerateValues().length, "values");
      }
      Tests.eq(0, SimDeviceSim.enumerateDevices("Gyro:Test").length, "freed");
    });

    t.test("wpiutil events and semaphores", () -> {
      int event = WPIUtilJNI.createEvent(false, false);
      Tests.check(WPIUtilJNI.waitForObjectTimeout(event, 0.05), "times out when not set");
      new Thread(() -> {
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          return;
        }
        WPIUtilJNI.setEvent(event);
      }).start();
      WPIUtilJNI.waitForObject(event);
      Tests.check(WPIUtilJNI.waitForObjectTimeout(event, 0.02), "auto reset event is cleared");
      WPIUtilJNI.destroyEvent(event);
      int sem = WPIUtilJNI.createSemaphore(0, 2);
      Tests.check(WPIUtilJNI.releaseSemaphore(sem, 2), "release");
      Tests.check(!WPIUtilJNI.releaseSemaphore(sem, 1), "over maximum");
      int[] signaled = WPIUtilJNI.waitForObjects(new int[] {sem});
      Tests.eq(1, signaled.length, "signaled");
      WPIUtilJNI.destroySemaphore(sem);
      Tests.check(WPIUtilJNI.now() > 0, "now");
    });

    t.test("bridge applies page input and reports robot output", () -> {
      transport.clearSent();
      transport.inject(
          "DriverStation", "",
          map(">enabled", true, ">autonomous", false, ">ds", true, ">station", "red3", ">new_data", true));
      transport.inject(
          "Joystick", "0", map(">axes", List.of(0.25, -1.0), ">buttons", List.of(true, false), ">povs", List.of(180)));
      transport.inject("DriverStation", "", map(">new_data", true));
      Tests.check(waitFor(() -> DriverStationSim.getEnabled() && DriverStationSim.getDsAttached(), 2000), "enabled from page");
      Tests.check(waitFor(() -> {
        DriverStation.refreshData();
        return DriverStation.getStickAxisCount(0) == 2;
      }, 2000), "joystick axes from page");
      Tests.near(0.25, DriverStation.getStickAxis(0, 0), 1e-6, "axis 0");
      Tests.check(DriverStation.getStickButton(0, 1), "button 1");
      Tests.eq(180, DriverStation.getStickPOV(0, 0), "pov");
      Tests.eq(DriverStation.Alliance.Red, DriverStation.getAlliance().get(), "station red3");
      Tests.eq(3, DriverStation.getLocation().getAsInt(), "station number");

      System.out.println("console line from test");
      try (var pwm = new edu.wpi.first.wpilibj.motorcontrol.PWMSparkMax(9)) {
        pwm.set(0.5);
        Tests.check(waitFor(() -> transport.sent().stream().anyMatch(m ->
            "PWM".equals(m.get("type")) && "9".equals(m.get("device"))
                && ((Map<?, ?>) m.get("data")).containsKey("<speed")), 2000), "pwm speed message");
      }
      Tests.check(waitFor(() -> transport.sent().stream().anyMatch(m ->
          "PWM".equals(m.get("type")) && Boolean.FALSE.equals(((Map<?, ?>) m.get("data")).get("<init"))), 2000), "pwm init false on free");
      Tests.check(transport.sent().stream().anyMatch(m ->
          "console".equals(m.get("type")) && String.valueOf(((Map<?, ?>) m.get("data")).get("text")).contains("console line from test")), "console message");
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

    t.test("hello resends the state of initialized devices", () -> {
      try (var pwm = new edu.wpi.first.wpilibj.motorcontrol.PWMSparkMax(8)) {
        pwm.set(-0.5);
        BridgeLoop.flush();
        transport.clearSent();
        transport.inject("{\"type\":\"hello\",\"device\":\"\",\"data\":{}}");
        Tests.check(waitFor(() -> transport.sent().stream().anyMatch(m ->
            "PWM".equals(m.get("type")) && "8".equals(m.get("device"))
                && Boolean.TRUE.equals(((Map<?, ?>) m.get("data")).get("<init"))
                && ((Map<?, ?>) m.get("data")).containsKey("<speed")), 2000), "pwm state after hello");
        Tests.check(transport.sent().stream().anyMatch(m -> "DriverStation".equals(m.get("type"))), "driver station state");
      }
    });

    t.test("sim device values reach the page and page values reach the robot", () -> {
      transport.clearSent();
      try (SimDevice dev = SimDevice.create("Accel:Test", 1)) {
        SimDouble x = dev.createDouble("x", SimDevice.Direction.kInput, 0.5);
        Tests.check(waitFor(() -> transport.sent().stream().anyMatch(m ->
            "Accel".equals(m.get("type")) && "Test[1]".equals(m.get("device"))
                && ((Map<?, ?>) m.get("data")).containsKey(">x")), 2000), "value announced");
        transport.inject("Accel", "Test[1]", map(">x", 2.25));
        Tests.check(waitFor(() -> x.get() == 2.25, 2000), "input applied");
      }
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
        java.io.File deploy = edu.wpi.first.wpilibj.Filesystem.getDeployDirectory();
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
  }
}
