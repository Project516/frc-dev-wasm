package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.PowerDistributionData;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM power distribution data access (PowerDistributionDataJNI.cpp and mockdata/PowerDistributionData.cpp). */
public final class PowerDistributionDataJNIImpl {
  private PowerDistributionDataJNIImpl() {}

  private static PowerDistributionData d(int index) {
    Sim.ensureInitialized();
    return PowerDistributionData.get(index);
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

  public static int registerTemperatureCallback(int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).temperature.register(callback, initialNotify);
  }

  public static void cancelTemperatureCallback(int index, int uid) {
    d(index).temperature.cancel(uid);
  }

  public static double getTemperature(int index) {
    return d(index).temperature.get();
  }

  public static void setTemperature(int index, double value) {
    d(index).temperature.set(value);
  }

  public static int registerVoltageCallback(int index, NotifyCallback callback, boolean initialNotify) {
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

  public static int registerCurrentCallback(
      int index, int channel, NotifyCallback callback, boolean initialNotify) {
    return d(index).current[channel].register(callback, initialNotify);
  }

  public static void cancelCurrentCallback(int index, int channel, int uid) {
    d(index).current[channel].cancel(uid);
  }

  public static double getCurrent(int index, int channel) {
    return d(index).current[channel].get();
  }

  public static void setCurrent(int index, int channel, double value) {
    d(index).current[channel].set(value);
  }

  public static void resetData(int index) {
    d(index).reset();
  }
}
