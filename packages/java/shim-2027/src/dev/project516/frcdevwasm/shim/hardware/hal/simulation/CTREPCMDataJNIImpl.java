package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.CTREPCMData;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM CTRE PCM data access (CTREPCMDataJNI.cpp and mockdata/CTREPCMData.cpp). */
public final class CTREPCMDataJNIImpl {
  private CTREPCMDataJNIImpl() {}

  private static CTREPCMData d(int index) {
    Sim.ensureInitialized();
    return CTREPCMData.get(index);
  }

  public static int registerInitializedCallback(int index, NotifyCallback callback, boolean initialNotify) {
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

  public static int registerSolenoidOutputCallback(
      int index, int channel, NotifyCallback callback, boolean initialNotify) {
    return d(index).solenoidOutput[channel].register(callback, initialNotify);
  }

  public static void cancelSolenoidOutputCallback(int index, int channel, int uid) {
    d(index).solenoidOutput[channel].cancel(uid);
  }

  public static boolean getSolenoidOutput(int index, int channel) {
    return d(index).solenoidOutput[channel].get();
  }

  public static void setSolenoidOutput(int index, int channel, boolean value) {
    d(index).solenoidOutput[channel].set(value);
  }

  public static int registerCompressorOnCallback(int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).compressorOn.register(callback, initialNotify);
  }

  public static void cancelCompressorOnCallback(int index, int uid) {
    d(index).compressorOn.cancel(uid);
  }

  public static boolean getCompressorOn(int index) {
    return d(index).compressorOn.get();
  }

  public static void setCompressorOn(int index, boolean value) {
    d(index).compressorOn.set(value);
  }

  public static int registerClosedLoopEnabledCallback(int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).closedLoopEnabled.register(callback, initialNotify);
  }

  public static void cancelClosedLoopEnabledCallback(int index, int uid) {
    d(index).closedLoopEnabled.cancel(uid);
  }

  public static boolean getClosedLoopEnabled(int index) {
    return d(index).closedLoopEnabled.get();
  }

  public static void setClosedLoopEnabled(int index, boolean value) {
    d(index).closedLoopEnabled.set(value);
  }

  public static int registerPressureSwitchCallback(int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).pressureSwitch.register(callback, initialNotify);
  }

  public static void cancelPressureSwitchCallback(int index, int uid) {
    d(index).pressureSwitch.cancel(uid);
  }

  public static boolean getPressureSwitch(int index) {
    return d(index).pressureSwitch.get();
  }

  public static void setPressureSwitch(int index, boolean value) {
    d(index).pressureSwitch.set(value);
  }

  public static int registerCompressorCurrentCallback(int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).compressorCurrent.register(callback, initialNotify);
  }

  public static void cancelCompressorCurrentCallback(int index, int uid) {
    d(index).compressorCurrent.cancel(uid);
  }

  public static double getCompressorCurrent(int index) {
    return d(index).compressorCurrent.get();
  }

  public static void setCompressorCurrent(int index, double value) {
    d(index).compressorCurrent.set(value);
  }

  public static void registerAllNonSolenoidCallbacks(
      int index, NotifyCallback callback, boolean initialNotify) {
    CTREPCMData d = d(index);
    d.initialized.register(callback, initialNotify);
    d.compressorOn.register(callback, initialNotify);
    d.closedLoopEnabled.register(callback, initialNotify);
    d.pressureSwitch.register(callback, initialNotify);
    d.compressorCurrent.register(callback, initialNotify);
  }

  public static void registerAllSolenoidCallbacks(
      int index, int channel, NotifyCallback callback, boolean initialNotify) {
    d(index).solenoidOutput[channel].register(callback, initialNotify);
  }

  public static void resetData(int index) {
    d(index).reset();
  }
}
