package dev.project516.frcdevwasm.shim.vendor.revrobotics.jni;

import com.revrobotics.MutableInt;
import edu.wpi.first.hal.SimDevice;
import edu.wpi.first.hal.SimDouble;
import edu.wpi.first.hal.SimInt;
import java.util.HashMap;
import java.util.Map;

/**
 * SPARK controllers exist as simulation devices named like the native library names them, so
 * REVLib's own {@code SparkSim} works on them. Setpoint commands and encoder readings go through
 * the device values. Parameters, configs and the closed loops behind {@code SparkSim.iterate} are
 * emulated; everything else reads as idle.
 */
public final class CANSparkJNIImpl {
  private static final int MODEL_FLEX = 1;
  private static final int CONTROL_DUTY_CYCLE = 0;
  private static final int MAX_MOTION_POSITION = 5;
  private static final int MAX_MOTION_VELOCITY = 6;
  private static final int INVERTED_PARAMETER = 45;
  private static final int ALLOWED_ERROR_PARAMETER = 97;

  private static final class Spark {
    final int deviceId;
    final int model;
    final SimDevice device;
    final SimDouble appliedOutput;
    final SimDouble position;
    final SimDouble velocity;
    final SimDouble busVoltage;
    final SimDouble motorCurrent;
    final SimDouble setpoint;
    final SimDouble arbFeedforward;
    final SimInt closedLoopSlot;
    final SimInt arbFeedforwardUnits;
    final SimInt controlMode;
    final SparkParams params = new SparkParams();
    final SparkLoop loop = new SparkLoop(params);

    Spark(int deviceId, int model) {
      this.deviceId = deviceId;
      this.model = model;
      device = SimDevice.create((model == MODEL_FLEX ? "SPARK Flex" : "SPARK MAX") + " [" + deviceId + "]");
      appliedOutput = double64("Applied Output", 0);
      position = double64("Position", 0);
      velocity = double64("Velocity", 0);
      busVoltage = double64("Bus Voltage", 12);
      motorCurrent = double64("Motor Current", 0);
      setpoint = double64("Setpoint", 0);
      arbFeedforward = double64("Arbitrary Feedforward", 0);
      closedLoopSlot = int32("Closed Loop Slot");
      arbFeedforwardUnits = int32("ArbFF Units");
      controlMode = int32("Control Mode");
    }

    private SimDouble double64(String name, double initial) {
      return device.createDouble(name, SimDevice.Direction.kBidir, initial);
    }

    private SimInt int32(String name) {
      return device.createInt(name, SimDevice.Direction.kBidir, 0);
    }
  }

  private static final Map<Long, Spark> sparks = new HashMap<>();
  private static long nextHandle = 1;

  private CANSparkJNIImpl() {}

  private static synchronized Spark spark(long handle) {
    return sparks.get(handle);
  }

  public static synchronized long c_Spark_Create(int deviceId, int motorType, int model, MutableInt status) {
    status.value = 0;
    long handle = nextHandle++;
    sparks.put(handle, new Spark(deviceId, model));
    return handle;
  }

  public static synchronized void c_Spark_Destroy(long handle) {
    Spark spark = sparks.remove(handle);
    if (spark != null) {
      spark.device.close();
    }
  }

  public static int c_Spark_GetDeviceId(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : spark.deviceId;
  }

  public static int c_Spark_GetSparkModel(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : spark.model;
  }

  public static int c_Spark_SetpointCommand(
      long handle, float setpoint, int controlType, int slot, float arbFeedforward, int arbFeedforwardUnits) {
    Spark spark = spark(handle);
    if (spark != null) {
      if (controlType != spark.controlMode.get() || slot != spark.closedLoopSlot.get()) {
        spark.loop.reset();
      }
      spark.setpoint.set(setpoint);
      spark.controlMode.set(controlType);
      spark.closedLoopSlot.set(slot);
      spark.arbFeedforward.set(arbFeedforward);
      spark.arbFeedforwardUnits.set(arbFeedforwardUnits);
      if (controlType == CONTROL_DUTY_CYCLE) {
        spark.appliedOutput.set(setpoint);
      }
    }
    return 0;
  }

  public static float c_Spark_GetSetpoint(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : (float) spark.setpoint.get();
  }

  public static float c_Spark_GetAppliedOutput(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : (float) spark.appliedOutput.get();
  }

  public static void c_Spark_SetSimAppliedOutput(long handle, float output) {
    Spark spark = spark(handle);
    if (spark != null) {
      spark.appliedOutput.set(output);
    }
  }

  public static float c_Spark_GetEncoderPosition(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : (float) spark.position.get();
  }

  public static float c_Spark_GetEncoderVelocity(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : (float) spark.velocity.get();
  }

  public static int c_Spark_SetEncoderPosition(long handle, float position) {
    Spark spark = spark(handle);
    if (spark != null) {
      spark.position.set(position);
    }
    return 0;
  }

  public static float c_Spark_GetBusVoltage(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : (float) spark.busVoltage.get();
  }

  public static float c_Spark_GetOutputCurrent(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : (float) spark.motorCurrent.get();
  }

  // parameters

  public static int c_Spark_GetParameterType(int paramId) {
    return SparkParams.typeOf(paramId);
  }

