package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.RelayData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.NotifyCallback;

/** HALSIM relay data access (RelayDataJNI.cpp and mockdata/RelayData.cpp). */
public final class RelayDataJNIImpl {
  private RelayDataJNIImpl() {}

  private static RelayData d(int index) {
    Sim.ensureInitialized();
    return RelayData.get(index);
  }

  public static int registerInitializedForwardCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).initializedForward.register(callback, initialNotify);
  }

  public static void cancelInitializedForwardCallback(int index, int uid) {
    d(index).initializedForward.cancel(uid);
  }

  public static boolean getInitializedForward(int index) {
    return d(index).initializedForward.get();
  }

  public static void setInitializedForward(int index, boolean initializedForward) {
    d(index).initializedForward.set(initializedForward);
  }

  public static int registerInitializedReverseCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).initializedReverse.register(callback, initialNotify);
  }

  public static void cancelInitializedReverseCallback(int index, int uid) {
    d(index).initializedReverse.cancel(uid);
  }

  public static boolean getInitializedReverse(int index) {
    return d(index).initializedReverse.get();
  }

  public static void setInitializedReverse(int index, boolean initializedReverse) {
    d(index).initializedReverse.set(initializedReverse);
  }

  public static int registerForwardCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).forward.register(callback, initialNotify);
  }

  public static void cancelForwardCallback(int index, int uid) {
    d(index).forward.cancel(uid);
  }

  public static boolean getForward(int index) {
    return d(index).forward.get();
  }

  public static void setForward(int index, boolean forward) {
    d(index).forward.set(forward);
  }

  public static int registerReverseCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).reverse.register(callback, initialNotify);
  }

  public static void cancelReverseCallback(int index, int uid) {
    d(index).reverse.cancel(uid);
  }

  public static boolean getReverse(int index) {
    return d(index).reverse.get();
  }

  public static void setReverse(int index, boolean reverse) {
    d(index).reverse.set(reverse);
  }


  public static void resetData(int index) {
    d(index).reset();
  }
}
