package dev.project516.frcdevwasm.test;

/**
 * Phoenix 6 device firmware on the shim: control requests, closed loops with a WPILib physics model
 * in the loop, and a Tuner X style swerve drivetrain. Time is paused and stepped, so runs are
 * deterministic.
 */
public final class Phoenix6SimTest {
  private Phoenix6SimTest() {}

  private static final String IMPORTS =
      """
      package probe;

      import com.ctre.phoenix6.StatusCode;
      import com.ctre.phoenix6.configs.*;
      import com.ctre.phoenix6.controls.*;
      import com.ctre.phoenix6.hardware.*;
      import com.ctre.phoenix6.signals.*;
      import com.ctre.phoenix6.sim.*;
      import edu.wpi.first.hal.HAL;
      import edu.wpi.first.math.system.plant.DCMotor;
      import edu.wpi.first.math.system.plant.LinearSystemId;
      import edu.wpi.first.wpilibj.simulation.DCMotorSim;
      import edu.wpi.first.wpilibj.simulation.DriverStationSim;
      import edu.wpi.first.wpilibj.simulation.SimHooks;
      """;

  private static final String PROLOGUE =
      """
        static void start(boolean enabled) {
          HAL.initialize(500, 0);
          SimHooks.pauseTiming();
          DriverStationSim.setEnabled(enabled);
          DriverStationSim.notifyNewData();
        }

        static String line(String key, double value) {
          return key + "=" + value + "\\n";
        }
      """;

  private static final String OPEN_LOOP =
      IMPORTS
          + """
      public final class Probe {
      """
          + PROLOGUE
          + """
        static TalonFX unlimited(int id, TalonFXConfiguration config) {
          config.CurrentLimits.StatorCurrentLimitEnable = false;
          config.CurrentLimits.SupplyCurrentLimitEnable = false;
          TalonFX talon = new TalonFX(id);
          talon.getConfigurator().apply(config);
          return talon;
        }

        public static String run() {
          start(true);
          StringBuilder out = new StringBuilder();

          TalonFXConfiguration config = new TalonFXConfiguration();
          config.Slot0.kP = 3.5;
          config.Slot0.kV = 0.12;
          config.Feedback.SensorToMechanismRatio = 5;
          TalonFX talon = new TalonFX(1);
          talon.getConfigurator().apply(config);
          TalonFXConfiguration read = new TalonFXConfiguration();
          talon.getConfigurator().refresh(read);
          out.append(line("readKP", read.Slot0.kP));
          out.append(line("readRatio", read.Feedback.SensorToMechanismRatio));
          TalonFXConfiguration fresh = new TalonFXConfiguration();
          new TalonFX(2).getConfigurator().refresh(fresh);
          out.append(line("freshRatio", fresh.Feedback.SensorToMechanismRatio));

          TalonFX duty = unlimited(3, new TalonFXConfiguration());
          duty.setControl(new DutyCycleOut(0.5));
          out.append(line("dutyVolts", duty.getSimState().getMotorVoltage()));
          out.append(line("dutySignal", duty.getDutyCycle().refresh().getValueAsDouble()));
          out.append(line("voltageSignal", duty.getMotorVoltage().refresh().getValueAsDouble()));
          out.append(line("supplySignal", duty.getSupplyVoltage().refresh().getValueAsDouble()));
          duty.setControl(new VoltageOut(-3));
          out.append(line("voltageOut", duty.getSimState().getMotorVoltage()));
          duty.setControl(new NeutralOut());
          out.append(line("neutral", duty.getSimState().getMotorVoltage()));
          duty.getSimState().setSupplyVoltage(10);
          duty.setControl(new DutyCycleOut(1.0));
          out.append(line("browned", duty.getSimState().getMotorVoltage()));

          TalonFXConfiguration invertedConfig = new TalonFXConfiguration();
          invertedConfig.MotorOutput.Inverted = InvertedValue.Clockwise_Positive;
          TalonFX inverted = unlimited(4, invertedConfig);
          inverted.setControl(new VoltageOut(3));
          out.append(line("invertedRaw", inverted.getSimState().getMotorVoltage()));
          inverted.getSimState().setRawRotorPosition(-1);
          out.append(line("invertedPosition", inverted.getPosition().refresh().getValueAsDouble()));

          TalonFX leader = unlimited(10, new TalonFXConfiguration());
          TalonFX follower = unlimited(11, new TalonFXConfiguration());
          leader.setControl(new DutyCycleOut(0.5));
          follower.setControl(new Follower(10, MotorAlignmentValue.Opposed));
          out.append(line("follower", follower.getSimState().getMotorVoltage()));

          TalonFX limited = new TalonFX(5);
          TalonFXConfiguration limits = new TalonFXConfiguration();
          limits.CurrentLimits.StatorCurrentLimit = 40;
          limits.CurrentLimits.StatorCurrentLimitEnable = true;
          limited.getConfigurator().apply(limits);
          limited.setControl(new VoltageOut(12));
          out.append(line("limitedStator", limited.getStatorCurrent().refresh().getValueAsDouble()));
          out.append(line("limitedVolts", limited.getSimState().getMotorVoltage()));

          TalonFX homed = new TalonFX(6);
          homed.getSimState().setRawRotorPosition(2);
          homed.setPosition(10);
          homed.getSimState().setRawRotorPosition(2.5);
          out.append(line("homed", homed.getPosition().refresh().getValueAsDouble()));

          DriverStationSim.setEnabled(false);
          DriverStationSim.notifyNewData();
          out.append(line("disabled", duty.getSimState().getMotorVoltage()));
          return out.toString();
        }
      }
      """;

