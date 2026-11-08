package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.EncoderData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.NotifyCallback;

/** HALSIM data access (EncoderDataJNI.cpp and mockdata/EncoderData.cpp). */
public final class EncoderDataJNIImpl {
  private EncoderDataJNIImpl() {}

  private static EncoderData d(int index) {
    Sim.ensureInitialized();
    return EncoderData.get(index);
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

  public static int registerCountCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).count.register(callback, initialNotify);
  }

  public static void cancelCountCallback(int index, int uid) {
    d(index).count.cancel(uid);
  }

  public static int getCount(int index) {
    return d(index).count.get();
  }

  public static void setCount(int index, int value) {
    d(index).setCount(value);
  }

  public static int registerPeriodCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).period.register(callback, initialNotify);
  }

  public static void cancelPeriodCallback(int index, int uid) {
    d(index).period.cancel(uid);
  }

  public static double getPeriod(int index) {
    return d(index).period.get();
  }

  public static void setPeriod(int index, double value) {
    d(index).period.set(value);
  }

  public static int registerResetCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).reset.register(callback, initialNotify);
  }

  public static void cancelResetCallback(int index, int uid) {
    d(index).reset.cancel(uid);
  }

  public static boolean getReset(int index) {
    return d(index).reset.get();
  }

  public static void setReset(int index, boolean value) {
    d(index).reset.set(value);
  }

  public static int registerMaxPeriodCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).maxPeriod.register(callback, initialNotify);
  }

  public static void cancelMaxPeriodCallback(int index, int uid) {
    d(index).maxPeriod.cancel(uid);
  }

  public static double getMaxPeriod(int index) {
    return d(index).maxPeriod.get();
  }

  public static void setMaxPeriod(int index, double value) {
    d(index).maxPeriod.set(value);
  }

  public static int registerDirectionCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).direction.register(callback, initialNotify);
  }

  public static void cancelDirectionCallback(int index, int uid) {
    d(index).direction.cancel(uid);
  }

  public static boolean getDirection(int index) {
    return d(index).direction.get();
  }

  public static void setDirection(int index, boolean value) {
    d(index).direction.set(value);
  }

  public static int registerReverseDirectionCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).reverseDirection.register(callback, initialNotify);
  }

  public static void cancelReverseDirectionCallback(int index, int uid) {
    d(index).reverseDirection.cancel(uid);
  }

  public static boolean getReverseDirection(int index) {
    return d(index).reverseDirection.get();
  }

  public static void setReverseDirection(int index, boolean value) {
    d(index).reverseDirection.set(value);
  }

  public static int registerSamplesToAverageCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).samplesToAverage.register(callback, initialNotify);
  }

  public static void cancelSamplesToAverageCallback(int index, int uid) {
    d(index).samplesToAverage.cancel(uid);
  }

  public static int getSamplesToAverage(int index) {
    return d(index).samplesToAverage.get();
  }

  public static void setSamplesToAverage(int index, int value) {
    d(index).samplesToAverage.set(value);
  }

  public static int registerDistancePerPulseCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).distancePerPulse.register(callback, initialNotify);
  }

  public static void cancelDistancePerPulseCallback(int index, int uid) {
    d(index).distancePerPulse.cancel(uid);
  }

  public static double getDistancePerPulse(int index) {
    return d(index).distancePerPulse.get();
  }

  public static void setDistancePerPulse(int index, double value) {
    d(index).distancePerPulse.set(value);
  }

  public static void setDistance(int index, double distance) {
    d(index).setDistance(distance);
  }

  public static double getDistance(int index) {
    return d(index).getDistance();
  }

  public static void setRate(int index, double rate) {
    d(index).setRate(rate);
  }

  public static double getRate(int index) {
    return d(index).getRate();
  }

  public static int findForChannel(int channel) {
    Sim.ensureInitialized();
    return EncoderData.findForChannel(channel);
  }

  public static void resetData(int index) {
    d(index).reset();
  }
}
