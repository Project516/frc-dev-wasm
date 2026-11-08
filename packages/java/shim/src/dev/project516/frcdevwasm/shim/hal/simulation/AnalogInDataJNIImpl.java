package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.AnalogInData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.NotifyCallback;

/** HALSIM data access (AnalogInDataJNI.cpp and mockdata/AnalogInData.cpp). */
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

  public static int registerAverageBitsCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).averageBits.register(callback, initialNotify);
  }

  public static void cancelAverageBitsCallback(int index, int uid) {
    d(index).averageBits.cancel(uid);
  }

  public static int getAverageBits(int index) {
    return d(index).averageBits.get();
  }

  public static void setAverageBits(int index, int value) {
    d(index).averageBits.set(value);
  }

  public static int registerOversampleBitsCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).oversampleBits.register(callback, initialNotify);
  }

  public static void cancelOversampleBitsCallback(int index, int uid) {
    d(index).oversampleBits.cancel(uid);
  }

  public static int getOversampleBits(int index) {
    return d(index).oversampleBits.get();
  }

  public static void setOversampleBits(int index, int value) {
    d(index).oversampleBits.set(value);
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

  public static int registerAccumulatorInitializedCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).accumulatorInitialized.register(callback, initialNotify);
  }

  public static void cancelAccumulatorInitializedCallback(int index, int uid) {
    d(index).accumulatorInitialized.cancel(uid);
  }

  public static boolean getAccumulatorInitialized(int index) {
    return d(index).accumulatorInitialized.get();
  }

  public static void setAccumulatorInitialized(int index, boolean value) {
    d(index).accumulatorInitialized.set(value);
  }

  public static int registerAccumulatorValueCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).accumulatorValue.register(callback, initialNotify);
  }

  public static void cancelAccumulatorValueCallback(int index, int uid) {
    d(index).accumulatorValue.cancel(uid);
  }

  public static long getAccumulatorValue(int index) {
    return d(index).accumulatorValue.get();
  }

  public static void setAccumulatorValue(int index, long value) {
    d(index).accumulatorValue.set(value);
  }

  public static int registerAccumulatorCountCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).accumulatorCount.register(callback, initialNotify);
  }

  public static void cancelAccumulatorCountCallback(int index, int uid) {
    d(index).accumulatorCount.cancel(uid);
  }

  public static long getAccumulatorCount(int index) {
    return d(index).accumulatorCount.get();
  }

  public static void setAccumulatorCount(int index, long value) {
    d(index).accumulatorCount.set(value);
  }

  public static int registerAccumulatorCenterCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).accumulatorCenter.register(callback, initialNotify);
  }

  public static void cancelAccumulatorCenterCallback(int index, int uid) {
    d(index).accumulatorCenter.cancel(uid);
  }

  public static int getAccumulatorCenter(int index) {
    return d(index).accumulatorCenter.get();
  }

  public static void setAccumulatorCenter(int index, int value) {
    d(index).accumulatorCenter.set(value);
  }

  public static int registerAccumulatorDeadbandCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).accumulatorDeadband.register(callback, initialNotify);
  }

  public static void cancelAccumulatorDeadbandCallback(int index, int uid) {
    d(index).accumulatorDeadband.cancel(uid);
  }

  public static int getAccumulatorDeadband(int index) {
    return d(index).accumulatorDeadband.get();
  }

  public static void setAccumulatorDeadband(int index, int value) {
    d(index).accumulatorDeadband.set(value);
  }

  public static void resetData(int index) {
    d(index).reset();
  }
}
