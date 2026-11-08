package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.RoboRioData;
import dev.project516.frcdevwasm.sim.Status;

/** Port of hal/src/main/native/sim/Power.cpp and the PowerJNI wrappers. */
public final class PowerJNIImpl {
  private static final double BROWNOUT_MIN_VOLTS = 5.0;
  private static final double BROWNOUT_MAX_VOLTS = 8.0;
  private static final double RECOVERY_MAX_VOLTS = 8.5;
  private static final long RECOVERY_MIN_DELTA_MILLIVOLTS = 500;

  private PowerJNIImpl() {}

  private static RoboRioData d() {
    return RoboRioData.get();
  }

  public static double getVinVoltage() {
    return d().vInVoltage.get();
  }

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
  }

  public static void setBrownoutVoltages(double brownoutVoltage, double recoveryVoltage) {
    if (!Double.isFinite(brownoutVoltage)
        || !Double.isFinite(recoveryVoltage)
        || brownoutVoltage < BROWNOUT_MIN_VOLTS
        || brownoutVoltage > BROWNOUT_MAX_VOLTS
        || recoveryVoltage < BROWNOUT_MIN_VOLTS
        || recoveryVoltage > RECOVERY_MAX_VOLTS) {
      Status.check(Status.PARAMETER_OUT_OF_RANGE);
      return;
    }
    long brownoutMillivolts = Math.round(brownoutVoltage * 1000.0);
    long recoveryMillivolts = Math.round(recoveryVoltage * 1000.0);
    if (recoveryMillivolts < brownoutMillivolts + RECOVERY_MIN_DELTA_MILLIVOLTS) {
      Status.check(Status.PARAMETER_OUT_OF_RANGE);
      return;
    }
    d().brownoutVoltage.set(brownoutMillivolts / 1000.0);
    d().brownoutRecoveryVoltage.set(recoveryMillivolts / 1000.0);
  }

  public static double getCPUTemp() {
    return d().cpuTemp.get();
  }
}
