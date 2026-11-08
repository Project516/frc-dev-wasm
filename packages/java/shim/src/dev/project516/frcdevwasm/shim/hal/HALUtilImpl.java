package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.RoboRioData;
import dev.project516.frcdevwasm.sim.SimTime;
import dev.project516.frcdevwasm.sim.Status;

/** Port of the HALUtil JNI functions (HALUtil.cpp) and the matching parts of sim/HAL.cpp. */
public final class HALUtilImpl {
  private static final int RUNTIME_SIMULATION = 2;

  private HALUtilImpl() {}

  public static short getFPGAVersion() {
    return 2018;
  }

  public static int getFPGARevision() {
    return 0;
  }

  public static String getSerialNumber() {
    return RoboRioData.get().getSerialNumber();
  }

  public static String getComments() {
    return RoboRioData.get().getComments();
  }

  public static int getTeamNumber() {
    return RoboRioData.get().teamNumber.get();
  }

  public static long getFPGATime() {
    return SimTime.fpgaTime();
  }

  public static int getHALRuntimeType() {
    return RUNTIME_SIMULATION;
  }

  public static boolean getFPGAButton() {
    return RoboRioData.get().fpgaButton.get();
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
