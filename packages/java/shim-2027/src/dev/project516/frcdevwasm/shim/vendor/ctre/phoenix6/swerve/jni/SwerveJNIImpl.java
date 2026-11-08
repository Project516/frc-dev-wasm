package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.swerve.jni;

import com.ctre.phoenix6.swerve.jni.SwerveJNI;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.PhoenixSim;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.SwerveSim;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.math.kinematics.ChassisVelocities;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * The native swerve drivetrain: constants, odometry, swerve requests and module access. The state
 * lives in {@link SwerveSim}. Telemetry callbacks run on their own thread at 50 Hz.
 */
public final class SwerveJNIImpl {
  private record DrivetrainConstants(int pigeonId) {}

  private static final double[] ODOMETRY_STD_DEVS = {0.1, 0.1, 0.1};
  private static final double[] VISION_STD_DEVS = {0.9, 0.9, 0.9};
  private static final double DEFAULT_ODOMETRY_HERTZ = 250;
  private static final long TELEMETRY_MILLIS = 20;
  private static final int FIELD_PERSPECTIVE_OPERATOR = 0;

  private static final Map<Long, Object> constants = new HashMap<>();
  private static final Map<Integer, SwerveSim> drivetrains = new HashMap<>();
  private static final Map<Integer, Long> controlOf = new HashMap<>();
  private static final Map<Long, Integer> controlOwner = new HashMap<>();
  private static final Map<Long, Thread> telemetry = new HashMap<>();
  // Above any real address, so the 26.70 binding's free_memory on a handle never frees a string.
  private static long nextHandle = 1L << 48;
  private static int nextId = 1;

  private SwerveJNIImpl() {}

  public static SwerveSim sim(int id) {
    synchronized (PhoenixSim.LOCK) {
      return drivetrains.get(id);
    }
  }

  // constants

  public static long JNI_CreateDrivetrainConstants(String canbusName, int pigeon2Id) {
    synchronized (PhoenixSim.LOCK) {
      long handle = nextHandle++;
      constants.put(handle, new DrivetrainConstants(pigeon2Id));
      return handle;
    }
  }

  public static long JNI_CreateModuleConstantsArr(long numModules) {
    synchronized (PhoenixSim.LOCK) {
      long handle = nextHandle++;
      constants.put(handle, new SwerveSim.Module[(int) numModules]);
      return handle;
    }
  }

