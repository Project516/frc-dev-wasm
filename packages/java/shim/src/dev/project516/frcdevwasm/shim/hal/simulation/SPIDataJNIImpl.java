package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.SPIData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.BufferCallback;
import edu.wpi.first.hal.simulation.ConstBufferCallback;
import edu.wpi.first.hal.simulation.NotifyCallback;
import edu.wpi.first.hal.simulation.SpiReadAutoReceiveBufferCallback;

/** HALSIM SPI data access (SPIDataJNI.cpp and mockdata/SPIData.cpp). */
public final class SPIDataJNIImpl {
  private SPIDataJNIImpl() {}

  private static SPIData d(int index) {
    Sim.ensureInitialized();
    return SPIData.get(index);
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

  public static int registerReadCallback(int index, BufferCallback callback) {
    return d(index).read.register(callback);
  }

  public static void cancelReadCallback(int index, int uid) {
    d(index).read.cancel(uid);
  }

  public static int registerWriteCallback(int index, ConstBufferCallback callback) {
    return d(index).write.register(callback);
  }

  public static void cancelWriteCallback(int index, int uid) {
    d(index).write.cancel(uid);
  }

  public static int registerReadAutoReceiveBufferCallback(
      int index, SpiReadAutoReceiveBufferCallback callback) {
    return d(index).autoReceivedData.register(callback);
  }

  public static void cancelReadAutoReceiveBufferCallback(int index, int uid) {
    d(index).autoReceivedData.cancel(uid);
  }

  public static void resetData(int index) {
    d(index).reset();
  }
}
