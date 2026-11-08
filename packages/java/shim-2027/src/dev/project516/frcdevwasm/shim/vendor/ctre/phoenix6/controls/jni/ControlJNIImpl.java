package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.controls.jni;

import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.hardware.jni.HardwareJNIImpl;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.PhoenixSim;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.TalonFxSim;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.TalonFxSim.Kind;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.TalonFxSim.Request;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.TalonFxSim.Unit;
import java.nio.ByteBuffer;

/**
 * Talon FX control requests. Requests for other devices, differential and animation requests report
 * OK and do nothing.
 */
public final class ControlJNIImpl {
  private ControlJNIImpl() {}

  private static int send(int deviceHash, Request request) {
    HardwareJNIImpl.Device device = HardwareJNIImpl.deviceOf(deviceHash);
    if (device != null && device.model().equals("talon fx")) {
      PhoenixSim.talon(device.id()).control(request);
    }
    return 0;
  }

  private static Request neutral() {
    return new Request(Kind.NEUTRAL, Unit.VOLTAGE);
  }

  private static Request open(Unit unit, double output) {
    Request r = new Request(Kind.OPEN_LOOP, unit);
    r.output = output;
    return r;
  }

  private static Request follower(int leaderId, boolean opposed, boolean strict) {
    Request r = new Request(strict ? Kind.STRICT_FOLLOWER : Kind.FOLLOWER, Unit.DUTY);
    r.leaderId = leaderId;
    r.opposed = opposed;
    return r;
  }

  private static Request loop(
      Kind kind, Unit unit, double position, double velocity, double acceleration, double feedforward, int slot) {
    Request r = new Request(kind, unit);
    r.position = position;
    r.velocity = velocity;
    r.acceleration = acceleration;
    r.feedforward = feedforward;
    r.slot = slot;
    return r;
  }

  /** Dynamic requests carry their own profile limits, which replace the config. */
  private static Request dynamic(Request r, double cruiseVelocity, double jerk, double expoKv, double expoKa) {
    r.cruiseVelocity = cruiseVelocity;
    r.jerk = jerk;
    r.expoKv = expoKv;
    r.expoKa = expoKa;
    r.velocity = 0;
    return r;
  }

  public static int JNI_RequestControlEmpty(
      ByteBuffer canbus,
      int deviceHash,
      double updateTime) {
    return send(deviceHash, neutral());
  }

