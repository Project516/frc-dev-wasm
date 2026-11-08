package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.controls.ffi;

import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.controls.jni.ControlJNIImpl;
import java.lang.foreign.MemorySegment;

/** Phoenix 6 26.70 control requests, which reach the same Talon FX output stage as the JNI ones. */
public final class ControlNativeImpl {
  private ControlNativeImpl() {}


  public static int c_ctre_phoenix6_RequestControlEmpty(
      MemorySegment canbus,
      int deviceHash,
      double updateTime) {
    return ControlJNIImpl.JNI_RequestControlEmpty(null, deviceHash, updateTime);
  }

  public static int c_ctre_phoenix6_RequestControlDutyCycleOut(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlDutyCycleOut(null, deviceHash, updateFrequency, Output, EnableFOC, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlTorqueCurrentFOC(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlTorqueCurrentFOC(null, deviceHash, updateFrequency, Output, MaxAbsDutyCycle, Deadband, OverrideCoastDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlVoltageOut(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlVoltageOut(null, deviceHash, updateFrequency, Output, EnableFOC, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlPositionDutyCycle(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlPositionDutyCycle(null, deviceHash, updateFrequency, Position, Velocity, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlPositionVoltage(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlPositionVoltage(null, deviceHash, updateFrequency, Position, Velocity, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlPositionTorqueCurrentFOC(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlPositionTorqueCurrentFOC(null, deviceHash, updateFrequency, Position, Velocity, FeedForward, Slot, OverrideCoastDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlVelocityDutyCycle(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlVelocityDutyCycle(null, deviceHash, updateFrequency, Velocity, Acceleration, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlVelocityVoltage(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlVelocityVoltage(null, deviceHash, updateFrequency, Velocity, Acceleration, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlVelocityTorqueCurrentFOC(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlVelocityTorqueCurrentFOC(null, deviceHash, updateFrequency, Velocity, Acceleration, FeedForward, Slot, OverrideCoastDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlMotionMagicDutyCycle(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlMotionMagicDutyCycle(null, deviceHash, updateFrequency, Position, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlMotionMagicVoltage(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlMotionMagicVoltage(null, deviceHash, updateFrequency, Position, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlMotionMagicTorqueCurrentFOC(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlMotionMagicTorqueCurrentFOC(null, deviceHash, updateFrequency, Position, FeedForward, Slot, OverrideCoastDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlFollower(
      MemorySegment canbus,
      int deviceHash,
      double updateFrequency,
      int LeaderID,
      int MotorAlignment) {
    return ControlJNIImpl.JNI_RequestControlFollower(null, deviceHash, updateFrequency, LeaderID, MotorAlignment);
  }

  public static int c_ctre_phoenix6_RequestControlStrictFollower(
      MemorySegment canbus,
      int deviceHash,
      double updateFrequency,
      int LeaderID) {
    return ControlJNIImpl.JNI_RequestControlStrictFollower(null, deviceHash, updateFrequency, LeaderID);
  }

  public static int c_ctre_phoenix6_RequestControlNeutralOut(
      MemorySegment canbus,
      int deviceHash,
      double updateFrequency,
      boolean UseTimesync) {
    return ControlJNIImpl.JNI_RequestControlNeutralOut(null, deviceHash, updateFrequency, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlCoastOut(
      MemorySegment canbus,
      int deviceHash,
      double updateFrequency,
      boolean UseTimesync) {
    return ControlJNIImpl.JNI_RequestControlCoastOut(null, deviceHash, updateFrequency, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlStaticBrake(
      MemorySegment canbus,
      int deviceHash,
      double updateFrequency,
      boolean UseTimesync) {
    return ControlJNIImpl.JNI_RequestControlStaticBrake(null, deviceHash, updateFrequency, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlMotionMagicVelocityDutyCycle(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlMotionMagicVelocityDutyCycle(null, deviceHash, updateFrequency, Velocity, Acceleration, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlMotionMagicVelocityTorqueCurrentFOC(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlMotionMagicVelocityTorqueCurrentFOC(null, deviceHash, updateFrequency, Velocity, Acceleration, FeedForward, Slot, OverrideCoastDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlMotionMagicVelocityVoltage(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlMotionMagicVelocityVoltage(null, deviceHash, updateFrequency, Velocity, Acceleration, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlMotionMagicExpoDutyCycle(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlMotionMagicExpoDutyCycle(null, deviceHash, updateFrequency, Position, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlMotionMagicExpoVoltage(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlMotionMagicExpoVoltage(null, deviceHash, updateFrequency, Position, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlMotionMagicExpoTorqueCurrentFOC(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlMotionMagicExpoTorqueCurrentFOC(null, deviceHash, updateFrequency, Position, FeedForward, Slot, OverrideCoastDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlDynamicMotionMagicDutyCycle(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlDynamicMotionMagicDutyCycle(null, deviceHash, updateFrequency, Position, Velocity, Acceleration, Jerk, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlDynamicMotionMagicVoltage(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlDynamicMotionMagicVoltage(null, deviceHash, updateFrequency, Position, Velocity, Acceleration, Jerk, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlDynamicMotionMagicTorqueCurrentFOC(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlDynamicMotionMagicTorqueCurrentFOC(null, deviceHash, updateFrequency, Position, Velocity, Acceleration, Jerk, FeedForward, Slot, OverrideCoastDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlDynamicMotionMagicExpoDutyCycle(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlDynamicMotionMagicExpoDutyCycle(null, deviceHash, updateFrequency, Position, kV, kA, Velocity, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlDynamicMotionMagicExpoVoltage(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlDynamicMotionMagicExpoVoltage(null, deviceHash, updateFrequency, Position, kV, kA, Velocity, EnableFOC, FeedForward, Slot, OverrideBrakeDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlDynamicMotionMagicExpoTorqueCurrentFOC(
      MemorySegment canbus,
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
    return ControlJNIImpl.JNI_RequestControlDynamicMotionMagicExpoTorqueCurrentFOC(null, deviceHash, updateFrequency, Position, kV, kA, Velocity, FeedForward, Slot, OverrideCoastDurNeutral, LimitForwardMotion, LimitReverseMotion, IgnoreHardwareLimits, IgnoreSoftwareLimits, UseTimesync);
  }

  public static int c_ctre_phoenix6_RequestControlModulateVBatOut(
      MemorySegment canbus,
      int deviceHash,
      double updateFrequency,
      double Output) {
    return ControlJNIImpl.JNI_RequestControlModulateVBatOut(null, deviceHash, updateFrequency, Output);
  }
}
