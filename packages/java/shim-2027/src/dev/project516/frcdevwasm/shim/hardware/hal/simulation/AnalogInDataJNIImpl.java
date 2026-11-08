package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.AnalogInData;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM data access (AnalogInDataJNI.cpp and mockdata). */
public final class AnalogInDataJNIImpl {
  private AnalogInDataJNIImpl() {}

  private static AnalogInData d(int index) {
    Sim.ensureInitialized();
    return AnalogInData.get(index);
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

  public static void resetData(int index) {
    d(index).reset();
  }}
