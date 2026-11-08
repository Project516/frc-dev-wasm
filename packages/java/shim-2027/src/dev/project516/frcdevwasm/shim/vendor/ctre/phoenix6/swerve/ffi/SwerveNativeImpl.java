package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.swerve.ffi;

import com.ctre.phoenix6.swerve.ffi.SwerveNative.chassis_velocities_t;
import com.ctre.phoenix6.swerve.ffi.SwerveNative.pose_t;
import com.ctre.phoenix6.swerve.ffi.SwerveNative.swerve_control_params_t;
import com.ctre.phoenix6.swerve.ffi.SwerveNative.swerve_drive_state_t;
import com.ctre.phoenix6.swerve.ffi.SwerveNative.swerve_module_position_t;
import com.ctre.phoenix6.swerve.ffi.SwerveNative.swerve_module_request_t;
import com.ctre.phoenix6.swerve.ffi.SwerveNative.swerve_module_velocity_t;
import dev.project516.frcdevwasm.shim.vendor.Upcalls;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.PhoenixSim;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.SwerveSim;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.swerve.jni.SwerveJNIImpl;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.kinematics.ChassisVelocities;

/**
 * The swerve drivetrain of Phoenix 6 26.70 on the same {@link SwerveSim} as the JNI binding. Handles
 * to constants are the handles of {@link SwerveJNIImpl} as addresses. Requests that Java code wrote
 * run through the Java target of their upcall stub, with a parameters struct filled from the sim.
 */
public final class SwerveNativeImpl {
  private static final long TELEMETRY_MILLIS = 20;
  private static final Map<Integer, Thread> telemetry = new HashMap<>();

  private SwerveNativeImpl() {}

  private static MemorySegment handle(long handle) {
    return MemorySegment.ofAddress(handle);
  }

  private static double[] stdDevs(MemorySegment values) {
    return new double[] {
      values.getAtIndex(ValueLayout.JAVA_DOUBLE, 0),
      values.getAtIndex(ValueLayout.JAVA_DOUBLE, 1),
      values.getAtIndex(ValueLayout.JAVA_DOUBLE, 2)
    };
  }

  // constants

  public static MemorySegment c_ctre_phoenix6_swerve_create_drivetrain_constants(MemorySegment network, int pigeon2Id) {
    return handle(SwerveJNIImpl.JNI_CreateDrivetrainConstants("", pigeon2Id));
  }

  public static MemorySegment c_ctre_phoenix6_swerve_create_module_constants_arr(long numModules) {
    return handle(SwerveJNIImpl.JNI_CreateModuleConstantsArr(numModules));
  }

  public static void c_ctre_phoenix6_swerve_set_module_constants(
      MemorySegment constantsArr,
      long moduleIdx,
      int steerMotorId,
      int driveMotorId,
      int encoderId,
      double encoderOffset,
      double locationX,
      double locationY,
      boolean driveMotorInverted,
      boolean steerMotorInverted,
      boolean encoderInverted,
      double driveMotorGearRatio,
      double steerMotorGearRatio,
      double couplingGearRatio,
      double wheelRadius,
      int steerMotorClosedLoopOutput,
      int driveMotorClosedLoopOutput,
      double slipCurrent,
      double speedAt12Volts,
      int driveMotorType,
      int steerMotorType,
      int feedbackSource,
      double steerInertia,
      double driveInertia,
      double steerFrictionVoltage,
      double driveFrictionVoltage) {
    SwerveJNIImpl.JNI_SetModuleConstants(
        constantsArr.address(),
        moduleIdx,
        steerMotorId,
        driveMotorId,
        encoderId,
        encoderOffset,
        locationX,
        locationY,
        driveMotorInverted,
        steerMotorInverted,
        encoderInverted,
        driveMotorGearRatio,
        steerMotorGearRatio,
        couplingGearRatio,
        wheelRadius,
        steerMotorClosedLoopOutput,
        driveMotorClosedLoopOutput,
        slipCurrent,
        speedAt12Volts,
        driveMotorType,
        steerMotorType,
        feedbackSource,
        steerInertia,
        driveInertia,
        steerFrictionVoltage,
        driveFrictionVoltage);
  }

  // drivetrain

  public static int c_ctre_phoenix6_swerve_create_drivetrain(MemorySegment drivetrainConstants, MemorySegment moduleConstants, long numModules) {
    return SwerveJNIImpl.JNI_CreateDrivetrain(drivetrainConstants.address(), moduleConstants.address(), (int) numModules);
  }

