package dev.project516.frcdevwasm.shim.vendor.revrobotics.jni;

/** Flattened parameters, which {@code c_Spark_Configure} applies. */
public final class CANCommonJNIImpl {
  private CANCommonJNIImpl() {}

  public static String c_REVLib_FlattenParameterInt32(int paramId, int value) {
    return SparkParams.flatten(paramId, value);
  }

  public static String c_REVLib_FlattenParameterUint32(int paramId, int value) {
    return SparkParams.flatten(paramId, value);
  }

  public static String c_REVLib_FlattenParameterFloat(int paramId, float value) {
    return SparkParams.flatten(paramId, value);
  }

  public static String c_REVLib_FlattenParameterBool(int paramId, boolean value) {
    return SparkParams.flatten(paramId, value);
  }
}
