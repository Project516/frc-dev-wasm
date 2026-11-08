package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim;

import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.TalonFxSim.Kind;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.TalonFxSim.Request;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.TalonFxSim.Unit;
import org.wpilib.math.linalg.VecBuilder;
import org.wpilib.math.estimator.SwerveDrivePoseEstimator;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.kinematics.SwerveDriveKinematics;
import org.wpilib.math.kinematics.SwerveModulePosition;
import org.wpilib.math.kinematics.SwerveModuleVelocity;
import java.util.Optional;

/**
 * A CTRE swerve drivetrain. On hardware the drivetrain runs natively: an odometry thread reads the
 * modules and pigeon, and applies the swerve request to the motors. Here the same work happens
 * whenever a module motor or the drivetrain is touched, at most once per {@link #MIN_TICK}.
 * Odometry is a WPILib pose estimator over the module positions and the pigeon yaw.
 */
public final class SwerveSim {
  /** The module constants the application passes to the native drivetrain. */
  public record Module(
      int steerId,
      int driveId,
      int encoderId,
      double encoderOffset,
      double x,
      double y,
      boolean driveInverted,
      boolean steerInverted,
      boolean encoderInverted,
      double driveGearRatio,
      double steerGearRatio,
      double couplingGearRatio,
      double wheelRadius,
      int steerClosedLoopOutput,
      int driveClosedLoopOutput,
      double slipCurrent,
      double speedAt12Volts) {}

  /** A swerve request that persists and is applied again on every tick. */
  public interface Control {
    void apply(SwerveSim drivetrain);
  }

  private static final double MIN_TICK = 0.01;
  private static final int DRIVE_VELOCITY = 1;
  private static final int STEER_POSITION = 1;
  private static final int OUTPUT_TORQUE_CURRENT = 1;

  public final int id;
  public final Module[] modules;
  private final TalonFxSim[] drive;
  private final TalonFxSim[] steer;
  private final Pigeon2Sim pigeon;
  private final SwerveDriveKinematics kinematics;
  private final SwerveDrivePoseEstimator estimator;
  private final SwerveModuleVelocity[] targets;
  private final double maxSpeed;
  private final double odometryFrequency;

  private Control control;
  private double operatorForward;
  private double lastTick = Double.NEGATIVE_INFINITY;
  private int successfulDaqs;
  private boolean syncing;

  public SwerveSim(
      int id, int pigeonId, Module[] modules, double odometryFrequency, double[] odometryStdDevs, double[] visionStdDevs) {
    this.id = id;
    this.modules = modules;
    this.odometryFrequency = odometryFrequency;
    this.pigeon = PhoenixSim.pigeon(pigeonId);
    drive = new TalonFxSim[modules.length];
    steer = new TalonFxSim[modules.length];
    targets = new SwerveModuleVelocity[modules.length];
    Translation2d[] locations = new Translation2d[modules.length];
    double max = 0;
    for (int i = 0; i < modules.length; i++) {
      drive[i] = PhoenixSim.talon(modules[i].driveId());
      steer[i] = PhoenixSim.talon(modules[i].steerId());
      drive[i].setOwner(this);
      steer[i].setOwner(this);
      targets[i] = new SwerveModuleVelocity();
      locations[i] = new Translation2d(modules[i].x(), modules[i].y());
      max = Math.max(max, modules[i].speedAt12Volts());
    }
    maxSpeed = max;
    kinematics = new SwerveDriveKinematics(locations);
    estimator =
        new SwerveDrivePoseEstimator(
            kinematics,
            gyro(),
            positions(),
            new Pose2d(),
            VecBuilder.fill(odometryStdDevs[0], odometryStdDevs[1], odometryStdDevs[2]),
            VecBuilder.fill(visionStdDevs[0], visionStdDevs[1], visionStdDevs[2]));
  }

  public double maxSpeed() {
    return maxSpeed;
  }

  public double odometryFrequency() {
    return odometryFrequency;
  }

  /** The nominal period the application sees, as on the native thread. */
  public double odometryPeriod() {
    return 1 / odometryFrequency;
  }

  public double operatorForward() {
    return operatorForward;
  }

  public void setOperatorForward(double radians) {
    operatorForward = radians;
  }

  public void setControl(Control next) {
    synchronized (PhoenixSim.LOCK) {
      control = next;
      tick();
    }
  }

