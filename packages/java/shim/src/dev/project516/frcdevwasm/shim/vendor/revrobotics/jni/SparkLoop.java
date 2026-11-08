package dev.project516.frcdevwasm.shim.vendor.revrobotics.jni;

import com.revrobotics.spark.config.SparkParameters;
import edu.wpi.first.math.trajectory.TrapezoidProfile;

/**
 * The closed loops of a SPARK, as SparkSim asks for them through the native sim hooks. Outputs are
 * duty cycle. Gains and feedforward in volts convert with the bus voltage the sim last reported.
 * Position and velocity are in the units of the conversion factors.
 */
final class SparkLoop {
  static final int VELOCITY = 1;
  static final int POSITION = 3;

  // The SPARK firmware loop runs every millisecond, and its I and D terms count in ticks.
  private static final double TICKS_PER_SECOND = 1000;

  private final SparkParams params;
  private double iAccum;
  private double previousError;
  private boolean hasPreviousError;
  private boolean profiling;
  private double profilePosition;
  private double profileVelocity;

  SparkLoop(SparkParams params) {
    this.params = params;
  }

  private float f(int base, int slot, int stride) {
    return params.getFloat(base + slot * stride);
  }

  double iAccum() {
    return iAccum;
  }

  void setIAccum(double value) {
    iAccum = value;
  }

  void reset() {
    iAccum = 0;
    hasPreviousError = false;
    profiling = false;
  }

  double profilePosition() {
    return profilePosition;
  }

  double profileVelocity() {
    return profileVelocity;
  }

  float output(int slot, int mode, double setpoint, double measured, double dt, double busVoltage) {
    double error = setpoint - measured;
    if (mode == POSITION && params.getBool(SparkParameters.kPositionPIDWrapEnable.value)) {
      double min = params.getFloat(SparkParameters.kPositionPIDMinInput.value);
      double range = params.getFloat(SparkParameters.kPositionPIDMaxInput.value) - min;
      if (range > 0) {
        error -= range * Math.rint(error / range);
      }
    }
    double feedforward = 0;
    if (mode == VELOCITY) {
      feedforward = f(SparkParameters.kS_0.value, slot, 5) * Math.signum(setpoint) + f(SparkParameters.kV_0.value, slot, 8) * setpoint;
    } else if (mode == POSITION) {
      feedforward =
          f(SparkParameters.kG_0.value, slot, 5)
              + f(SparkParameters.kCos_0.value, slot, 5) * Math.cos(2 * Math.PI * measured * f(SparkParameters.kCosRatio_0.value, slot, 5));
    }
    return clamp(pid(slot, error, dt) + feedforward / busVoltage, slot);
  }

  private double pid(int slot, double error, double dt) {
    double kI = f(SparkParameters.kI_0.value, slot, 8);
    double iZone = f(SparkParameters.kIZone_0.value, slot, 8);
    if (kI != 0 && (iZone == 0 || Math.abs(error) < iZone)) {
      iAccum += error * dt * TICKS_PER_SECOND;
      double max = f(SparkParameters.kIMaxAccum_0.value, slot, 4);
      if (max > 0) {
        iAccum = Math.max(-max, Math.min(max, iAccum));
      }
    }
    double derivative = dt > 0 && hasPreviousError ? (error - previousError) / (dt * TICKS_PER_SECOND) : 0;
    previousError = error;
    hasPreviousError = true;
    return f(SparkParameters.kP_0.value, slot, 8) * error + kI * iAccum + f(SparkParameters.kD_0.value, slot, 8) * derivative;
  }

  private float clamp(double output, int slot) {
    double min = f(SparkParameters.kOutputMin_0.value, slot, 8);
    double max = f(SparkParameters.kOutputMax_0.value, slot, 8);
    return (float) Math.max(min, Math.min(max, output));
  }

  /** Position units per second per velocity unit: velocities are per minute unless the factors say otherwise. */
  private double perSecond() {
    double positionFactor = params.getFloat(SparkParameters.kPositionConversionFactor.value);
    double velocityFactor = params.getFloat(SparkParameters.kVelocityConversionFactor.value);
    return velocityFactor == 0 ? 1.0 / 60 : positionFactor / (velocityFactor * 60);
  }

  float maxMotionPosition(int slot, double setpoint, double position, double velocity, double dt, double busVoltage) {
    double toPerSecond = perSecond();
    if (!profiling) {
      profilePosition = position;
      profileVelocity = velocity * toPerSecond;
      profiling = true;
    }
    double cruise = f(SparkParameters.kMAXMotionCruiseVelocity_0.value, slot, 5) * toPerSecond;
    double accel = f(SparkParameters.kMAXMotionMaxAccel_0.value, slot, 5) * toPerSecond;
    double previousVelocity = profileVelocity;
    if (cruise > 0 && accel > 0) {
      TrapezoidProfile.State state =
          new TrapezoidProfile(new TrapezoidProfile.Constraints(cruise, accel))
              .calculate(dt, new TrapezoidProfile.State(profilePosition, profileVelocity), new TrapezoidProfile.State(setpoint, 0));
      profilePosition = state.position;
      profileVelocity = state.velocity;
    } else {
      profilePosition = setpoint;
      profileVelocity = 0;
    }
    double acceleration = dt > 0 ? (profileVelocity - previousVelocity) / dt : 0;
    double velocityUnits = profileVelocity / toPerSecond;
    double feedforward =
        f(SparkParameters.kS_0.value, slot, 5) * Math.signum(velocityUnits)
            + f(SparkParameters.kV_0.value, slot, 8) * velocityUnits
            + f(SparkParameters.kA_0.value, slot, 5) * acceleration / toPerSecond
            + f(SparkParameters.kG_0.value, slot, 5)
            + f(SparkParameters.kCos_0.value, slot, 5) * Math.cos(2 * Math.PI * position * f(SparkParameters.kCosRatio_0.value, slot, 5));
    return clamp(pid(slot, profilePosition - position, dt) + feedforward / busVoltage, slot);
  }

  float maxMotionVelocity(int slot, double setpoint, double velocity, double dt, double busVoltage) {
    if (!profiling) {
      profileVelocity = velocity;
      profiling = true;
    }
    double accel = f(SparkParameters.kMAXMotionMaxAccel_0.value, slot, 5);
    double previousVelocity = profileVelocity;
    if (accel > 0) {
      double max = accel * dt;
      profileVelocity += Math.max(-max, Math.min(max, setpoint - profileVelocity));
    } else {
      profileVelocity = setpoint;
    }
    double acceleration = dt > 0 ? (profileVelocity - previousVelocity) / dt : 0;
    double feedforward =
        f(SparkParameters.kS_0.value, slot, 5) * Math.signum(profileVelocity)
            + f(SparkParameters.kV_0.value, slot, 8) * profileVelocity
            + f(SparkParameters.kA_0.value, slot, 5) * acceleration;
    return clamp(pid(slot, profileVelocity - velocity, dt) + feedforward / busVoltage, slot);
  }

  /** The smart current limit scales the output down when the motor draws more than the stall limit. */
  float currentLimit(float output, double current) {
    double limit = params.getInt(SparkParameters.kSmartCurrentStallLimit.value);
    if (limit > 0 && Math.abs(current) > limit) {
      return (float) (output * limit / Math.abs(current));
    }
    return output;
  }
}
