package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.hardware.ffi;

import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.ffi.Ffi;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.hardware.jni.HardwareJNIImpl;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public final class HardwareNativeImpl {
  private HardwareNativeImpl() {}

  public static int c_ctre_phoenix6_encode_device(int id, MemorySegment model, MemorySegment network, MemorySegment hash) {
    hash.set(ValueLayout.JAVA_INT, 0, HardwareJNIImpl.getDeviceHash(id, Ffi.string(model), Ffi.string(network)));
    return 0;
  }
}
