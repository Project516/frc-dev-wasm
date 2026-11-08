package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.AnalogGyroData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.NotifyCallback;

/** HALSIM data access (AnalogGyroDataJNI.cpp and mockdata/AnalogGyroData.cpp). */
public final class AnalogGyroDataJNIImpl {
  private AnalogGyroDataJNIImpl() {}

  private static AnalogGyroData d(int index) {
    Sim.ensureInitialized();
    return AnalogGyroData.get(index);
  }

  public static int registerAngleCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).angle.register(callback, initialNotify);
  }

  public static void cancelAngleCallback(int index, int uid) {
    d(index).angle.cancel(uid);
  }

  public static double getAngle(int index) {
    return d(index).angle.get();
  }

  public static void setAngle(int index, double value) {
    d(index).angle.set(value);
  }

  public static int registerRateCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).rate.register(callback, initialNotify);
  }

  public static void cancelRateCallback(int index, int uid) {
    d(index).rate.cancel(uid);
  }

  public static double getRate(int index) {
    return d(index).rate.get();
  }

  public static void setRate(int index, double value) {
    d(index).rate.set(value);
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
