package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.DigitalPwmData;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM digital PWM data access (DigitalPWMDataJNI.cpp and mockdata/DigitalPWMData.cpp). */
public final class DigitalPWMDataJNIImpl {
  private DigitalPWMDataJNIImpl() {}

  private static DigitalPwmData d(int index) {
    Sim.ensureInitialized();
    return DigitalPwmData.get(index);
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

  public static int registerDutyCycleCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).dutyCycle.register(callback, initialNotify);
  }

  public static void cancelDutyCycleCallback(int index, int uid) {
    d(index).dutyCycle.cancel(uid);
  }

  public static double getDutyCycle(int index) {
    return d(index).dutyCycle.get();
  }

  public static void setDutyCycle(int index, double dutyCycle) {
    d(index).dutyCycle.set(dutyCycle);
  }

  public static int registerPinCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).pin.register(callback, initialNotify);
  }

  public static void cancelPinCallback(int index, int uid) {
    d(index).pin.cancel(uid);
  }

  public static int getPin(int index) {
    return d(index).pin.get();
  }

  public static void setPin(int index, int pin) {
    d(index).pin.set(pin);
  }

  public static int findForChannel(int channel) {
    Sim.ensureInitialized();
    return DigitalPwmData.findForChannel(channel);
  }

  public static void resetData(int index) {
    d(index).reset();
  }
}
