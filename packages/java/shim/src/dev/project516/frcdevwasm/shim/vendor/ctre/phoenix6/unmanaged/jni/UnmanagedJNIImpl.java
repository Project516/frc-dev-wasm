package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.unmanaged.jni;

import dev.project516.frcdevwasm.sim.DriverStationData;

public final class UnmanagedJNIImpl {
  private UnmanagedJNIImpl() {}

  public static boolean JNI_GetEnableState() {
    return DriverStationData.get().robotEnabled();
  }
}