  private static final String MOTOR_LOOP =
      """
        static final double GEAR = 10;

        static String run(String requestSetup, int steps, double[] samples) {
          start(true);
          TalonFXConfiguration config = new TalonFXConfiguration();
          configure(config);
          TalonFX talon = new TalonFX(1);
          talon.getConfigurator().apply(config);
          TalonFXSimState state = talon.getSimState();
          DCMotor gearbox = DCMotor.getKrakenX60Foc(1);
          DCMotorSim motor = new DCMotorSim(LinearSystemId.createDCMotorSystem(gearbox, 0.001, GEAR), gearbox);
          StringBuilder out = new StringBuilder();
          double maxVelocity = 0;
          for (int i = 1; i <= steps; i++) {
            state.setSupplyVoltage(12);
            talon.setControl(request());
            motor.setInputVoltage(state.getMotorVoltage());
            motor.update(0.02);
            state.setRawRotorPosition(motor.getAngularPositionRotations() * GEAR);
            state.setRotorVelocity(motor.getAngularVelocityRPM() / 60 * GEAR);
            SimHooks.stepTiming(0.02);
            double position = talon.getPosition().refresh().getValueAsDouble();
            double velocity = talon.getVelocity().refresh().getValueAsDouble();
            maxVelocity = Math.max(maxVelocity, velocity);
            for (double sample : samples) {
              if (i == Math.round(sample / 0.02)) {
                out.append(line("position@" + sample, position));
                out.append(line("velocity@" + sample, velocity));
              }
            }
          }
          out.append(line("position", talon.getPosition().refresh().getValueAsDouble()));
          out.append(line("velocity", talon.getVelocity().refresh().getValueAsDouble()));
          out.append(line("maxVelocity", maxVelocity));
          out.append(line("reference", talon.getClosedLoopReference().refresh().getValueAsDouble()));
          out.append(line("error", talon.getClosedLoopError().refresh().getValueAsDouble()));
          out.append(line("volts", talon.getMotorVoltage().refresh().getValueAsDouble()));
          return out.toString();
        }
      """;

  private static final String POSITION =
      IMPORTS
          + """
      public final class Probe {
      """
          + PROLOGUE
          + MOTOR_LOOP
          + """
        static void configure(TalonFXConfiguration config) {
          config.Slot0.kP = 20;
          config.Feedback.SensorToMechanismRatio = GEAR;
        }

        static ControlRequest request() {
          return new PositionVoltage(2.0);
        }

        public static String run() {
          return run("", 100, new double[0]);
        }
      }
      """;

  private static final String VELOCITY =
      IMPORTS
          + """
      public final class Probe {
      """
          + PROLOGUE
          + MOTOR_LOOP
          + """
        static void configure(TalonFXConfiguration config) {
          config.Slot0.kP = 0.5;
          config.Slot0.kV = 1.24;
          config.Feedback.SensorToMechanismRatio = GEAR;
        }

        static ControlRequest request() {
          return new VelocityVoltage(5.0);
        }

        public static String run() {
          return run("", 100, new double[0]);
        }
      }
      """;

