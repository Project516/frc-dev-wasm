package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.Notifiers;

public final class NotifierDataJNIImpl {
  private NotifierDataJNIImpl() {}

  public static long getNextTimeout() {
    return Notifiers.nextTimeout();
  }

  public static int getNumNotifiers() {
    return Notifiers.count();
  }
}
