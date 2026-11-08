package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.Buffers;
import dev.project516.frcdevwasm.sim.I2CData;
import dev.project516.frcdevwasm.sim.Sim;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Port of sim/I2C.cpp and I2CJNI.cpp. Transfers always report success (0). */
public final class I2CJNIImpl {
  private I2CJNIImpl() {}

  public static void i2CInitialize(int port) {
    Sim.ensureInitialized();
    I2CData.get(port).initialized.set(true);
  }

  public static int i2CTransaction(
      int port,
      byte address,
      ByteBuffer dataToSend,
      byte sendSize,
      ByteBuffer dataReceived,
      byte receiveSize) {
    byte[] recv = Buffers.bytes(dataReceived, receiveSize);
    transaction(port, Buffers.bytes(dataToSend, sendSize), sendSize, recv, receiveSize);
    Buffers.putBytes(dataReceived, recv);
    return 0;
  }

  public static int i2CTransactionB(
      int port,
      byte address,
      byte[] dataToSend,
      byte sendSize,
      byte[] dataReceived,
      byte receiveSize) {
    if (sendSize < 0) {
      throw new IllegalArgumentException("I2CJNI.i2cTransactionB() sendSize < 0");
    }
    if (receiveSize < 0) {
      throw new IllegalArgumentException("I2CJNI.i2cTransactionB() receiveSize < 0");
    }
    byte[] recv = new byte[receiveSize];
    transaction(port, Arrays.copyOf(dataToSend, sendSize), sendSize, recv, receiveSize);
    System.arraycopy(recv, 0, dataReceived, 0, receiveSize);
    return 0;
  }

  private static void transaction(int port, byte[] send, int sendSize, byte[] recv, int recvSize) {
    I2CData data = I2CData.get(port);
    data.write(send, sendSize);
    data.read(recv, recvSize);
  }

  public static int i2CWrite(int port, byte address, ByteBuffer dataToSend, byte sendSize) {
    I2CData.get(port).write(Buffers.bytes(dataToSend, sendSize), sendSize);
    return 0;
  }

  public static int i2CWriteB(int port, byte address, byte[] dataToSend, byte sendSize) {
    I2CData.get(port).write(Arrays.copyOf(dataToSend, Math.max(sendSize, 0)), sendSize);
    return 0;
  }

  public static int i2CRead(int port, byte address, ByteBuffer dataReceived, byte receiveSize) {
    byte[] recv = Buffers.bytes(dataReceived, receiveSize);
    I2CData.get(port).read(recv, receiveSize);
    Buffers.putBytes(dataReceived, recv);
    return 0;
  }

  public static int i2CReadB(int port, byte address, byte[] dataReceived, byte receiveSize) {
    if (receiveSize < 0) {
      throw new IllegalArgumentException("I2CJNI.i2cReadB() receiveSize < 0");
    }
    byte[] recv = new byte[receiveSize];
    I2CData.get(port).read(recv, receiveSize);
    System.arraycopy(recv, 0, dataReceived, 0, receiveSize);
    return 0;
  }

  public static void i2CClose(int port) {
    I2CData.get(port).initialized.set(false);
  }
}