  public static int c_Spark_Configure(long handle, String flattenedString, boolean resetSafeParameters, boolean persistParameters) {
    Spark spark = spark(handle);
    if (spark != null) {
      if (resetSafeParameters) {
        spark.params.reset();
      }
      spark.params.apply(flattenedString);
    }
    return 0;
  }

  public static int c_Spark_ConfigureAsync(long handle, String flattenedString, boolean resetSafeParameters, boolean persistParameters) {
    return c_Spark_Configure(handle, flattenedString, resetSafeParameters, persistParameters);
  }

  public static int c_Spark_ResetSafeParameters(long handle, boolean persist) {
    Spark spark = spark(handle);
    if (spark != null) {
      spark.params.reset();
    }
    return 0;
  }

  public static int c_Spark_SetParameterFloat32(long handle, int paramId, float value) {
    Spark spark = spark(handle);
    if (spark != null) {
      spark.params.set(paramId, value);
    }
    return 0;
  }

  public static int c_Spark_SetParameterInt32(long handle, int paramId, int value) {
    Spark spark = spark(handle);
    if (spark != null) {
      spark.params.set(paramId, value);
    }
    return 0;
  }

  public static int c_Spark_SetParameterUint32(long handle, int paramId, int value) {
    return c_Spark_SetParameterInt32(handle, paramId, value);
  }

  public static int c_Spark_SetParameterBool(long handle, int paramId, boolean value) {
    Spark spark = spark(handle);
    if (spark != null) {
      spark.params.set(paramId, value);
    }
    return 0;
  }

  public static float c_Spark_GetParameterFloat32(long handle, int paramId) {
    Spark spark = spark(handle);
    return spark == null ? 0 : spark.params.getFloat(paramId);
  }

  public static int c_Spark_GetParameterInt32(long handle, int paramId) {
    Spark spark = spark(handle);
    return spark == null ? 0 : spark.params.getInt(paramId);
  }

  public static int c_Spark_GetParameterUint32(long handle, int paramId) {
    return c_Spark_GetParameterInt32(handle, paramId);
  }

  public static boolean c_Spark_GetParameterBool(long handle, int paramId) {
    Spark spark = spark(handle);
    return spark != null && spark.params.getBool(paramId);
  }

  public static int c_Spark_SetInverted(long handle, boolean inverted) {
    return c_Spark_SetParameterBool(handle, INVERTED_PARAMETER, inverted);
  }

  public static boolean c_Spark_GetInverted(long handle) {
    return c_Spark_GetParameterBool(handle, INVERTED_PARAMETER);
  }

  // closed loops

  private static double busVoltage(Spark spark) {
    double volts = spark.busVoltage.get();
    return volts > 0 ? volts : 12;
  }

  public static float c_Spark_GetSimClosedLoopOutput(long handle, float setpoint, float pv, float dt) {
    Spark spark = spark(handle);
    if (spark == null) {
      return 0;
    }
    return spark.loop.output((int) spark.closedLoopSlot.get(), (int) spark.controlMode.get(), setpoint, pv, dt, busVoltage(spark));
  }

  public static float c_Spark_GetSimMAXMotionPositionControlOutput(long handle, float dt) {
    Spark spark = spark(handle);
    if (spark == null) {
      return 0;
    }
    return spark.loop.maxMotionPosition((int) spark.closedLoopSlot.get(), spark.setpoint.get(), spark.position.get(), spark.velocity.get(), dt, busVoltage(spark));
  }

  public static float c_Spark_GetSimMAXMotionVelocityControlOutput(long handle, float dt) {
    Spark spark = spark(handle);
    if (spark == null) {
      return 0;
    }
    return spark.loop.maxMotionVelocity((int) spark.closedLoopSlot.get(), spark.setpoint.get(), spark.velocity.get(), dt, busVoltage(spark));
  }

  public static float c_Spark_GetSimCurrentLimitOutput(long handle, float appliedOutput, float current) {
    Spark spark = spark(handle);
    return spark == null ? appliedOutput : spark.loop.currentLimit(appliedOutput, current);
  }

  public static int c_Spark_SetIAccum(long handle, float iAccum) {
    Spark spark = spark(handle);
    if (spark != null) {
      spark.loop.setIAccum(iAccum);
    }
    return 0;
  }

  public static float c_Spark_GetIAccum(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : (float) spark.loop.iAccum();
  }

  public static int c_Spark_GetSelectedSlot(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : (int) spark.closedLoopSlot.get();
  }

  public static float c_Spark_GetMaxMotionSetpointPosition(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : (float) spark.loop.profilePosition();
  }

  public static float c_Spark_GetMaxMotionSetpointVelocity(long handle) {
    Spark spark = spark(handle);
    return spark == null ? 0 : (float) spark.loop.profileVelocity();
  }

  public static boolean c_Spark_IsAtSetpoint(long handle) {
    Spark spark = spark(handle);
    if (spark == null) {
      return false;
    }
    int mode = (int) spark.controlMode.get();
    int slot = (int) spark.closedLoopSlot.get();
    double measured;
    if (mode == SparkLoop.POSITION || mode == MAX_MOTION_POSITION) {
      measured = spark.position.get();
    } else if (mode == SparkLoop.VELOCITY || mode == MAX_MOTION_VELOCITY) {
      measured = spark.velocity.get();
    } else {
      return false;
    }
    double allowed = spark.params.getFloat(ALLOWED_ERROR_PARAMETER + slot * 4);
    return Math.abs(spark.setpoint.get() - measured) <= allowed;
  }
}
