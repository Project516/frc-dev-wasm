package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.AddressableLedData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.ConstBufferCallback;
import edu.wpi.first.hal.simulation.NotifyCallback;

/** HALSIM addressable LED data access (AddressableLEDDataJNI.cpp, mockdata/AddressableLEDData.cpp). */
public final class AddressableLEDDataJNIImpl {
  private AddressableLEDDataJNIImpl() {}

  private static AddressableLedData d(int index) {
    Sim.ensureInitialized();
    return AddressableLedData.get(index);
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

  public static int registerOutputPortCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).outputPort.register(callback, initialNotify);
  }

  public static void cancelOutputPortCallback(int index, int uid) {
    d(index).outputPort.cancel(uid);
  }

  public static int getOutputPort(int index) {
    return d(index).outputPort.get();
  }

  public static void setOutputPort(int index, int value) {
    d(index).outputPort.set(value);
  }

  public static int registerLengthCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).length.register(callback, initialNotify);
  }

  public static void cancelLengthCallback(int index, int uid) {
    d(index).length.cancel(uid);
  }

  public static int getLength(int index) {
    return d(index).length.get();
  }

  public static void setLength(int index, int value) {
    d(index).length.set(value);
  }

  public static int registerRunningCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).running.register(callback, initialNotify);
  }

  public static void cancelRunningCallback(int index, int uid) {
    d(index).running.cancel(uid);
  }

  public static boolean getRunning(int index) {
    return d(index).running.get();
  }

  public static void setRunning(int index, boolean value) {
    d(index).running.set(value);
  }

  public static int registerDataCallback(int index, ConstBufferCallback callback) {
    return d(index).registerDataCallback(callback);
  }

  public static void cancelDataCallback(int index, int uid) {
    d(index).cancelDataCallback(uid);
  }

  public static byte[] getData(int index) {
    return d(index).getData();
  }

  public static void setData(int index, byte[] arr) {
    d(index).setData(arr, arr.length / 4);
  }

  public static void resetData(int index) {
    d(index).reset();
  }

  public static int findForChannel(int channel) {
    Sim.ensureInitialized();
    return AddressableLedData.findForChannel(channel);
  }
}
