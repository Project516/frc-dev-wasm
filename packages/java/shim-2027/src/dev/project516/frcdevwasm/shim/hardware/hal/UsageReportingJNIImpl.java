package dev.project516.frcdevwasm.shim.hardware.hal;

/** Port of the usage reporting of hal/src/main/native/sim/HAL.cpp: the sim reports nothing. */
public final class UsageReportingJNIImpl {
  private UsageReportingJNIImpl() {}

  public static void report(String resource, String data) {}

  public static int publishCanVersion(int busId, int deviceId, String name, String version) {
    return 0;
  }

  public static int publishVersion(String name, String version) {
    return 0;
  }

  public static int publishWpilibVersion(String version) {
    return 0;
  }
}
