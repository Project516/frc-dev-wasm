package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.AnalogOutData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.NotifyCallback;

/** HALSIM data access (AnalogOutDataJNI.cpp and mockdata/AnalogOutData.cpp). */
public final class AnalogOutDataJNIImpl {
  private AnalogOutDataJNIImpl() {}

  private static AnalogOutData d(int index) {
    Sim.ensureInitialized();
    return AnalogOutData.get(index);
  }

  public static int registerVoltageCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).voltage.register(callback, initialNotify);
  }

  public static void cancelVoltageCallback(int index, int uid) {
    d(index).voltage.cancel(uid);
  }

  public static double getVoltage(int index) {
    return d(index).voltage.get();
  }

  public static void setVoltage(int index, double value) {
    d(index).voltage.set(value);
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

  public static void resetData(int index) {
    d(index).reset();
  }
}