  // state

  private Rotation2d gyro() {
    return Rotation2d.fromDegrees(pigeon.yaw());
  }

  public double rawHeading() {
    return Math.toRadians(pigeon.yaw());
  }

  public SwerveModulePosition[] positions() {
    SwerveModulePosition[] out = new SwerveModulePosition[modules.length];
    for (int i = 0; i < out.length; i++) {
      double[] p = modulePosition(i);
      out[i] = new SwerveModulePosition(p[0], Rotation2d.fromRadians(p[1]));
    }
    return out;
  }

  public SwerveModuleVelocity[] states() {
    SwerveModuleVelocity[] out = new SwerveModuleVelocity[modules.length];
    for (int i = 0; i < out.length; i++) {
      double[] s = moduleState(i);
      out[i] = new SwerveModuleVelocity(s[0], Rotation2d.fromRadians(s[1]));
    }
    return out;
  }

  public SwerveModuleVelocity target(int i) {
    return targets[i];
  }

  /** Distance in meters and angle in radians. */
  public double[] modulePosition(int i) {
    Module m = modules[i];
    double steerRotations = steer[i].position();
    double wheelRotations = (drive[i].position() - steerRotations * m.couplingGearRatio()) / m.driveGearRatio();
    return new double[] {wheelRotations * 2 * Math.PI * m.wheelRadius(), steerRotations * 2 * Math.PI};
  }

  /** Speed in meters per second and angle in radians. */
  public double[] moduleState(int i) {
    Module m = modules[i];
    double steerRps = steer[i].velocity();
    double wheelRps = (drive[i].velocity() - steerRps * m.couplingGearRatio()) / m.driveGearRatio();
    return new double[] {wheelRps * 2 * Math.PI * m.wheelRadius(), steer[i].position() * 2 * Math.PI};
  }

  public Pose2d pose() {
    synchronized (PhoenixSim.LOCK) {
      sync();
      return estimator.getEstimatedPosition();
    }
  }

  /** Robot-relative speeds of the modules. */
  public ChassisVelocities speeds() {
    return kinematics.toChassisVelocities(states());
  }

  public double timestamp() {
    return PhoenixSim.now();
  }

  public int successfulDaqs() {
    return successfulDaqs;
  }

  public void resetPose(Pose2d pose) {
    synchronized (PhoenixSim.LOCK) {
      estimator.resetPosition(gyro(), positions(), pose);
    }
  }

  public void resetTranslation(Translation2d translation) {
    synchronized (PhoenixSim.LOCK) {
      estimator.resetTranslation(translation);
    }
  }

  public void resetRotation(Rotation2d rotation) {
    synchronized (PhoenixSim.LOCK) {
      estimator.resetRotation(rotation);
    }
  }

  public void tare() {
    synchronized (PhoenixSim.LOCK) {
      for (int i = 0; i < modules.length; i++) {
        resetModulePosition(i);
      }
      estimator.resetPosition(gyro(), positions(), new Pose2d());
    }
  }

  public void resetModulePosition(int i) {
    synchronized (PhoenixSim.LOCK) {
      drive[i].setPosition(0);
    }
  }

  public void addVisionMeasurement(Pose2d pose, double timestamp) {
    synchronized (PhoenixSim.LOCK) {
      estimator.addVisionMeasurement(pose, timestamp);
    }
  }

  public void addVisionMeasurement(Pose2d pose, double timestamp, double[] stdDevs) {
    synchronized (PhoenixSim.LOCK) {
      estimator.addVisionMeasurement(pose, timestamp, VecBuilder.fill(stdDevs[0], stdDevs[1], stdDevs[2]));
    }
  }

  /** Releases the motors, which then stop following this drivetrain's ticks. */
  public void close() {
    synchronized (PhoenixSim.LOCK) {
      for (int i = 0; i < modules.length; i++) {
        drive[i].setOwner(null);
        steer[i].setOwner(null);
      }
    }
  }

  public void setVisionStdDevs(double[] stdDevs) {
    synchronized (PhoenixSim.LOCK) {
      estimator.setVisionMeasurementStdDevs(VecBuilder.fill(stdDevs[0], stdDevs[1], stdDevs[2]));
    }
  }