  public static int c_ctre_phoenix6_swerve_create_drivetrain_with_freq(
      MemorySegment drivetrainConstants, double odometryUpdateFrequency, MemorySegment moduleConstants, long numModules) {
    return SwerveJNIImpl.JNI_CreateDrivetrainWithFreq(
        drivetrainConstants.address(), odometryUpdateFrequency, moduleConstants.address(), (int) numModules);
  }

  public static int c_ctre_phoenix6_swerve_create_drivetrain_with_stddev(
      MemorySegment drivetrainConstants,
      double odometryUpdateFrequency,
      MemorySegment odometryStdDevs,
      MemorySegment visionStdDevs,
      MemorySegment moduleConstants,
      long numModules) {
    return SwerveJNIImpl.JNI_CreateDrivetrainWithStddev(
        drivetrainConstants.address(),
        odometryUpdateFrequency,
        stdDevs(odometryStdDevs),
        stdDevs(visionStdDevs),
        moduleConstants.address(),
        (int) numModules);
  }

  public static void c_ctre_phoenix6_swerve_destroy_drivetrain(int id) {
    registerTelemetry(id, null, null);
    SwerveJNIImpl.JNI_DestroyDrivetrain(id);
  }

  public static boolean c_ctre_phoenix6_swerve_drivetrain_is_on_can_fd(int id) {
    return SwerveJNIImpl.JNI_IsOnCANFD(id);
  }

  public static double c_ctre_phoenix6_swerve_drivetrain_get_odometry_frequency(int id) {
    return SwerveJNIImpl.JNI_GetOdometryFrequency(id);
  }

