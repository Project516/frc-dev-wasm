package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.AnalogTriggerData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.NotifyCallback;

/** HALSIM data access (AnalogTriggerDataJNI.cpp and mockdata/AnalogTriggerData.cpp). */
public final class AnalogTriggerDataJNIImpl {
  private AnalogTriggerDataJNIImpl() {}

  private static AnalogTriggerData d(int index) {
    Sim.ensureInitialized();
    return AnalogTriggerData.get(index);
  }

  public static int registerInitializedCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).initialized.register(callback, initialNotify);
  }

  public static void cancelInitializedCallback(int index, int uid) {
    d(index).initialized.cancel(uid);
  }

  public static boolean getInitialized(int index) {
    return d(index).initialized.get();
  }

  public static void setInitialized(int index, boolean value) {
    d(index).initialized.set(value);
  }

  public static int registerTriggerLowerBoundCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).triggerLowerBound.register(callback, initialNotify);
  }

  public static void cancelTriggerLowerBoundCallback(int index, int uid) {
    d(index).triggerLowerBound.cancel(uid);
  }

  public static double getTriggerLowerBound(int index) {
    return d(index).triggerLowerBound.get();
  }

  public static void setTriggerLowerBound(int index, double value) {
    d(index).triggerLowerBound.set(value);
  }

  public static int registerTriggerUpperBoundCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).triggerUpperBound.register(callback, initialNotify);
  }

  public static void cancelTriggerUpperBoundCallback(int index, int uid) {
    d(index).triggerUpperBound.cancel(uid);
  }

  public static double getTriggerUpperBound(int index) {
    return d(index).triggerUpperBound.get();
  }

  public static void setTriggerUpperBound(int index, double value) {
    d(index).triggerUpperBound.set(value);
  }

  public static int findForChannel(int channel) {
    Sim.ensureInitialized();
    return AnalogTriggerData.findForChannel(channel);
  }

  public static void resetData(int index) {
    d(index).reset();
  }
}
