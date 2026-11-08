package dev.project516.frcdevwasm.test;

/** Common vendor libraries construct their devices on the shim, and REVLib's sim works. */
public final class VendorLibrariesTest {
  private VendorLibrariesTest() {}

  private static final String REV =
      """
      package probe;

      import com.revrobotics.spark.SparkBase;
      import com.revrobotics.spark.SparkLowLevel;
      import com.revrobotics.spark.SparkMax;
      import com.revrobotics.spark.SparkSim;
      import com.revrobotics.spark.config.SparkMaxConfig;
      import edu.wpi.first.math.system.plant.DCMotor;

      public final class Probe {
        public static String run() {
          StringBuilder out = new StringBuilder();
          SparkMax motor = new SparkMax(4, SparkLowLevel.MotorType.kBrushless);
          SparkMaxConfig config = new SparkMaxConfig();
          config.inverted(true);
          out.append("configure=").append(motor.configure(config, SparkBase.ResetMode.kResetSafeParameters, SparkBase.PersistMode.kPersistParameters)).append('\\n');
          motor.set(0.5);
          out.append("applied=").append(motor.getAppliedOutput()).append('\\n');
          out.append("id=").append(motor.getDeviceId()).append('\\n');

          SparkSim sim = new SparkSim(motor, DCMotor.getNEO(1));
          sim.enable();
          sim.setPosition(3.0);
          out.append("position=").append(motor.getEncoder().getPosition()).append('\\n');
          sim.iterate(100.0, 12.0, 0.02);
          out.append("moving=").append(motor.getEncoder().getVelocity() > 0).append('\\n');
          return out.toString();
        }
      }
      """;

  private static final String OTHERS =
      """
      package probe;

      public final class Probe {
        public static String run() {
          StringBuilder out = new StringBuilder();
          new com.reduxrobotics.sensors.canandmag.Canandmag(1).getPosition();
          out.append("redux=ok\\n");
          return out.toString();
        }
      }
      """;

  private static final String ADVANTAGEKIT =
      """
      package probe;

      import edu.wpi.first.hal.AllianceStationID;
      import edu.wpi.first.wpilibj.PowerDistribution;
      import edu.wpi.first.wpilibj.simulation.DriverStationSim;
      import edu.wpi.first.wpilibj.simulation.RoboRioSim;
      import org.littletonrobotics.conduit.ConduitApi;
      import org.littletonrobotics.junction.LoggedPowerDistribution;

      public final class Probe {
        public static String run() {
          DriverStationSim.setAllianceStationId(AllianceStationID.Blue2);
          DriverStationSim.setEnabled(true);
          DriverStationSim.setDsAttached(true);
          DriverStationSim.setEventName("CMPTX");
          DriverStationSim.setJoystickAxisCount(0, 2);
          DriverStationSim.setJoystickAxis(0, 1, 0.5);
          DriverStationSim.setJoystickButtonCount(0, 10);
          DriverStationSim.setJoystickButtons(0, 5);
          DriverStationSim.notifyNewData();
          RoboRioSim.setVInVoltage(11.5);
          LoggedPowerDistribution.getInstance(1, PowerDistribution.ModuleType.kCTRE);

          ConduitApi conduit = ConduitApi.getInstance();
          conduit.captureData();

          StringBuilder out = new StringBuilder();
          out.append("station=").append(conduit.getAllianceStation()).append('\\n');
          out.append("event=").append(conduit.getEventName()).append('\\n');
          out.append("enabled=").append((conduit.getControlWord() & 1) != 0).append('\\n');
          out.append("axis1=").append(conduit.getAxisValues(0)[1]).append('\\n');
          out.append("buttons=").append(conduit.getButtonValues(0)).append('/').append(conduit.getButtonCount(0)).append('\\n');
          out.append("vin=").append(conduit.getVoltageVin()).append('\\n');
          out.append("joystick=").append(conduit.getJoystickName(1).isEmpty()).append('\\n');
          out.append("pdp=").append(conduit.getPDPModuleId()).append('/').append(conduit.getPDPChannelCount()).append('\\n');
          return out.toString();
        }
      }
      """;

  public static void run(Tests t) {
    t.test("REVLib SPARK MAX works with SparkSim", () -> {
      String result = VendorProbe.run("REVLib", REV);
      if (result == null) {
        System.out.println("skipped: no REVLib in the vendor index");
        return;
      }
      VendorProbe.expectLines(result, "configure=kOk", "applied=0.5", "id=4", "position=3.0", "moving=true");
    });

    t.test("AdvantageKit reads the HAL through conduit", () -> {
      String result = VendorProbe.run("AdvantageKit", ADVANTAGEKIT);
      if (result == null) {
        System.out.println("skipped: no AdvantageKit in the vendor index");
        return;
      }
      VendorProbe.expectLines(
          result, "station=5", "event=CMPTX", "enabled=true", "axis1=0.5", "buttons=5/10", "vin=11.5", "joystick=true", "pdp=1/24");
    });

    t.test("ReduxLib accepts its own version and starts", () -> {
      String result = VendorProbe.run("ReduxLib", OTHERS);
      if (result == null) {
        System.out.println("skipped: no ReduxLib in the vendor index");
        return;
      }
      VendorProbe.expectLines(result, "redux=ok");
    });
  }
}
