package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.RoboRioData;

/** Port of hal/src/main/native/sim/Power.cpp and the PowerJNI wrappers. */
public final class PowerJNIImpl {
  private PowerJNIImpl() {}

  private static RoboRioData d() {
    return RoboRioData.get();
  }

  public static double getVinVoltage() {
    return d().vInVoltage.get();
  }

  public static double getVinCurrent() {
    return d().vInCurrent.get();
  }

  public static double getUserVoltage6V() {
    return d().userVoltage6V.get();
  }

  public static double getUserCurrent6V() {
    return d().userCurrent6V.get();
  }

  public static boolean getUserActive6V() {
    return d().userActive6V.get();
  }

  public static int getUserCurrentFaults6V() {
    return d().userFaults6V.get();
  }

  public static void setUserEnabled6V(boolean enabled) {}

  public static double getUserVoltage5V() {
    return d().userVoltage5V.get();
  }

  public static double getUserCurrent5V() {
    return d().userCurrent5V.get();
  }

  public static boolean getUserActive5V() {
    return d().userActive5V.get();
  }

  public static int getUserCurrentFaults5V() {
    return d().userFaults5V.get();
  }

  public static void setUserEnabled5V(boolean enabled) {}

  public static double getUserVoltage3V3() {
    return d().userVoltage3V3.get();
  }

  public static double getUserCurrent3V3() {
    return d().userCurrent3V3.get();
  }

  public static boolean getUserActive3V3() {
    return d().userActive3V3.get();
  }

  public static int getUserCurrentFaults3V3() {
    return d().userFaults3V3.get();
  }

  public static void setUserEnabled3V3(boolean enabled) {}

  public static void resetUserCurrentFaults() {
    d().userFaults3V3.set(0);
    d().userFaults5V.set(0);
    d().userFaults6V.set(0);
  }

  public static void setBrownoutVoltage(double voltage) {
    d().brownoutVoltage.set(voltage);
  }

  public static double getBrownoutVoltage() {
    return d().brownoutVoltage.get();
  }

  public static double getCPUTemp() {
    return d().cpuTemp.get();
  }
}
