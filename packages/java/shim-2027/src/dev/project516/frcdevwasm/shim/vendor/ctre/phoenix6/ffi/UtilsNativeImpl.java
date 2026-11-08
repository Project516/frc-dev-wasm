package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.ffi;

import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.jni.UtilsJNIImpl;

public final class UtilsNativeImpl {
  private UtilsNativeImpl() {}

  public static double c_ctre_phoenix6_get_current_time_seconds() {
    return UtilsJNIImpl.getCurrentTimeSeconds();
  }

  public static double c_ctre_phoenix6_get_monotonic_time_seconds() {
    return UtilsJNIImpl.getSystemTimeSeconds();
  }

  public static boolean c_ctre_phoenix6_is_simulation() {
    return UtilsJNIImpl.isSimulation();
  }

  public static boolean c_ctre_phoenix6_is_replay() {
    return UtilsJNIImpl.isReplay();
  }
}
