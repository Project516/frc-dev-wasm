package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.SPIAccelerometerData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.NotifyCallback;

/** HALSIM data access (SPIAccelerometerDataJNI.cpp and mockdata/SPIAccelerometerData.cpp). */
public final class SPIAccelerometerDataJNIImpl {
  private SPIAccelerometerDataJNIImpl() {}

  private static SPIAccelerometerData d(int index) {
    Sim.ensureInitialized();
    return SPIAccelerometerData.get(index);
  }

  public static int registerActiveCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).active.register(callback, initialNotify);
  }

  public static void cancelActiveCallback(int index, int uid) {
    d(index).active.cancel(uid);
  }

  public static boolean getActive(int index) {
    return d(index).active.get();
  }

  public static void setActive(int index, boolean value) {
    d(index).active.set(value);
  }

  public static int registerRangeCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).range.register(callback, initialNotify);
  }

  public static void cancelRangeCallback(int index, int uid) {
    d(index).range.cancel(uid);
  }

  public static int getRange(int index) {
    return d(index).range.get();
  }

  public static void setRange(int index, int value) {
    d(index).range.set(value);
  }

  public static int registerXCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).x.register(callback, initialNotify);
  }

  public static void cancelXCallback(int index, int uid) {
    d(index).x.cancel(uid);
  }

  public static double getX(int index) {
    return d(index).x.get();
  }

  public static void setX(int index, double value) {
    d(index).x.set(value);
  }

  public static int registerYCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).y.register(callback, initialNotify);
  }

  public static void cancelYCallback(int index, int uid) {
    d(index).y.cancel(uid);
  }

  public static double getY(int index) {
    return d(index).y.get();
  }

  public static void setY(int index, double value) {
    d(index).y.set(value);
  }

  public static int registerZCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).z.register(callback, initialNotify);
  }

  public static void cancelZCallback(int index, int uid) {
    d(index).z.cancel(uid);
  }

  public static double getZ(int index) {
    return d(index).z.get();
  }

  public static void setZ(int index, double value) {
    d(index).z.set(value);
  }

  public static void resetData(int index) {
    d(index).reset();
  }
}
