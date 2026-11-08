package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.DutyCycleData;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM data access (DutyCycleDataJNI.cpp and mockdata). */
public final class DutyCycleDataJNIImpl {
  private DutyCycleDataJNIImpl() {}

  private static DutyCycleData d(int index) {
    Sim.ensureInitialized();
    return DutyCycleData.get(index);
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

  public static int registerFrequencyCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).frequency.register(callback, initialNotify);
  }

  public static void cancelFrequencyCallback(int index, int uid) {
    d(index).frequency.cancel(uid);
  }

  public static double getFrequency(int index) {
    return d(index).frequency.get();
  }

  public static void setFrequency(int index, double value) {
    d(index).frequency.set(value);
  }

  public static int registerOutputCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).output.register(callback, initialNotify);
  }

  public static void cancelOutputCallback(int index, int uid) {
    d(index).output.cancel(uid);
  }

  public static double getOutput(int index) {
    return d(index).output.get();
  }

  public static void setOutput(int index, double value) {
    d(index).output.set(value);
  }

  public static void resetData(int index) {
    d(index).reset();
  }}
