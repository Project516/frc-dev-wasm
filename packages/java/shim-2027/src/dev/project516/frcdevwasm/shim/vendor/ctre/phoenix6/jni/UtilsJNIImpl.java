package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.jni;

import org.wpilib.hardware.hal.HALUtil;

/** Phoenix 6 time and mode queries. The robot always runs in simulation here. */
public final class UtilsJNIImpl {
  private UtilsJNIImpl() {}

  public static double getCurrentTimeSeconds() {
    return HALUtil.getMonotonicTime() / 1e9;
  }

  public static double getSystemTimeSeconds() {
    return HALUtil.getMonotonicTime() / 1e9;
  }

  public static boolean isSimulation() {
    return true;
  }

  public static boolean isReplay() {
    return false;
  }
}