  public static void JNI_SetModuleConstants(
      long constantsArr,
      long moduleIdx,
      int steerMotorId,
      int driveMotorId,
      int encoderId,
      double encoderOffset_tr,
      double locationX_m,
      double locationY_m,
      boolean driveMotorInverted,
      boolean steerMotorInverted,
      boolean encoderInverted,
      double driveMotorGearRatio,
      double steerMotorGearRatio,
      double couplingGearRatio,
      double wheelRadius_m,
      int steerMotorClosedLoopOutput,
      int driveMotorClosedLoopOutput,
      double slipCurrent_A,
      double speedAt12Volts_mps,
      int driveMotorType,
      int steerMotorType,
      int feedbackSource,
      double steerInertia_kg_sq_m,
      double driveInertia_kg_sq_m,
      double steerFrictionVoltage_V,
      double driveFrictionVoltage_V) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim.Module[] modules = (SwerveSim.Module[]) constants.get(constantsArr);
      modules[(int) moduleIdx] =
          new SwerveSim.Module(
              steerMotorId,
              driveMotorId,
              encoderId,
              encoderOffset_tr,
              locationX_m,
              locationY_m,
              driveMotorInverted,
              steerMotorInverted,
              encoderInverted,
              driveMotorGearRatio,
              steerMotorGearRatio,
              couplingGearRatio,
              wheelRadius_m,
              steerMotorClosedLoopOutput,
              driveMotorClosedLoopOutput,
              slipCurrent_A,
              speedAt12Volts_mps);
    }
  }

  public static void JNI_DestroyConstants(long constantsHandle) {
    synchronized (PhoenixSim.LOCK) {
      constants.remove(constantsHandle);
    }
  }

  // drivetrain

  public static int JNI_CreateDrivetrain(long drivetrainConstants, long moduleConstants, int numModules) {
    return create(drivetrainConstants, DEFAULT_ODOMETRY_HERTZ, ODOMETRY_STD_DEVS, VISION_STD_DEVS, moduleConstants);
  }

  public static int JNI_CreateDrivetrainWithFreq(
      long drivetrainConstants, double odometryUpdateFreqHz, long moduleConstants, int numModules) {
    return create(drivetrainConstants, odometryUpdateFreqHz, ODOMETRY_STD_DEVS, VISION_STD_DEVS, moduleConstants);
  }

  public static int JNI_CreateDrivetrainWithStddev(
      long drivetrainConstants,
      double odometryUpdateFreqHz,
      double[] odometryStandardDeviation,
      double[] visionStandardDeviation,
      long moduleConstants,
      int numModules) {
    return create(drivetrainConstants, odometryUpdateFreqHz, odometryStandardDeviation, visionStandardDeviation, moduleConstants);
  }

  private static int create(long drivetrainConstants, double hertz, double[] odometryStdDevs, double[] visionStdDevs, long moduleConstants) {
    synchronized (PhoenixSim.LOCK) {
      DrivetrainConstants drivetrain = (DrivetrainConstants) constants.get(drivetrainConstants);
      SwerveSim.Module[] modules = ((SwerveSim.Module[]) constants.get(moduleConstants)).clone();
      int id = nextId++;
      drivetrains.put(id, new SwerveSim(id, drivetrain.pigeonId(), modules, hertz > 0 ? hertz : DEFAULT_ODOMETRY_HERTZ, odometryStdDevs, visionStdDevs));
      return id;
    }
  }

  public static void JNI_DestroyDrivetrain(int id) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim sim = drivetrains.remove(id);
      if (sim != null) {
        sim.close();
      }
    }
  }

  public static boolean JNI_IsOnCANFD(int id) {
    return true;
  }

  public static double JNI_GetOdometryFrequency(int id) {
    SwerveSim sim = sim(id);
    return sim == null ? 0 : sim.odometryFrequency();
  }

  public static boolean JNI_IsOdometryValid(int id) {
    return sim(id) != null;
  }

  // requests

  public static long JNI_SetControl(SwerveJNI self, int id, IntSupplier request) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim sim = drivetrains.get(id);
      if (sim == null) {
        return 0;
      }
      if (request == null) {
        controlOf.remove(id);
        sim.setControl(null);
        return 0;
      }
      long handle = nextHandle++;
      controlOf.put(id, handle);
      controlOwner.put(handle, id);
      sim.setControl(
          s -> {
            fillControlParams(self, s);
            try {
              request.getAsInt();
            } catch (RuntimeException e) {
              e.printStackTrace();
            }
          });
      return handle;
    }
  }

  public static void JNI_DestroyControl(long handle) {
    synchronized (PhoenixSim.LOCK) {
      Integer id = controlOwner.remove(handle);
      if (id != null && controlOf.get(id) != null && controlOf.get(id) == handle) {
        controlOf.remove(id);
        SwerveSim sim = drivetrains.get(id);
        if (sim != null) {
          sim.setControl(null);
        }
      }
    }
  }

  private static void fillControlParams(SwerveJNI self, SwerveSim sim) {
    SwerveJNI.ControlParams params = self.controlParams;
    ChassisVelocities speeds = sim.speeds();
    Pose2d pose = sim.pose();
    params.kMaxSpeedMps = sim.maxSpeed();
    params.operatorForwardDirection = sim.operatorForward();
    params.currentChassisVelocityVx = speeds.vx;
    params.currentChassisVelocityVy = speeds.vy;
    params.currentChassisVelocityOmega = speeds.omega;
    params.currentPoseX = pose.getX();
    params.currentPoseY = pose.getY();
    params.currentPoseTheta = pose.getRotation().getRadians();
    params.timestamp = sim.timestamp();
    params.updatePeriod = sim.odometryPeriod();
  }

  private static void setNative(int id, SwerveSim.Control control) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim sim = drivetrains.get(id);
      if (sim != null) {
        controlOf.remove(id);
        sim.setControl(control);
      }
    }
  }

  private static int applyNow(int id, SwerveSim.Control control) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim sim = drivetrains.get(id);
      if (sim != null) {
        control.apply(sim);
      }
      return 0;
    }
  }

  private static SwerveSim.Control idle() {
    return SwerveSim::idle;
  }

  private static SwerveSim.Control brake(int driveType, int steerType) {
    return s -> s.applyBrake(driveType, steerType);
  }

  private static SwerveSim.Control fieldCentric(
      double vx, double vy, double omega, double deadband, double rotationalDeadband, double corX, double corY, int driveType, int steerType, boolean desaturate, int perspective) {
    return s -> {
      Translation2d v = new Translation2d(vx, vy);
      if (perspective == FIELD_PERSPECTIVE_OPERATOR) {
        v = v.rotateBy(Rotation2d.fromRadians(s.operatorForward()));
      }
      double x = v.getNorm() < deadband ? 0 : v.getX();
      double y = v.getNorm() < deadband ? 0 : v.getY();
      s.applyFieldSpeeds(x, y, Math.abs(omega) < rotationalDeadband ? 0 : omega, new Translation2d(corX, corY), desaturate, driveType, steerType);
    };
  }

  private static SwerveSim.Control robotCentric(
      double vx, double vy, double omega, double deadband, double rotationalDeadband, double corX, double corY, int driveType, int steerType, boolean desaturate) {
    return s -> {
      double norm = Math.hypot(vx, vy);
      ChassisVelocities speeds =
          new ChassisVelocities(norm < deadband ? 0 : vx, norm < deadband ? 0 : vy, Math.abs(omega) < rotationalDeadband ? 0 : omega);
      s.applyRobotSpeeds(speeds.discretize(s.odometryPeriod()), new Translation2d(corX, corY), desaturate, driveType, steerType);
    };
  }

  private static SwerveSim.Control robotSpeeds(
      double vx, double vy, double omega, double corX, double corY, int driveType, int steerType, boolean desaturate) {
    return s -> s.applyRobotSpeeds(new ChassisVelocities(vx, vy, omega), new Translation2d(corX, corY), desaturate, driveType, steerType);
  }

  private static SwerveSim.Control fieldSpeeds(
      double vx, double vy, double omega, double corX, double corY, int driveType, int steerType, boolean desaturate, int perspective) {
    return s -> {
      Translation2d v = new Translation2d(vx, vy);
      if (perspective == FIELD_PERSPECTIVE_OPERATOR) {
        v = v.rotateBy(Rotation2d.fromRadians(s.operatorForward()));
      }
      ChassisVelocities speeds = new ChassisVelocities(v.getX(), v.getY(), omega).toRobotRelative(s.pose().getRotation());
      s.applyRobotSpeeds(speeds, new Translation2d(corX, corY), desaturate, driveType, steerType);
    };
  }

  public static void JNI_SetControl_Idle(int id) {
    setNative(id, idle());
  }

  public static int JNI_Request_Apply_Idle(int id) {
    return applyNow(id, idle());
  }

  public static void JNI_SetControl_SwerveDriveBrake(int id, int driveRequestType, int steerRequestType) {
    setNative(id, brake(driveRequestType, steerRequestType));
  }

  public static int JNI_Request_Apply_SwerveDriveBrake(int id, int driveRequestType, int steerRequestType) {
    return applyNow(id, brake(driveRequestType, steerRequestType));
  }

  public static void JNI_SetControl_FieldCentric(
      int id, double velocityX_mps, double velocityY_mps, double rotationalRate_rad_per_s, double deadband_mps, double rotationalDeadband_rad_per_s, double centerOfRotationX, double centerOfRotationY, int driveRequestType, int steerRequestType, boolean desaturateWheelSpeeds, int forwardPerspective) {
    setNative(id, fieldCentric(velocityX_mps, velocityY_mps, rotationalRate_rad_per_s, deadband_mps, rotationalDeadband_rad_per_s, centerOfRotationX, centerOfRotationY, driveRequestType, steerRequestType, desaturateWheelSpeeds, forwardPerspective));
  }

  public static int JNI_Request_Apply_FieldCentric(
      int id, double velocityX_mps, double velocityY_mps, double rotationalRate_rad_per_s, double deadband_mps, double rotationalDeadband_rad_per_s, double centerOfRotationX, double centerOfRotationY, int driveRequestType, int steerRequestType, boolean desaturateWheelSpeeds, int forwardPerspective) {
    return applyNow(id, fieldCentric(velocityX_mps, velocityY_mps, rotationalRate_rad_per_s, deadband_mps, rotationalDeadband_rad_per_s, centerOfRotationX, centerOfRotationY, driveRequestType, steerRequestType, desaturateWheelSpeeds, forwardPerspective));
  }

  public static void JNI_SetControl_RobotCentric(
      int id, double velocityX_mps, double velocityY_mps, double rotationalRate_rad_per_s, double deadband_mps, double rotationalDeadband_rad_per_s, double centerOfRotationX, double centerOfRotationY, int driveRequestType, int steerRequestType, boolean desaturateWheelSpeeds) {
    setNative(id, robotCentric(velocityX_mps, velocityY_mps, rotationalRate_rad_per_s, deadband_mps, rotationalDeadband_rad_per_s, centerOfRotationX, centerOfRotationY, driveRequestType, steerRequestType, desaturateWheelSpeeds));
  }

  public static int JNI_Request_Apply_RobotCentric(
      int id, double velocityX_mps, double velocityY_mps, double rotationalRate_rad_per_s, double deadband_mps, double rotationalDeadband_rad_per_s, double centerOfRotationX, double centerOfRotationY, int driveRequestType, int steerRequestType, boolean desaturateWheelSpeeds) {
    return applyNow(id, robotCentric(velocityX_mps, velocityY_mps, rotationalRate_rad_per_s, deadband_mps, rotationalDeadband_rad_per_s, centerOfRotationX, centerOfRotationY, driveRequestType, steerRequestType, desaturateWheelSpeeds));
  }

  public static void JNI_SetControl_PointWheelsAt(int id, double moduleDirection, int driveRequestType, int steerRequestType) {
    setNative(id, s -> s.pointWheelsAt(moduleDirection, driveRequestType, steerRequestType));
  }

  public static int JNI_Request_Apply_PointWheelsAt(int id, double moduleDirection, int driveRequestType, int steerRequestType) {
    return applyNow(id, s -> s.pointWheelsAt(moduleDirection, driveRequestType, steerRequestType));
  }

  public static void JNI_SetControl_ApplyRobotVelocity(
      int id, double speedsVx, double speedsVy, double speedsOmega, double[] wheelForceFeedforwardsX_N, double[] wheelForceFeedforwardsY_N, double centerOfRotationX, double centerOfRotationY, int driveRequestType, int steerRequestType, boolean desaturateWheelSpeeds) {
    setNative(id, robotSpeeds(speedsVx, speedsVy, speedsOmega, centerOfRotationX, centerOfRotationY, driveRequestType, steerRequestType, desaturateWheelSpeeds));
  }

  public static int JNI_Request_Apply_ApplyRobotVelocity(
      int id, double speedsVx, double speedsVy, double speedsOmega, double[] wheelForceFeedforwardsX_N, double[] wheelForceFeedforwardsY_N, double centerOfRotationX, double centerOfRotationY, int driveRequestType, int steerRequestType, boolean desaturateWheelSpeeds) {
    return applyNow(id, robotSpeeds(speedsVx, speedsVy, speedsOmega, centerOfRotationX, centerOfRotationY, driveRequestType, steerRequestType, desaturateWheelSpeeds));
  }

  public static void JNI_SetControl_ApplyFieldVelocity(
      int id, double speedsVx, double speedsVy, double speedsOmega, double[] wheelForceFeedforwardsX_N, double[] wheelForceFeedforwardsY_N, double centerOfRotationX, double centerOfRotationY, int driveRequestType, int steerRequestType, boolean desaturateWheelSpeeds, int forwardPerspective) {
    setNative(id, fieldSpeeds(speedsVx, speedsVy, speedsOmega, centerOfRotationX, centerOfRotationY, driveRequestType, steerRequestType, desaturateWheelSpeeds, forwardPerspective));
  }

  public static int JNI_Request_Apply_ApplyFieldVelocity(
      int id, double speedsVx, double speedsVy, double speedsOmega, double[] wheelForceFeedforwardsX_N, double[] wheelForceFeedforwardsY_N, double centerOfRotationX, double centerOfRotationY, int driveRequestType, int steerRequestType, boolean desaturateWheelSpeeds, int forwardPerspective) {
    return applyNow(id, fieldSpeeds(speedsVx, speedsVy, speedsOmega, centerOfRotationX, centerOfRotationY, driveRequestType, steerRequestType, desaturateWheelSpeeds, forwardPerspective));
  }

  // state

  private static void fillState(SwerveJNI self, SwerveSim sim) {
    SwerveJNI.DriveState state = self.driveState;
    Pose2d pose = sim.pose();
    ChassisVelocities speeds = sim.speeds();
    state.PoseX = pose.getX();
    state.PoseY = pose.getY();
    state.PoseTheta = pose.getRotation().getRadians();
    state.VelocityVx = speeds.vx;
    state.VelocityVy = speeds.vy;
    state.VelocityOmega = speeds.omega;
    for (int i = 0; i < sim.modules.length; i++) {
      double[] current = sim.moduleState(i);
      state.ModuleVelocities[i].velocity = current[0];
      state.ModuleVelocities[i].angle = current[1];
      state.ModuleTargets[i].velocity = sim.target(i).velocity;
      state.ModuleTargets[i].angle = sim.target(i).angle.getRadians();
      double[] position = sim.modulePosition(i);
      state.ModulePositions[i].distance = position[0];
      state.ModulePositions[i].angle = position[1];
    }
    state.RawHeading = sim.rawHeading();
    state.Timestamp = sim.timestamp();
    state.OdometryPeriod = sim.odometryPeriod();
    state.SuccessfulDaqs = sim.successfulDaqs();
    state.FailedDaqs = 0;
  }

  public static void JNI_GetState(SwerveJNI self, int id) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim sim = drivetrains.get(id);
      if (sim != null) {
        fillState(self, sim);
      }
    }
  }

  public static long JNI_RegisterTelemetry(SwerveJNI self, int id, Runnable telemetryFunc) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim sim = drivetrains.get(id);
      if (sim == null || telemetryFunc == null) {
        return 0;
      }
      long handle = nextHandle++;
      Thread thread =
          new Thread(
              () -> {
                while (!Thread.currentThread().isInterrupted()) {
                  try {
                    Thread.sleep(TELEMETRY_MILLIS);
                  } catch (InterruptedException e) {
                    return;
                  }
                  synchronized (PhoenixSim.LOCK) {
                    fillState(self, sim);
                  }
                  try {
                    telemetryFunc.run();
                  } catch (RuntimeException e) {
                    e.printStackTrace();
                  }
                }
              },
              "phoenix-telemetry");
      thread.setDaemon(true);
      telemetry.put(handle, thread);
      thread.start();
      return handle;
    }
  }

  public static void JNI_DestroyTelemetry(long handle) {
    Thread thread;
    synchronized (PhoenixSim.LOCK) {
      thread = telemetry.remove(handle);
    }
    if (thread != null) {
      thread.interrupt();
    }
  }

  public static void JNI_TareEverything(int id) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      sim.tare();
    }
  }

  public static void JNI_SeedFieldCentric(int id, double rotationRad) {
    JNI_ResetRotation(id, rotationRad);
  }

  public static void JNI_ResetPose(int id, double xMeters, double yMeters, double rotationRad) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      sim.resetPose(new Pose2d(xMeters, yMeters, Rotation2d.fromRadians(rotationRad)));
    }
  }

  public static void JNI_ResetTranslation(int id, double xMeters, double yMeters) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      sim.resetTranslation(new Translation2d(xMeters, yMeters));
    }
  }

  public static void JNI_ResetRotation(int id, double rotationRad) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      sim.resetRotation(Rotation2d.fromRadians(rotationRad));
    }
  }

  public static void JNI_SetOperatorPerspectiveForward(int id, double rotationRad) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      sim.setOperatorForward(rotationRad);
    }
  }

  public static double JNI_GetOperatorForwardDirection(int id) {
    SwerveSim sim = sim(id);
    return sim == null ? 0 : sim.operatorForward();
  }

  public static void JNI_AddVisionMeasurement(int id, double xMeters, double yMeters, double rotationRad, double timestampSeconds) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      sim.addVisionMeasurement(new Pose2d(xMeters, yMeters, Rotation2d.fromRadians(rotationRad)), timestampSeconds);
    }
  }

  public static void JNI_AddVisionMeasurementWithStdDev(
      int id, double xMeters, double yMeters, double rotationRad, double timestampSeconds, double[] visionMeasurementStdDevs) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      sim.addVisionMeasurement(new Pose2d(xMeters, yMeters, Rotation2d.fromRadians(rotationRad)), timestampSeconds, visionMeasurementStdDevs);
    }
  }

  public static void JNI_SetVisionMeasurementStdDevs(int id, double[] visionMeasurementStdDevs) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      sim.setVisionStdDevs(visionMeasurementStdDevs);
    }
  }

  public static double[] JNI_SamplePoseAt(int id, double timestampSeconds) {
    SwerveSim sim = sim(id);
    Optional<Pose2d> pose = sim == null ? Optional.empty() : sim.sampleAt(timestampSeconds);
    return pose.map(p -> new double[] {p.getX(), p.getY(), p.getRotation().getRadians()}).orElse(null);
  }

  // modules

  public static void JNI_Module_Apply(SwerveJNI self, int id, int idx) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim sim = drivetrains.get(id);
      if (sim != null) {
        SwerveJNI.ModuleApplyParams p = self.moduleApplyParams;
        sim.applyModule(idx, p.velocity.velocity, p.velocity.angle, p.driveRequest, p.steerRequest);
      }
    }
  }

  public static void JNI_Module_GetPosition(SwerveJNI self, int id, int idx, boolean refresh) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      double[] p = sim.modulePosition(idx);
      self.modulePosition.distance = p[0];
      self.modulePosition.angle = p[1];
    }
  }

  public static void JNI_Module_GetCachedPosition(SwerveJNI self, int id, int idx) {
    JNI_Module_GetPosition(self, id, idx, false);
  }

  public static void JNI_Module_GetCurrentVelocity(SwerveJNI self, int id, int idx) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      double[] s = sim.moduleState(idx);
      self.moduleVelocity.velocity = s[0];
      self.moduleVelocity.angle = s[1];
    }
  }

  public static void JNI_Module_GetTargetVelocity(SwerveJNI self, int id, int idx) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      self.moduleVelocity.velocity = sim.target(idx).velocity;
      self.moduleVelocity.angle = sim.target(idx).angle.getRadians();
    }
  }

  public static void JNI_Module_ResetPosition(int id, int idx) {
    SwerveSim sim = sim(id);
    if (sim != null) {
      sim.resetModulePosition(idx);
    }
  }
}