  public static boolean c_ctre_phoenix6_swerve_drivetrain_is_odometry_valid(int id) {
    return SwerveJNIImpl.JNI_IsOdometryValid(id);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_tare_everything(int id) {
    SwerveJNIImpl.JNI_TareEverything(id);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_seed_field_centric(int id, double rotation) {
    SwerveJNIImpl.JNI_SeedFieldCentric(id, rotation);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_reset_pose(int id, double x, double y, double rotation) {
    SwerveJNIImpl.JNI_ResetPose(id, x, y, rotation);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_reset_translation(int id, double x, double y) {
    SwerveJNIImpl.JNI_ResetTranslation(id, x, y);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_reset_rotation(int id, double rotation) {
    SwerveJNIImpl.JNI_ResetRotation(id, rotation);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_set_operator_forward_direction(int id, double rotation) {
    SwerveJNIImpl.JNI_SetOperatorPerspectiveForward(id, rotation);
  }

  public static double c_ctre_phoenix6_swerve_drivetrain_get_operator_forward_direction(int id) {
    return SwerveJNIImpl.JNI_GetOperatorForwardDirection(id);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_add_vision_measurement(
      int id, double x, double y, double rotation, double timestamp) {
    SwerveJNIImpl.JNI_AddVisionMeasurement(id, x, y, rotation, timestamp);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_add_vision_measurement_with_stddev(
      int id, double x, double y, double rotation, double timestamp, MemorySegment visionStdDevs) {
    SwerveJNIImpl.JNI_AddVisionMeasurementWithStdDev(id, x, y, rotation, timestamp, stdDevs(visionStdDevs));
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_set_vision_measurement_stddevs(int id, MemorySegment visionStdDevs) {
    SwerveJNIImpl.JNI_SetVisionMeasurementStdDevs(id, stdDevs(visionStdDevs));
  }

  public static boolean c_ctre_phoenix6_swerve_drivetrain_sample_pose_at(int id, double timestamp, MemorySegment out) {
    SwerveSim sim = SwerveJNIImpl.sim(id);
    Optional<Pose2d> pose = sim == null ? Optional.empty() : sim.sampleAt(timestamp);
    pose.ifPresent(
        p -> {
          pose_t.x(out, p.getX());
          pose_t.y(out, p.getY());
          pose_t.theta(out, p.getRotation().getRadians());
        });
    return pose.isPresent();
  }

  // state and callbacks

  private static void fillState(MemorySegment state, SwerveSim sim) {
    int count = sim.modules.length;
    Pose2d pose = sim.pose();
    ChassisVelocities speeds = sim.speeds();
    MemorySegment statePose = swerve_drive_state_t.pose(state);
    pose_t.x(statePose, pose.getX());
    pose_t.y(statePose, pose.getY());
    pose_t.theta(statePose, pose.getRotation().getRadians());
    MemorySegment velocity = swerve_drive_state_t.velocity(state);
    chassis_velocities_t.vx(velocity, speeds.vx);
    chassis_velocities_t.vy(velocity, speeds.vy);
    chassis_velocities_t.omega(velocity, speeds.omega);
    MemorySegment positions = swerve_drive_state_t.modulePositions(state, count);
    MemorySegment velocities = swerve_drive_state_t.moduleVelocities(state, count);
    MemorySegment targets = swerve_drive_state_t.moduleTargets(state, count);
    for (int i = 0; i < count; i++) {
      double[] position = sim.modulePosition(i);
      MemorySegment modulePosition = swerve_module_position_t.asSlice(positions, i);
      swerve_module_position_t.distance(modulePosition, position[0]);
      swerve_module_position_t.angle(modulePosition, position[1]);
      double[] current = sim.moduleState(i);
      MemorySegment moduleVelocity = swerve_module_velocity_t.asSlice(velocities, i);
      swerve_module_velocity_t.velocity(moduleVelocity, current[0]);
      swerve_module_velocity_t.angle(moduleVelocity, current[1]);
      MemorySegment moduleTarget = swerve_module_velocity_t.asSlice(targets, i);
      swerve_module_velocity_t.velocity(moduleTarget, sim.target(i).velocity);
      swerve_module_velocity_t.angle(moduleTarget, sim.target(i).angle.getRadians());
    }
    swerve_drive_state_t.rawHeading(state, sim.rawHeading());
    swerve_drive_state_t.timestamp(state, sim.timestamp());
    swerve_drive_state_t.odometryPeriod(state, sim.odometryPeriod());
    swerve_drive_state_t.successfulDaqs(state, sim.successfulDaqs());
    swerve_drive_state_t.failedDaqs(state, 0);
  }

  private static void fillControlParams(MemorySegment params, SwerveSim sim) {
    ChassisVelocities speeds = sim.speeds();
    Pose2d pose = sim.pose();
    swerve_control_params_t.kMaxSpeedMps(params, sim.maxSpeed());
    swerve_control_params_t.operatorForwardDirection(params, sim.operatorForward());
    MemorySegment velocity = swerve_control_params_t.currentChassisVelocity(params);
    chassis_velocities_t.vx(velocity, speeds.vx);
    chassis_velocities_t.vy(velocity, speeds.vy);
    chassis_velocities_t.omega(velocity, speeds.omega);
    MemorySegment currentPose = swerve_control_params_t.currentPose(params);
    pose_t.x(currentPose, pose.getX());
    pose_t.y(currentPose, pose.getY());
    pose_t.theta(currentPose, pose.getRotation().getRadians());
    swerve_control_params_t.timestamp(params, sim.timestamp());
    swerve_control_params_t.updatePeriod(params, sim.odometryPeriod());
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_get_state(int id, MemorySegment state) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim sim = SwerveJNIImpl.sim(id);
      if (sim != null) {
        fillState(state, sim);
      }
    }
  }

  /** Runs a request that Java code wrote on every tick, through the target of its stub. */
  public static void c_ctre_phoenix6_swerve_drivetrain_set_control(int id, MemorySegment context, MemorySegment function) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim sim = SwerveJNIImpl.sim(id);
      if (sim == null) {
        return;
      }
      MethodHandle target = Upcalls.target(function);
      if (target == null) {
        sim.setControl(null);
        return;
      }
      sim.setControl(
          s -> {
            try (Arena arena = Arena.ofConfined()) {
              MemorySegment params = arena.allocate(swerve_control_params_t.LAYOUT);
              fillControlParams(params, s);
              target.invoke(context, params);
            } catch (Throwable e) {
              e.printStackTrace();
            }
          });
    }
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_register_telemetry(int id, MemorySegment context, MemorySegment function) {
    registerTelemetry(id, context, Upcalls.target(function));
  }

  private static void registerTelemetry(int id, MemorySegment context, MethodHandle target) {
    Thread previous;
    synchronized (PhoenixSim.LOCK) {
      previous = telemetry.remove(id);
      if (target != null && SwerveJNIImpl.sim(id) != null) {
        Thread thread =
            new Thread(
                () -> {
                  while (!Thread.currentThread().isInterrupted()) {
                    try {
                      Thread.sleep(TELEMETRY_MILLIS);
                    } catch (InterruptedException e) {
                      return;
                    }
                    SwerveSim sim = SwerveJNIImpl.sim(id);
                    if (sim == null) {
                      return;
                    }
                    try (Arena arena = Arena.ofConfined()) {
                      int count = sim.modules.length;
                      MemorySegment state = arena.allocate(swerve_drive_state_t.LAYOUT);
                      swerve_drive_state_t.modulePositions(state, arena.allocate(swerve_module_position_t.LAYOUT, count));
                      swerve_drive_state_t.moduleVelocities(state, arena.allocate(swerve_module_velocity_t.LAYOUT, count));
                      swerve_drive_state_t.moduleTargets(state, arena.allocate(swerve_module_velocity_t.LAYOUT, count));
                      synchronized (PhoenixSim.LOCK) {
                        fillState(state, sim);
                      }
                      target.invoke(context, state);
                    } catch (Throwable e) {
                      e.printStackTrace();
                    }
                  }
                },
                "phoenix-telemetry");
        thread.setDaemon(true);
        telemetry.put(id, thread);
        thread.start();
      }
    }
    if (previous != null) {
      previous.interrupt();
    }
  }

  // modules

  public static void c_ctre_phoenix6_swerve_module_apply(int id, long moduleIndex, MemorySegment request) {
    synchronized (PhoenixSim.LOCK) {
      SwerveSim sim = SwerveJNIImpl.sim(id);
      if (sim != null) {
        MemorySegment velocity = swerve_module_request_t.velocity(request);
        sim.applyModule(
            (int) moduleIndex,
            swerve_module_velocity_t.velocity(velocity),
            swerve_module_velocity_t.angle(velocity),
            swerve_module_request_t.driveRequest(request),
            swerve_module_request_t.steerRequest(request));
      }
    }
  }

  private static MemorySegment position(SegmentAllocator allocator, int id, long moduleIndex) {
    MemorySegment out = allocator.allocate(swerve_module_position_t.LAYOUT);
    SwerveSim sim = SwerveJNIImpl.sim(id);
    if (sim != null) {
      double[] p = sim.modulePosition((int) moduleIndex);
      swerve_module_position_t.distance(out, p[0]);
      swerve_module_position_t.angle(out, p[1]);
    }
    return out;
  }

  public static MemorySegment c_ctre_phoenix6_swerve_module_get_position(
      SegmentAllocator allocator, int id, long moduleIndex, boolean refresh) {
    return position(allocator, id, moduleIndex);
  }

  public static MemorySegment c_ctre_phoenix6_swerve_module_get_cached_position(SegmentAllocator allocator, int id, long moduleIndex) {
    return position(allocator, id, moduleIndex);
  }

  public static MemorySegment c_ctre_phoenix6_swerve_module_get_current_velocity(SegmentAllocator allocator, int id, long moduleIndex) {
    MemorySegment out = allocator.allocate(swerve_module_velocity_t.LAYOUT);
    SwerveSim sim = SwerveJNIImpl.sim(id);
    if (sim != null) {
      double[] s = sim.moduleState((int) moduleIndex);
      swerve_module_velocity_t.velocity(out, s[0]);
      swerve_module_velocity_t.angle(out, s[1]);
    }
    return out;
  }

  public static MemorySegment c_ctre_phoenix6_swerve_module_get_target_velocity(SegmentAllocator allocator, int id, long moduleIndex) {
    MemorySegment out = allocator.allocate(swerve_module_velocity_t.LAYOUT);
    SwerveSim sim = SwerveJNIImpl.sim(id);
    if (sim != null) {
      swerve_module_velocity_t.velocity(out, sim.target((int) moduleIndex).velocity);
      swerve_module_velocity_t.angle(out, sim.target((int) moduleIndex).angle.getRadians());
    }
    return out;
  }

  public static void c_ctre_phoenix6_swerve_module_reset_position(int id, long moduleIndex) {
    SwerveJNIImpl.JNI_Module_ResetPosition(id, (int) moduleIndex);
  }

  // native requests

  public static void c_ctre_phoenix6_swerve_drivetrain_set_control_idle(int id) {
    SwerveJNIImpl.JNI_SetControl_Idle(id);
  }

  public static int c_ctre_phoenix6_swerve_request_apply_idle(int id) {
    return SwerveJNIImpl.JNI_Request_Apply_Idle(id);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_set_control_swerve_drive_brake(int id, int driveRequestType, int steerRequestType) {
    SwerveJNIImpl.JNI_SetControl_SwerveDriveBrake(id, driveRequestType, steerRequestType);
  }

  public static int c_ctre_phoenix6_swerve_request_apply_swerve_drive_brake(int id, int driveRequestType, int steerRequestType) {
    return SwerveJNIImpl.JNI_Request_Apply_SwerveDriveBrake(id, driveRequestType, steerRequestType);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_set_control_field_centric(
      int id, double vx, double vy, double rotationalRate, double deadband, double rotationalDeadband, double corX, double corY, int driveRequestType, int steerRequestType, boolean desaturate, int forwardPerspective) {
    SwerveJNIImpl.JNI_SetControl_FieldCentric(id, vx, vy, rotationalRate, deadband, rotationalDeadband, corX, corY, driveRequestType, steerRequestType, desaturate, forwardPerspective);
  }

  public static int c_ctre_phoenix6_swerve_request_apply_field_centric(
      int id, double vx, double vy, double rotationalRate, double deadband, double rotationalDeadband, double corX, double corY, int driveRequestType, int steerRequestType, boolean desaturate, int forwardPerspective) {
    return SwerveJNIImpl.JNI_Request_Apply_FieldCentric(id, vx, vy, rotationalRate, deadband, rotationalDeadband, corX, corY, driveRequestType, steerRequestType, desaturate, forwardPerspective);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_set_control_robot_centric(
      int id, double vx, double vy, double rotationalRate, double deadband, double rotationalDeadband, double corX, double corY, int driveRequestType, int steerRequestType, boolean desaturate) {
    SwerveJNIImpl.JNI_SetControl_RobotCentric(id, vx, vy, rotationalRate, deadband, rotationalDeadband, corX, corY, driveRequestType, steerRequestType, desaturate);
  }

  public static int c_ctre_phoenix6_swerve_request_apply_robot_centric(
      int id, double vx, double vy, double rotationalRate, double deadband, double rotationalDeadband, double corX, double corY, int driveRequestType, int steerRequestType, boolean desaturate) {
    return SwerveJNIImpl.JNI_Request_Apply_RobotCentric(id, vx, vy, rotationalRate, deadband, rotationalDeadband, corX, corY, driveRequestType, steerRequestType, desaturate);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_set_control_point_wheels_at(int id, double direction, int driveRequestType, int steerRequestType) {
    SwerveJNIImpl.JNI_SetControl_PointWheelsAt(id, direction, driveRequestType, steerRequestType);
  }

  public static int c_ctre_phoenix6_swerve_request_apply_point_wheels_at(int id, double direction, int driveRequestType, int steerRequestType) {
    return SwerveJNIImpl.JNI_Request_Apply_PointWheelsAt(id, direction, driveRequestType, steerRequestType);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_set_control_apply_robot_velocity(
      int id, double vx, double vy, double omega, MemorySegment feedforwardX, long countX, MemorySegment feedforwardY, long countY, double corX, double corY, int driveRequestType, int steerRequestType, boolean desaturate) {
    SwerveJNIImpl.JNI_SetControl_ApplyRobotVelocity(id, vx, vy, omega, null, null, corX, corY, driveRequestType, steerRequestType, desaturate);
  }

  public static int c_ctre_phoenix6_swerve_request_apply_apply_robot_velocity(
      int id, double vx, double vy, double omega, MemorySegment feedforwardX, long countX, MemorySegment feedforwardY, long countY, double corX, double corY, int driveRequestType, int steerRequestType, boolean desaturate) {
    return SwerveJNIImpl.JNI_Request_Apply_ApplyRobotVelocity(id, vx, vy, omega, null, null, corX, corY, driveRequestType, steerRequestType, desaturate);
  }

  public static void c_ctre_phoenix6_swerve_drivetrain_set_control_apply_field_velocity(
      int id, double vx, double vy, double omega, MemorySegment feedforwardX, long countX, MemorySegment feedforwardY, long countY, double corX, double corY, int driveRequestType, int steerRequestType, boolean desaturate, int forwardPerspective) {
    SwerveJNIImpl.JNI_SetControl_ApplyFieldVelocity(id, vx, vy, omega, null, null, corX, corY, driveRequestType, steerRequestType, desaturate, forwardPerspective);
  }

  public static int c_ctre_phoenix6_swerve_request_apply_apply_field_velocity(
      int id, double vx, double vy, double omega, MemorySegment feedforwardX, long countX, MemorySegment feedforwardY, long countY, double corX, double corY, int driveRequestType, int steerRequestType, boolean desaturate, int forwardPerspective) {
    return SwerveJNIImpl.JNI_Request_Apply_ApplyFieldVelocity(id, vx, vy, omega, null, null, corX, corY, driveRequestType, steerRequestType, desaturate, forwardPerspective);
  }
}
