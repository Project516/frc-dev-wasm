package dev.project516.frcdevwasm.shim.util;

import org.wpilib.util.AlertDataJNI.AlertInfo;

/** Simulation view of the alert store. */
public final class AlertDataJNIImpl {
  private AlertDataJNIImpl() {}

  public static int getNumAlerts() {
    return Alerts.all().length;
  }

  public static AlertInfo[] getAlerts() {
    return Alerts.all();
  }

  public static void resetData() {
    Alerts.reset();
  }
}
