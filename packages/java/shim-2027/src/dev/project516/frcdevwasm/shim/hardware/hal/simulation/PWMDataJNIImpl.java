package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.PwmData;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM data access (PWMDataJNI.cpp and mockdata). */
public final class PWMDataJNIImpl {
  private PWMDataJNIImpl() {}

  private static PwmData d(int index) {
    Sim.ensureInitialized();
    return PwmData.get(index);
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

  public static int registerPulseMicrosecondCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).pulseMicrosecond.register(callback, initialNotify);
  }

  public static void cancelPulseMicrosecondCallback(int index, int uid) {
    d(index).pulseMicrosecond.cancel(uid);
  }

  public static int getPulseMicrosecond(int index) {
    return d(index).pulseMicrosecond.get();
  }

  public static void setPulseMicrosecond(int index, int value) {
    d(index).pulseMicrosecond.set(value);
  }

  public static int registerOutputPeriodCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).outputPeriod.register(callback, initialNotify);
  }

  public static void cancelOutputPeriodCallback(int index, int uid) {
    d(index).outputPeriod.cancel(uid);
  }

  public static int getOutputPeriod(int index) {
    return d(index).outputPeriod.get();
  }

  public static void setOutputPeriod(int index, int value) {
    d(index).outputPeriod.set(value);
  }

  public static void resetData(int index) {
    d(index).reset();
  }}