  public static int JNI_RequestControlDutyCycleOut(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Output,
      boolean EnableFOC,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, open(Unit.DUTY, Output));
  }

  public static int JNI_RequestControlTorqueCurrentFOC(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Output,
      double MaxAbsDutyCycle,
      double Deadband,
      boolean OverrideCoastDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, open(Unit.TORQUE, Output));
  }

  public static int JNI_RequestControlVoltageOut(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Output,
      boolean EnableFOC,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, open(Unit.VOLTAGE, Output));
  }

  public static int JNI_RequestControlPositionDutyCycle(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double Velocity,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.POSITION, Unit.DUTY, Position, Velocity, Double.NaN, FeedForward, Slot));
  }

  public static int JNI_RequestControlPositionVoltage(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double Velocity,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.POSITION, Unit.VOLTAGE, Position, Velocity, Double.NaN, FeedForward, Slot));
  }

  public static int JNI_RequestControlPositionTorqueCurrentFOC(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double Velocity,
      double FeedForward,
      int Slot,
      boolean OverrideCoastDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.POSITION, Unit.TORQUE, Position, Velocity, Double.NaN, FeedForward, Slot));
  }

  public static int JNI_RequestControlVelocityDutyCycle(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Velocity,
      double Acceleration,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.VELOCITY, Unit.DUTY, 0, Velocity, Acceleration, FeedForward, Slot));
  }

  public static int JNI_RequestControlVelocityVoltage(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Velocity,
      double Acceleration,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.VELOCITY, Unit.VOLTAGE, 0, Velocity, Acceleration, FeedForward, Slot));
  }

  public static int JNI_RequestControlVelocityTorqueCurrentFOC(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Velocity,
      double Acceleration,
      double FeedForward,
      int Slot,
      boolean OverrideCoastDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.VELOCITY, Unit.TORQUE, 0, Velocity, Acceleration, FeedForward, Slot));
  }

  public static int JNI_RequestControlMotionMagicDutyCycle(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.MOTION_MAGIC, Unit.DUTY, Position, 0, Double.NaN, FeedForward, Slot));
  }

  public static int JNI_RequestControlMotionMagicVoltage(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.MOTION_MAGIC, Unit.VOLTAGE, Position, 0, Double.NaN, FeedForward, Slot));
  }

  public static int JNI_RequestControlMotionMagicTorqueCurrentFOC(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double FeedForward,
      int Slot,
      boolean OverrideCoastDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.MOTION_MAGIC, Unit.TORQUE, Position, 0, Double.NaN, FeedForward, Slot));
  }

  public static int JNI_RequestControlFollower(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      int LeaderID,
      int MotorAlignment) {
    return send(deviceHash, follower(LeaderID, MotorAlignment == 1, false));
  }

  public static int JNI_RequestControlStrictFollower(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      int LeaderID) {
    return send(deviceHash, follower(LeaderID, false, true));
  }

  public static int JNI_RequestControlNeutralOut(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      boolean UseTimesync) {
    return send(deviceHash, neutral());
  }

  public static int JNI_RequestControlCoastOut(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      boolean UseTimesync) {
    return send(deviceHash, neutral());
  }

  public static int JNI_RequestControlStaticBrake(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      boolean UseTimesync) {
    return send(deviceHash, neutral());
  }

  public static int JNI_RequestControlMotionMagicVelocityDutyCycle(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Velocity,
      double Acceleration,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.MOTION_MAGIC_VELOCITY, Unit.DUTY, 0, Velocity, Acceleration, FeedForward, Slot));
  }

  public static int JNI_RequestControlMotionMagicVelocityTorqueCurrentFOC(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Velocity,
      double Acceleration,
      double FeedForward,
      int Slot,
      boolean OverrideCoastDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.MOTION_MAGIC_VELOCITY, Unit.TORQUE, 0, Velocity, Acceleration, FeedForward, Slot));
  }

  public static int JNI_RequestControlMotionMagicVelocityVoltage(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Velocity,
      double Acceleration,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.MOTION_MAGIC_VELOCITY, Unit.VOLTAGE, 0, Velocity, Acceleration, FeedForward, Slot));
  }

  public static int JNI_RequestControlMotionMagicExpoDutyCycle(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.MOTION_MAGIC_EXPO, Unit.DUTY, Position, 0, Double.NaN, FeedForward, Slot));
  }

  public static int JNI_RequestControlMotionMagicExpoVoltage(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.MOTION_MAGIC_EXPO, Unit.VOLTAGE, Position, 0, Double.NaN, FeedForward, Slot));
  }

  public static int JNI_RequestControlMotionMagicExpoTorqueCurrentFOC(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double FeedForward,
      int Slot,
      boolean OverrideCoastDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, loop(Kind.MOTION_MAGIC_EXPO, Unit.TORQUE, Position, 0, Double.NaN, FeedForward, Slot));
  }

  public static int JNI_RequestControlDynamicMotionMagicDutyCycle(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double Velocity,
      double Acceleration,
      double Jerk,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, dynamic(loop(Kind.MOTION_MAGIC, Unit.DUTY, Position, 0, Acceleration, FeedForward, Slot), Velocity, Jerk, Double.NaN, Double.NaN));
  }

  public static int JNI_RequestControlDynamicMotionMagicVoltage(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double Velocity,
      double Acceleration,
      double Jerk,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, dynamic(loop(Kind.MOTION_MAGIC, Unit.VOLTAGE, Position, 0, Acceleration, FeedForward, Slot), Velocity, Jerk, Double.NaN, Double.NaN));
  }

  public static int JNI_RequestControlDynamicMotionMagicTorqueCurrentFOC(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double Velocity,
      double Acceleration,
      double Jerk,
      double FeedForward,
      int Slot,
      boolean OverrideCoastDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, dynamic(loop(Kind.MOTION_MAGIC, Unit.TORQUE, Position, 0, Acceleration, FeedForward, Slot), Velocity, Jerk, Double.NaN, Double.NaN));
  }

  public static int JNI_RequestControlDynamicMotionMagicExpoDutyCycle(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double kV,
      double kA,
      double Velocity,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, dynamic(loop(Kind.MOTION_MAGIC_EXPO, Unit.DUTY, Position, 0, Double.NaN, FeedForward, Slot), Velocity, Double.NaN, kV, kA));
  }

  public static int JNI_RequestControlDynamicMotionMagicExpoVoltage(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double kV,
      double kA,
      double Velocity,
      boolean EnableFOC,
      double FeedForward,
      int Slot,
      boolean OverrideBrakeDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, dynamic(loop(Kind.MOTION_MAGIC_EXPO, Unit.VOLTAGE, Position, 0, Double.NaN, FeedForward, Slot), Velocity, Double.NaN, kV, kA));
  }

  public static int JNI_RequestControlDynamicMotionMagicExpoTorqueCurrentFOC(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Position,
      double kV,
      double kA,
      double Velocity,
      double FeedForward,
      int Slot,
      boolean OverrideCoastDurNeutral,
      boolean LimitForwardMotion,
      boolean LimitReverseMotion,
      boolean IgnoreHardwareLimits,
      boolean IgnoreSoftwareLimits,
      boolean UseTimesync) {
    return send(deviceHash, dynamic(loop(Kind.MOTION_MAGIC_EXPO, Unit.TORQUE, Position, 0, Double.NaN, FeedForward, Slot), Velocity, Double.NaN, kV, kA));
  }

  public static int JNI_RequestControlModulateVBatOut(
      ByteBuffer canbus,
      int deviceHash,
      double updateFrequency,
      double Output) {
    return send(deviceHash, open(Unit.DUTY, Output));
  }
}
