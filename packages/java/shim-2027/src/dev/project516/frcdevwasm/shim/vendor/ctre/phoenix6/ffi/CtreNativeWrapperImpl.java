package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.ffi;

import java.lang.foreign.MemorySegment;

public final class CtreNativeWrapperImpl {
  private CtreNativeWrapperImpl() {}

  public static void c_ctre_phoenix6_free_memory(MemorySegment memory) {
    Ffi.free(memory);
  }
}