  private static final String MOTION_MAGIC =
      IMPORTS
          + """
      public final class Probe {
      """
          + PROLOGUE
          + MOTOR_LOOP
          + """
        static void configure(TalonFXConfiguration config) {
          config.Slot0.kP = 20;
          config.Slot0.kV = 1.2;
          config.Feedback.SensorToMechanismRatio = GEAR;
          config.MotionMagic.MotionMagicCruiseVelocity = 4;
          config.MotionMagic.MotionMagicAcceleration = 8;
        }

        static ControlRequest request() {
          return new MotionMagicVoltage(8.0);
        }

        public static String run() {
          return run("", 200, new double[] {0.25, 1.0});
        }
      }
      """;

  private static final String SWERVE =
      """
      package probe;

      import static edu.wpi.first.units.Units.*;

      import com.ctre.phoenix6.configs.*;
      import com.ctre.phoenix6.hardware.*;
      import com.ctre.phoenix6.signals.StaticFeedforwardSignValue;
      import com.ctre.phoenix6.swerve.*;
      import com.ctre.phoenix6.swerve.SwerveModuleConstants.*;
      import edu.wpi.first.hal.HAL;
      import edu.wpi.first.math.geometry.Pose2d;
      import edu.wpi.first.math.geometry.Rotation2d;
      import edu.wpi.first.wpilibj.simulation.DriverStationSim;
      import edu.wpi.first.wpilibj.simulation.SimHooks;

      public final class Probe {
        static String line(String key, double value) {
          return key + "=" + value + "\\n";
        }

        static SwerveDrivetrain<TalonFX, TalonFX, CANcoder> drivetrain() {
          Slot0Configs steerGains =
              new Slot0Configs()
                  .withKP(100).withKI(0).withKD(0.5).withKS(0.1).withKV(1.91).withKA(0)
                  .withStaticFeedforwardSign(StaticFeedforwardSignValue.UseClosedLoopSign);
          Slot0Configs driveGains = new Slot0Configs().withKP(0.1).withKI(0).withKD(0).withKS(0).withKV(0.124);
          var creator =
              new SwerveModuleConstantsFactory<TalonFXConfiguration, TalonFXConfiguration, CANcoderConfiguration>()
                  .withDriveMotorGearRatio(5.14)
                  .withSteerMotorGearRatio(12.8)
                  .withCouplingGearRatio(3.5714)
                  .withWheelRadius(Inches.of(2))
                  .withSteerMotorGains(steerGains)
                  .withDriveMotorGains(driveGains)
                  .withSteerMotorClosedLoopOutput(ClosedLoopOutputType.Voltage)
                  .withDriveMotorClosedLoopOutput(ClosedLoopOutputType.Voltage)
                  .withSlipCurrent(Amps.of(120))
                  .withSpeedAt12Volts(MetersPerSecond.of(4.69))
                  .withDriveMotorType(DriveMotorArrangement.TalonFX_Integrated)
                  .withSteerMotorType(SteerMotorArrangement.TalonFX_Integrated)
                  .withFeedbackSource(SteerFeedbackType.FusedCANcoder)
                  .withDriveMotorInitialConfigs(new TalonFXConfiguration())
                  .withSteerMotorInitialConfigs(
                      new TalonFXConfiguration()
                          .withCurrentLimits(new CurrentLimitsConfigs().withStatorCurrentLimit(Amps.of(60)).withStatorCurrentLimitEnable(true)))
                  .withEncoderInitialConfigs(new CANcoderConfiguration())
                  .withSteerInertia(KilogramSquareMeters.of(0.004))
                  .withDriveInertia(KilogramSquareMeters.of(0.025))
                  .withSteerFrictionVoltage(Volts.of(0.2))
                  .withDriveFrictionVoltage(Volts.of(0.2));
          double x = Inches.of(10.5).in(Meters);
          return new SwerveDrivetrain<TalonFX, TalonFX, CANcoder>(
              TalonFX::new,
              TalonFX::new,
              CANcoder::new,
              new SwerveDrivetrainConstants().withCANBusName("rio").withPigeon2Id(0),
              creator.createModuleConstants(1, 11, 21, Rotations.of(0.1), Meters.of(x), Meters.of(x), false, true, false),
              creator.createModuleConstants(2, 12, 22, Rotations.of(-0.2), Meters.of(x), Meters.of(-x), true, true, false),
              creator.createModuleConstants(3, 13, 23, Rotations.of(0.3), Meters.of(-x), Meters.of(x), false, true, false),
              creator.createModuleConstants(4, 14, 24, Rotations.of(0), Meters.of(-x), Meters.of(-x), true, true, false));
        }

        static SwerveDrivetrain.SwerveDriveState drive(SwerveRequest request, double seconds, SwerveDrivetrain<TalonFX, TalonFX, CANcoder> drivetrain) {
          for (int i = 0; i < Math.round(seconds / 0.02); i++) {
            drivetrain.setControl(request);
            drivetrain.updateSimState(0.02, 12);
            SimHooks.stepTiming(0.02);
          }
          return drivetrain.getStateCopy();
        }

        public static String run() {
          HAL.initialize(500, 0);
          SimHooks.pauseTiming();
          DriverStationSim.setEnabled(true);
          DriverStationSim.notifyNewData();
          StringBuilder out = new StringBuilder();
          SwerveDrivetrain<TalonFX, TalonFX, CANcoder> drivetrain = drivetrain();
          drivetrain.setOperatorPerspectiveForward(Rotation2d.kZero);

          var state = drive(new SwerveRequest.RobotCentric().withDriveRequestType(SwerveModule.DriveRequestType.Velocity).withVelocityX(1.0), 1.0, drivetrain);
          out.append(line("forwardX", state.Pose.getX()));
          out.append(line("forwardY", state.Pose.getY()));
          out.append(line("forwardHeading", state.Pose.getRotation().getRadians()));
          out.append(line("forwardSpeed", state.Speeds.vxMetersPerSecond));
          out.append(line("moduleSpeed", state.ModuleStates[0].speedMetersPerSecond));
          out.append(line("moduleTarget", state.ModuleTargets[0].speedMetersPerSecond));
          out.append(line("pigeonYaw", drivetrain.getPigeon2().getYaw().refresh().getValueAsDouble()));

          drivetrain.resetPose(new Pose2d(3, 2, Rotation2d.kZero));
          state = drive(new SwerveRequest.FieldCentric().withDriveRequestType(SwerveModule.DriveRequestType.Velocity).withVelocityY(1.0), 1.0, drivetrain);
          out.append(line("strafeX", state.Pose.getX() - 3));
          out.append(line("strafeY", state.Pose.getY() - 2));

          state = drive(new SwerveRequest.RobotCentric().withRotationalRate(Math.PI / 2), 1.0, drivetrain);
          out.append(line("spinHeading", state.Pose.getRotation().getRadians()));
          out.append(line("spinYaw", drivetrain.getPigeon2().getYaw().refresh().getValueAsDouble()));

          state = drive(new SwerveRequest.SwerveDriveBrake(), 0.5, drivetrain);
          out.append(line("brakeSpeed", Math.abs(state.ModuleStates[0].speedMetersPerSecond)));
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

  private static String probe(String name, String source) throws Exception {
    String result = VendorProbe.run(name, source);
    if (result == null) {
      System.out.println("skipped: no " + name + " in the vendor index");
    }
    return result;
  }

  public static void run(Tests t) {
    t.test("Phoenix 6 TalonFX open loop requests, configs, followers and current limits", () -> {
      String r = probe("CTRE-Phoenix (v6)", OPEN_LOOP);
      if (r == null) {
        return;
      }
      Tests.near(3.5, num(r, "readKP"), 1e-9, "config reads back");
      Tests.near(5, num(r, "readRatio"), 1e-9, "config reads back");
      Tests.near(1, num(r, "freshRatio"), 1e-9, "default config");
      Tests.near(6, num(r, "dutyVolts"), 1e-6, "duty cycle to volts");
      Tests.near(0.5, num(r, "dutySignal"), 1e-6, "duty signal");
      Tests.near(6, num(r, "voltageSignal"), 1e-6, "voltage signal");
      Tests.near(12, num(r, "supplySignal"), 1e-6, "supply voltage signal");
      Tests.near(-3, num(r, "voltageOut"), 1e-6, "voltage request");
      Tests.near(0, num(r, "neutral"), 1e-6, "neutral request");
      Tests.near(10, num(r, "browned"), 1e-6, "output limited to the supply voltage");
      Tests.near(-3, num(r, "invertedRaw"), 1e-6, "inverted motor drives the rotor the other way");
      Tests.near(1, num(r, "invertedPosition"), 1e-6, "inverted motor reports the position in its own direction");
      Tests.near(-6, num(r, "follower"), 1e-6, "opposed follower");
      Tests.near(40, num(r, "limitedStator"), 0.5, "stator current limit");
      Tests.check(num(r, "limitedVolts") < 2, "voltage drops to hold the current limit");
      Tests.near(10.5, num(r, "homed"), 1e-6, "setPosition survives later rotor updates");
      Tests.near(0, num(r, "disabled"), 1e-6, "no output while disabled");
    });

    t.test("Phoenix 6 TalonFX PositionVoltage with a DCMotorSim converges", () -> {
      String r = probe("CTRE-Phoenix (v6)", POSITION);
      if (r == null) {
        return;
      }
      Tests.near(2.0, num(r, "position"), 0.02, "position");
      Tests.near(0, num(r, "velocity"), 0.05, "velocity");
      Tests.near(2.0, num(r, "reference"), 1e-6, "closed loop reference");
      Tests.near(0, num(r, "error"), 0.02, "closed loop error");
    });

    t.test("Phoenix 6 TalonFX VelocityVoltage with a DCMotorSim reaches its target", () -> {
      String r = probe("CTRE-Phoenix (v6)", VELOCITY);
      if (r == null) {
        return;
      }
      Tests.near(5.0, num(r, "velocity"), 0.1, "velocity");
      Tests.near(6.0, num(r, "volts"), 0.4, "voltage holds the speed");
    });

    t.test("Phoenix 6 TalonFX MotionMagicVoltage follows a trapezoid", () -> {
      String r = probe("CTRE-Phoenix (v6)", MOTION_MAGIC);
      if (r == null) {
        return;
      }
      // 8 rot/s^2 up to 4 rot/s: 0.5 s and 1 rot, cruise, then the mirror image
      Tests.near(0.25, num(r, "position@0.25"), 0.05, "position in the ramp");
      Tests.near(2.0, num(r, "velocity@0.25"), 0.15, "velocity in the ramp");
      Tests.near(3.0, num(r, "position@1.0"), 0.15, "position while cruising");
      Tests.near(4.0, num(r, "velocity@1.0"), 0.15, "cruise velocity");
      Tests.near(4.0, num(r, "maxVelocity"), 0.2, "peak velocity");
      Tests.near(8.0, num(r, "position"), 0.03, "final position");
    });

    t.test("Phoenix 6 swerve drivetrain moves the robot like a Tuner X project", () -> {
      String r = probe("CTRE-Phoenix (v6)", SWERVE);
      if (r == null) {
        return;
      }
      Tests.near(1.0, num(r, "forwardX"), 0.12, "1 m/s for 1 s");
      Tests.near(0, num(r, "forwardY"), 0.05, "straight");
      Tests.near(0, num(r, "forwardHeading"), 0.05, "no turn");
      Tests.near(1.0, num(r, "forwardSpeed"), 0.1, "chassis speed");
      Tests.near(1.0, num(r, "moduleSpeed"), 0.1, "module speed");
      Tests.near(1.0, num(r, "moduleTarget"), 0.01, "module target");
      Tests.near(0, num(r, "pigeonYaw"), 3, "pigeon yaw in degrees");
      Tests.near(0, num(r, "strafeX"), 0.1, "field centric strafe");
      Tests.near(0.9, num(r, "strafeY"), 0.15, "field centric strafe");
      Tests.near(Math.PI / 2, num(r, "spinHeading"), 0.2, "rotation");
      Tests.near(90, num(r, "spinYaw"), 12, "pigeon yaw follows the rotation");
      Tests.check(num(r, "brakeSpeed") < 0.1, "brake stops the wheels");
    });
  }
}
