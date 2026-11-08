package dev.project516.frcdevwasm.shim.hal;

/** Port of hal/src/main/native/sim/Constants.cpp. */
public final class ConstantsJNIImpl {
  private ConstantsJNIImpl() {}

  public static int getSystemClockTicksPerMicrosecond() {
    return 40;
  }
}
