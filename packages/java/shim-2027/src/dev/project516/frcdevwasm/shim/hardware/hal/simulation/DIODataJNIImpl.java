package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.DioData;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM DIO data access (DIODataJNI.cpp and mockdata/DIOData.cpp). */
public final class DIODataJNIImpl {
  private DIODataJNIImpl() {}

  private static DioData d(int index) {
    Sim.ensureInitialized();
    return DioData.get(index);
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

  public static void setInitialized(int index, boolean initialized) {
    d(index).initialized.set(initialized);
  }

  public static int registerValueCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).value.register(callback, initialNotify);
  }

  public static void cancelValueCallback(int index, int uid) {
    d(index).value.cancel(uid);
  }

  public static boolean getValue(int index) {
    return d(index).value.get();
  }

  public static void setValue(int index, boolean value) {
    d(index).value.set(value);
  }

  public static int registerPulseLengthCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).pulseLength.register(callback, initialNotify);
  }

  public static void cancelPulseLengthCallback(int index, int uid) {
    d(index).pulseLength.cancel(uid);
  }

  public static double getPulseLength(int index) {
    return d(index).pulseLength.get();
  }

  public static void setPulseLength(int index, double pulseLength) {
    d(index).pulseLength.set(pulseLength);
  }

  public static int registerIsInputCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).isInput.register(callback, initialNotify);
  }

  public static void cancelIsInputCallback(int index, int uid) {
    d(index).isInput.cancel(uid);
  }

  public static boolean getIsInput(int index) {
    return d(index).isInput.get();
  }

  public static void setIsInput(int index, boolean isInput) {
    d(index).isInput.set(isInput);
  }

  public static int registerFilterIndexCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).filterIndex.register(callback, initialNotify);
  }

  public static void cancelFilterIndexCallback(int index, int uid) {
    d(index).filterIndex.cancel(uid);
  }

  public static int getFilterIndex(int index) {
    return d(index).filterIndex.get();
  }

  public static void setFilterIndex(int index, int filterIndex) {
    d(index).filterIndex.set(filterIndex);
  }


  public static void resetData(int index) {
    d(index).reset();
  }
}
