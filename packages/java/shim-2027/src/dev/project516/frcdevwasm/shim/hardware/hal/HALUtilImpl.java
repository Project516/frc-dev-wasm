package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.shim.hardware.hal.simulation.SimulatorJNIImpl;
import dev.project516.frcdevwasm.sim.RoboRioData;
import dev.project516.frcdevwasm.sim.SimTime;
import dev.project516.frcdevwasm.sim.Status;

/** Port of the HALUtil JNI functions (HALUtil.cpp) and the matching parts of sim/HAL.cpp. */
public final class HALUtilImpl {
  private HALUtilImpl() {}

  public static String getSerialNumber() {
    return RoboRioData.get().getSerialNumber();
  }

  public static String getComments() {
    return RoboRioData.get().getComments();
  }

  public static int getTeamNumber() {
    return RoboRioData.get().teamNumber.get();
  }

  public static long getMonotonicTime() {
    return SimTime.now();
  }

  public static int getHALRuntimeType() {
    return SimulatorJNIImpl.runtimeType();
  }

  public static String getHALErrorMessage(int code) {
    return Status.message(code);
  }

  public static int getHALErrno() {
    return 0;
  }

  public static String getHALstrerror(int errorCode) {
    return "Success";
  }
}
