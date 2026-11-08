package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.I2CData;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.simulation.BufferCallback;
import org.wpilib.hardware.hal.simulation.ConstBufferCallback;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM I2C data access (I2CDataJNI.cpp and mockdata/I2CData.cpp). */
public final class I2CDataJNIImpl {
  private I2CDataJNIImpl() {}

  private static I2CData d(int index) {
    Sim.ensureInitialized();
    return I2CData.get(index);
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

  public static void resetData(int index) {
    d(index).reset();
  }
}
