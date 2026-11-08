package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.jni;

import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.PhoenixSim;

/** The sim state classes' window into the simulated devices. */
public final class PlatformJNIImpl {
  private PlatformJNIImpl() {}

  public static int JNI_SimCreate(int deviceType, int id) {
    PhoenixSim.device(deviceType, id);
    return 0;
  }

  public static int JNI_SimSetPhysicsInput(int deviceType, int id, String name, double value) {
    PhoenixSim.device(deviceType, id).setInput(name, value);
    return 0;
  }

  public static double JNI_SimGetPhysicsValue(int deviceType, int id, String name) {
    return PhoenixSim.device(deviceType, id).getValue(name);
  }
}
