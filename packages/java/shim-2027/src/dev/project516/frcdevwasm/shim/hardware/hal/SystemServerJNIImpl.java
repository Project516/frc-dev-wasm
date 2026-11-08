package dev.project516.frcdevwasm.shim.hardware.hal;

/** Port of hal/src/main/native/sim/SystemServer.cpp: the sim has no system server. */
public final class SystemServerJNIImpl {
  private SystemServerJNIImpl() {}

  public static int getSystemServerHandle() {
    return 0;
  }
}
