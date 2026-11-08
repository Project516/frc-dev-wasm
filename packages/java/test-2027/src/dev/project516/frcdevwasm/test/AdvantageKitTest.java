package dev.project516.frcdevwasm.test;

/** AdvantageKit 27 on the shim: conduit reads the HAL, and a LoggedRobot runs with its receivers. */
public final class AdvantageKitTest {
  private AdvantageKitTest() {}

  private static final String CONDUIT =
      """
      package probe;

      import org.littletonrobotics.conduit.ConduitApi;
      import org.littletonrobotics.junction.LoggedPowerDistribution;
      import org.wpilib.hardware.hal.AllianceStationID;
      import org.wpilib.hardware.hal.ControlWord;
      import org.wpilib.hardware.power.PowerDistribution;
      import org.wpilib.simulation.DriverStationSim;
      import org.wpilib.simulation.RoboRioSim;

      public final class Probe {
        public static String run() {
          DriverStationSim.setAllianceStationId(AllianceStationID.BLUE_2);
          DriverStationSim.setEnabled(true);
          DriverStationSim.setDsAttached(true);
          DriverStationSim.setEventName("CMPTX");
          DriverStationSim.setGameData("RBG");
          DriverStationSim.setMatchNumber(12);
          DriverStationSim.setJoystickName(0, "Pad");
          DriverStationSim.setJoystickAxesMaximumIndex(0, 2);
          DriverStationSim.setJoystickAxis(0, 1, 0.5);
          DriverStationSim.setJoystickButtonsMaximumIndex(0, 10);
          DriverStationSim.setJoystickButton(0, 1, true);
          DriverStationSim.setJoystickButton(0, 3, true);
          DriverStationSim.notifyNewData();
          RoboRioSim.setVInVoltage(11.5);
          LoggedPowerDistribution.getInstance(0, 1, PowerDistribution.ModuleType.CTRE);

          ConduitApi conduit = ConduitApi.getInstance();
          conduit.captureData();

          ControlWord word = new ControlWord();
          word.update(conduit.getControlWord());
          StringBuilder out = new StringBuilder();
          out.append("station=").append(conduit.getAllianceStation()).append('\\n');
          out.append("event=").append(conduit.getEventName()).append('\\n');
          out.append("gameData=").append(conduit.getGameData()).append('\\n');
          out.append("match=").append(conduit.getMatchNumber()).append('\\n');
          out.append("enabled=").append(word.isEnabled()).append('\\n');
          out.append("name=").append(conduit.getJoystickName(0)).append('\\n');
          out.append("axis1=").append(conduit.getAxisValues(0)[1]).append('\\n');
          out.append("buttons=").append(conduit.getButtonValues(0)).append('\\n');
          out.append("battery=").append(conduit.getBatteryVoltage()).append('\\n');
          out.append("unplugged=").append(conduit.getJoystickName(1).isEmpty()).append('\\n');
          out.append("pdp=").append(conduit.getPDPModuleId()).append('/').append(conduit.getPDPChannelCount()).append('\\n');
          return out.toString();
        }
      }
      """;

  private static final String LOGGED_ROBOT =
      """
      package probe;

      import java.nio.file.Files;
      import java.nio.file.Path;
      import java.util.stream.Stream;
      import org.littletonrobotics.junction.LoggedRobot;
      import org.littletonrobotics.junction.Logger;
      import org.littletonrobotics.junction.networktables.NT4Publisher;
      import org.littletonrobotics.junction.wpilog.WPILOGWriter;
      import org.wpilib.simulation.DriverStationSim;

      public final class Probe {
        static final class Robot extends LoggedRobot {
          volatile int periodics;

          Robot(Path dir) {
            Logger.addDataReceiver(new WPILOGWriter(dir.toString()));
            Logger.addDataReceiver(new NT4Publisher());
            Logger.start();
          }

          @Override
          public void robotPeriodic() {
            Logger.recordOutput("Probe/Counter", ++periodics);
          }
        }

        public static String run() throws Exception {
          Path dir = Files.createTempDirectory("akit-probe");
          DriverStationSim.setEnabled(true);
          DriverStationSim.notifyNewData();
          Robot robot = new Robot(dir);
          Thread loop = new Thread(robot::startCompetition);
          loop.start();
          long deadline = System.nanoTime() + 20_000_000_000L;
          while (robot.periodics < 10 && System.nanoTime() < deadline) {
            Thread.sleep(20);
          }
          robot.endCompetition();
          loop.join(5000);
          Logger.end();

          StringBuilder out = new StringBuilder();
          out.append("periodics=").append(robot.periodics >= 10).append('\\n');
          try (Stream<Path> files = Files.list(dir)) {
            Path log = files.filter(p -> p.toString().endsWith(".wpilog")).findFirst().orElseThrow();
            String text = new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.ISO_8859_1);
            out.append("log=").append(Files.size(log) > 1000).append('\\n');
            out.append("output=").append(text.contains("/RealOutputs/Probe/Counter")).append('\\n');
            out.append("driverStation=").append(text.contains("/DriverStation/")).append('\\n');
            out.append("systemStats=").append(text.contains("/SystemStats/")).append('\\n');
          }
          try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
              Files.delete(file);
            }
          }
          Files.delete(dir);
          return out.toString();
        }
      }
      """;

  public static void run(Tests t) {
    t.test("AdvantageKit 27 reads the HAL through conduit", () -> {
      String result = VendorProbe.run("org/littletonrobotics/akit/akit-java", "", CONDUIT);
      if (result == null) {
        System.out.println("skipped: AdvantageKit 27 not downloaded");
        return;
      }
      VendorProbe.expectLines(
          result, "station=5", "event=CMPTX", "gameData=RBG", "match=12", "enabled=true", "name=Pad", "axis1=0.5",
          "buttons=10", "battery=11.5", "unplugged=true", "pdp=1/24");
    });

    t.test("AdvantageKit 27 LoggedRobot runs and logs with the WPILOG and NT4 receivers", () -> {
      String result = VendorProbe.run("org/littletonrobotics/akit/akit-java", "", LOGGED_ROBOT);
      if (result == null) {
        System.out.println("skipped: AdvantageKit 27 not downloaded");
        return;
      }
      VendorProbe.expectLines(result, "periodics=true", "log=true", "output=true", "driverStation=true", "systemStats=true");
    });
  }
}
