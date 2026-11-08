package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.unmanaged.ffi;

import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.unmanaged.jni.UnmanagedJNIImpl;

public final class UnmanagedNativeImpl {
  private UnmanagedNativeImpl() {}

  public static boolean c_ctre_phoenix6_unmanaged_get_enable_state() {
    return UnmanagedJNIImpl.JNI_GetEnableState();
  }
}
