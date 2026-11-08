package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.AddressableLedData;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.simulation.ConstBufferCallback;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM data access (AddressableLEDDataJNI.cpp and mockdata). */
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

  public static int registerStartCallback(
      int index, NotifyCallback callback, boolean initialNotify) {
    return d(index).start.register(callback, initialNotify);
  }

  public static void cancelStartCallback(int index, int uid) {
    d(index).start.cancel(uid);
  }

  public static int getStart(int index) {
    return d(index).start.get();
  }

  public static void setStart(int index, int value) {
    d(index).start.set(value);
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

  public static int registerDataCallback(ConstBufferCallback callback) {
    return AddressableLedData.registerDataCallback(callback);
  }

  public static void cancelDataCallback(int uid) {
    AddressableLedData.cancelDataCallback(uid);
  }

  public static byte[] getData(int start, int length) {
    return AddressableLedData.getData(start, length);
  }

  public static void setData(int start, byte[] data) {
    AddressableLedData.setData(start, data.length / 3, data);
  }

  public static void resetData(int index) {
    d(index).reset();
  }}
