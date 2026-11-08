package dev.project516.frcdevwasm.test;

/** REVLib SPARK parameters and closed loops, driven the way a robot program drives SparkSim. */
public final class RevLibSimTest {
  private RevLibSimTest() {}

  private static final String PROBE =
      """
      package probe;

      import com.revrobotics.spark.*;
      import com.revrobotics.spark.config.*;
      import edu.wpi.first.math.system.plant.DCMotor;
      import edu.wpi.first.math.system.plant.LinearSystemId;
      import edu.wpi.first.wpilibj.simulation.DCMotorSim;

      public final class Probe {
        static String line(String key, double value) {
          return key + "=" + value + "\\n";
        }

        static SparkMax motor(int id, SparkMaxConfig config) {
          SparkMax motor = new SparkMax(id, SparkLowLevel.MotorType.kBrushless);
          motor.configure(config, SparkBase.ResetMode.kResetSafeParameters, SparkBase.PersistMode.kNoPersistParameters);
          return motor;
        }

        /** Runs the motor against a flywheel-like DC motor model and returns what it did. */
        static String simulate(SparkMax motor, SparkBase.ControlType type, double setpoint, int steps, StringBuilder out) {
          SparkSim sim = new SparkSim(motor, DCMotor.getNEO(1));
          sim.enable();
          DCMotorSim physics = new DCMotorSim(LinearSystemId.createDCMotorSystem(DCMotor.getNEO(1), 0.0005, 1), DCMotor.getNEO(1));
          for (int i = 0; i < steps; i++) {
            motor.getClosedLoopController().setSetpoint(setpoint, type);
            sim.iterate(physics.getAngularVelocityRPM(), 12.0, 0.02);
            physics.setInputVoltage(sim.getAppliedOutput() * 12);
            physics.update(0.02);
          }
          out.append(line("position", motor.getEncoder().getPosition()));
          out.append(line("velocity", motor.getEncoder().getVelocity()));
          out.append(line("output", sim.getAppliedOutput()));
          return out.toString();
        }

        public static String run() {
          StringBuilder out = new StringBuilder();

          SparkMaxConfig config = new SparkMaxConfig();
          config.inverted(true);
          config.closedLoop.p(0.4).i(0.001).d(0.02).outputRange(-0.8, 0.9);
          config.closedLoop.feedForward.kV(0.0021);
          config.encoder.positionConversionFactor(0.5).velocityConversionFactor(0.25);
          SparkMax first = motor(1, config);
          out.append(line("p", first.configAccessor.closedLoop.getP()));
          out.append(line("i", first.configAccessor.closedLoop.getI()));
          out.append(line("d", first.configAccessor.closedLoop.getD()));
          out.append(line("kV", first.configAccessor.closedLoop.feedForward.getkV()));
          out.append(line("max", first.configAccessor.closedLoop.getMaxOutput()));
          out.append(line("positionFactor", first.configAccessor.encoder.getPositionConversionFactor()));
          out.append(line("velocityFactor", first.configAccessor.encoder.getVelocityConversionFactor()));
          out.append(line("inverted", first.configAccessor.getInverted() ? 1 : 0));
          SparkMaxConfig reset = new SparkMaxConfig();
          first.configure(reset, SparkBase.ResetMode.kResetSafeParameters, SparkBase.PersistMode.kNoPersistParameters);
          out.append(line("pAfterReset", first.configAccessor.closedLoop.getP()));

          SparkMaxConfig positionConfig = new SparkMaxConfig();
          positionConfig.closedLoop.p(0.4).outputRange(-1, 1);
          StringBuilder positionOut = new StringBuilder();
          simulate(motor(2, positionConfig), SparkBase.ControlType.kPosition, 2.0, 150, positionOut);
          for (String l : positionOut.toString().split("\\n")) {
            out.append("position.").append(l).append('\\n');
          }

          SparkMaxConfig velocityConfig = new SparkMaxConfig();
          velocityConfig.closedLoop.p(0.0001).outputRange(-1, 1);
          velocityConfig.closedLoop.feedForward.kV(12.0 / 5676);
          StringBuilder velocityOut = new StringBuilder();
          simulate(motor(3, velocityConfig), SparkBase.ControlType.kVelocity, 3000, 150, velocityOut);
          for (String l : velocityOut.toString().split("\\n")) {
            out.append("velocity.").append(l).append('\\n');
          }
          return out.toString();
        }
      }
      """;

  private static double num(String result, String key) {
    for (String line : result.split("\n")) {
      if (line.startsWith(key + "=")) {
        return Double.parseDouble(line.substring(key.length() + 1));
      }
    }
    throw new AssertionError("no " + key + " in:\n" + result);
  }

  public static void run(Tests t) {
    t.test("REVLib SPARK parameters and closed loops follow SparkSim", () -> {
      String r = VendorProbe.run("REVLib", PROBE);
      if (r == null) {
        System.out.println("skipped: no REVLib in the vendor index");
        return;
      }
      Tests.near(0.4, num(r, "p"), 1e-6, "kP reads back");
      Tests.near(0.001, num(r, "i"), 1e-9, "kI reads back");
      Tests.near(0.02, num(r, "d"), 1e-6, "kD reads back");
      Tests.near(0.0021, num(r, "kV"), 1e-6, "kV reads back");
      Tests.near(0.9, num(r, "max"), 1e-6, "output range reads back");
      Tests.near(0.5, num(r, "positionFactor"), 1e-6, "position conversion factor");
      Tests.near(0.25, num(r, "velocityFactor"), 1e-6, "velocity conversion factor");
      Tests.near(1, num(r, "inverted"), 0, "inverted");
      Tests.near(0, num(r, "pAfterReset"), 1e-9, "reset clears the gains");
      Tests.near(2.0, num(r, "position.position"), 0.1, "position control converges");
      Tests.near(0, num(r, "position.output"), 0.1, "position control settles");
      Tests.near(3000, num(r, "velocity.velocity"), 90, "velocity control reaches its target");
    });
  }
}