  public Optional<Pose2d> sampleAt(double timestamp) {
    synchronized (PhoenixSim.LOCK) {
      return estimator.sampleAt(timestamp);
    }
  }

  // ticking

  /** Runs a tick if one is due. */
  void sync() {
    if (syncing) {
      return;
    }
    if (PhoenixSim.now() - lastTick >= MIN_TICK) {
      tick();
    }
  }

  private void tick() {
    if (syncing) {
      return;
    }
    syncing = true;
    try {
      double now = PhoenixSim.now();
      lastTick = now;
      estimator.updateWithTime(now, gyro(), positions());
      successfulDaqs++;
      if (control != null) {
        control.apply(this);
      }
    } finally {
      syncing = false;
    }
  }

  // requests

  /** Applies chassis speeds that are already in the robot frame. */
  public void applyRobotSpeeds(
      ChassisVelocities speeds, Translation2d centerOfRotation, boolean desaturate, int driveType, int steerType) {
    SwerveModuleVelocity[] states = kinematics.toSwerveModuleVelocities(speeds, centerOfRotation);
    if (desaturate) {
      SwerveDriveKinematics.desaturateWheelVelocities(states, maxSpeed);
    }
    for (int i = 0; i < states.length; i++) {
      applyModule(i, states[i].velocity, states[i].angle.getRadians(), driveType, steerType);
    }
  }

  public void applyFieldSpeeds(
      double vx, double vy, double omega, Translation2d centerOfRotation, boolean desaturate, int driveType, int steerType) {
    ChassisVelocities speeds = new ChassisVelocities(vx, vy, omega).toRobotRelative(pose().getRotation());
    applyRobotSpeeds(speeds.discretize(odometryPeriod()), centerOfRotation, desaturate, driveType, steerType);
  }

  public void applyBrake(int driveType, int steerType) {
    for (int i = 0; i < modules.length; i++) {
      applyModule(i, 0, Math.atan2(modules[i].y(), modules[i].x()), driveType, steerType);
    }
  }

  public void pointWheelsAt(double direction, int driveType, int steerType) {
    for (int i = 0; i < modules.length; i++) {
      applyModule(i, 0, direction, driveType, steerType);
    }
  }

  public void idle() {
    for (int i = 0; i < modules.length; i++) {
      drive[i].control(new Request(Kind.NEUTRAL, Unit.VOLTAGE));
      steer[i].control(new Request(Kind.NEUTRAL, Unit.VOLTAGE));
    }
  }

  /** Drives one module to a state. The wheel turns the short way and slows when it is off angle. */
  public void applyModule(int i, double speed, double angle, int driveType, int steerType) {
    Module m = modules[i];
    double current = steer[i].position() * 2 * Math.PI;
    double error = wrap(angle - current);
    if (Math.abs(error) > Math.PI / 2) {
      speed = -speed;
      angle += Math.PI;
      error = wrap(error + Math.PI);
    }
    speed *= Math.cos(error);
    targets[i] = new SwerveModuleVelocity(speed, Rotation2d.fromRadians(angle));

    Unit steerUnit = m.steerClosedLoopOutput() == OUTPUT_TORQUE_CURRENT ? Unit.TORQUE : Unit.VOLTAGE;
    Request steerRequest = new Request(steerType == STEER_POSITION ? Kind.POSITION : Kind.MOTION_MAGIC_EXPO, steerUnit);
    steerRequest.position = angle / (2 * Math.PI);
    steer[i].control(steerRequest);

    Request driveRequest;
    if (driveType == DRIVE_VELOCITY) {
      double rps = speed / (2 * Math.PI * m.wheelRadius()) * m.driveGearRatio() + steer[i].velocity() * m.couplingGearRatio();
      Unit driveUnit = m.driveClosedLoopOutput() == OUTPUT_TORQUE_CURRENT ? Unit.TORQUE : Unit.VOLTAGE;
      driveRequest = new Request(Kind.VELOCITY, driveUnit);
      driveRequest.velocity = rps;
    } else {
      driveRequest = new Request(Kind.OPEN_LOOP, Unit.VOLTAGE);
      driveRequest.output = speed / m.speedAt12Volts() * 12;
    }
    drive[i].control(driveRequest);
  }

  private static double wrap(double radians) {
    return radians - 2 * Math.PI * Math.rint(radians / (2 * Math.PI));
  }
}
