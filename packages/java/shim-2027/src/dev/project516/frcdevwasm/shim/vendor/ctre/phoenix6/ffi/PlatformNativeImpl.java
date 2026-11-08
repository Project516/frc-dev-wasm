package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.ffi;

import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.jni.PlatformJNIImpl;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public final class PlatformNativeImpl {
  private PlatformNativeImpl() {}

  public static int c_ctre_phoenix6_platform_sim_create(int deviceType, int id) {
    return PlatformJNIImpl.JNI_SimCreate(deviceType, id);
  }

  public static int c_ctre_phoenix6_platform_sim_set_physics_input(
      int deviceType, int id, MemorySegment name, double value) {
    return PlatformJNIImpl.JNI_SimSetPhysicsInput(deviceType, id, Ffi.string(name), value);
  }

  public static int c_ctre_phoenix6_platform_sim_get_physics_value(
      int deviceType, int id, MemorySegment name, MemorySegment out) {
    out.set(ValueLayout.JAVA_DOUBLE, 0, PlatformJNIImpl.JNI_SimGetPhysicsValue(deviceType, id, Ffi.string(name)));
    return 0;
  }
}
