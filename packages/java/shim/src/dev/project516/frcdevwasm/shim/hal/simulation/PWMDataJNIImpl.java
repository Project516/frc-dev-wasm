package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.PwmData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.NotifyCallback;

/** HALSIM PWM data access (PWMDataJNI.cpp and mockdata/PWMData.cpp). */
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

  public static void setInitialized(int index, boolean initialized) {
    d(index).initialized.set(initialized);
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

  public static void setPulseMicrosecond(int index, int microsecondPulseTime) {
    d(index).pulseMicrosecond.set(microsecondPulseTime);
  }

  public static int registerSpeedCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).speed.register(callback, initialNotify);
  }

  public static void cancelSpeedCallback(int index, int uid) {
    d(index).speed.cancel(uid);
  }

  public static double getSpeed(int index) {
    return d(index).speed.get();
  }

  public static void setSpeed(int index, double speed) {
    d(index).speed.set(speed);
  }

  public static int registerPositionCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).position.register(callback, initialNotify);
  }

  public static void cancelPositionCallback(int index, int uid) {
    d(index).position.cancel(uid);
  }

  public static double getPosition(int index) {
    return d(index).position.get();
  }

  public static void setPosition(int index, double position) {
    d(index).position.set(position);
  }

  public static int registerPeriodScaleCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).periodScale.register(callback, initialNotify);
  }

  public static void cancelPeriodScaleCallback(int index, int uid) {
    d(index).periodScale.cancel(uid);
  }

  public static int getPeriodScale(int index) {
    return d(index).periodScale.get();
  }

  public static void setPeriodScale(int index, int periodScale) {
    d(index).periodScale.set(periodScale);
  }

  public static int registerZeroLatchCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).zeroLatch.register(callback, initialNotify);
  }

  public static void cancelZeroLatchCallback(int index, int uid) {
    d(index).zeroLatch.cancel(uid);
  }

  public static boolean getZeroLatch(int index) {
    return d(index).zeroLatch.get();
  }

  public static void setZeroLatch(int index, boolean zeroLatch) {
    d(index).zeroLatch.set(zeroLatch);
  }

  public static void resetData(int index) {
    d(index).reset();
  }
}
