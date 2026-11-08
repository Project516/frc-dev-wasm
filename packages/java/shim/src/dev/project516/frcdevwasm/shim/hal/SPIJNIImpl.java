package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.Buffers;
import dev.project516.frcdevwasm.sim.SPIData;
import dev.project516.frcdevwasm.sim.Sim;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Port of sim/SPI.cpp and SPIJNI.cpp. Everything except the data callbacks is a no-op. */
public final class SPIJNIImpl {
  private SPIJNIImpl() {}

  public static void spiInitialize(int port) {
    Sim.ensureInitialized();
    SPIData.get(port).initialized.set(true);
  }

  public static int spiTransaction(
      int port, ByteBuffer dataToSend, ByteBuffer dataReceived, int size) {
    byte[] recv = Buffers.bytes(dataReceived, size);
    int ret = SPIData.get(port).transaction(Buffers.bytes(dataToSend, size), recv, size);
    Buffers.putBytes(dataReceived, recv);
    return ret;
  }

  public static int spiTransactionB(int port, byte[] dataToSend, byte[] dataReceived, int size) {
    if (size < 0) {
      throw new IllegalArgumentException("SPIJNI.spiTransactionB() size < 0");
    }
    byte[] recv = new byte[size];
    int ret = SPIData.get(port).transaction(Arrays.copyOf(dataToSend, size), recv, size);
    System.arraycopy(recv, 0, dataReceived, 0, size);
    return ret;
  }

  public static int spiWrite(int port, ByteBuffer dataToSend, int size) {
    return SPIData.get(port).write(Buffers.bytes(dataToSend, size), size);
  }

  public static int spiWriteB(int port, byte[] dataToSend, int size) {
    return SPIData.get(port).write(Arrays.copyOf(dataToSend, size), size);
  }

  public static int spiRead(int port, boolean initiate, ByteBuffer dataReceived, int size) {
    if (size < 0) {
      throw new IllegalArgumentException("SPIJNI.spiRead() size < 0");
    }
    byte[] recv = Buffers.bytes(dataReceived, size);
    int ret = read(port, initiate, recv, size);
    Buffers.putBytes(dataReceived, recv);
    return ret;
  }

  public static int spiReadB(int port, boolean initiate, byte[] dataReceived, int size) {
    if (size < 0) {
      throw new IllegalArgumentException("SPIJNI.spiReadB() size < 0");
    }
    byte[] recv = new byte[size];
    int ret = read(port, initiate, recv, size);
    System.arraycopy(recv, 0, dataReceived, 0, size);
    return ret;
  }

  private static int read(int port, boolean initiate, byte[] recv, int size) {
    SPIData data = SPIData.get(port);
    return initiate ? data.transaction(new byte[size], recv, size) : data.read(recv, size);
  }

  public static void spiClose(int port) {
    SPIData.get(port).initialized.set(false);
  }

  public static void spiSetSpeed(int port, int speed) {}

  public static void spiSetMode(int port, int mode) {}

  public static int spiGetMode(int port) {
    return 0;
  }

  public static void spiSetChipSelectActiveHigh(int port) {}

  public static void spiSetChipSelectActiveLow(int port) {}

  public static void spiInitAuto(int port, int bufferSize) {}

  public static void spiFreeAuto(int port) {}

  public static void spiStartAutoRate(int port, double period) {}

  public static void spiStartAutoTrigger(
      int port,
      int digitalSourceHandle,
      int analogTriggerType,
      boolean triggerRising,
      boolean triggerFalling) {}

  public static void spiStopAuto(int port) {}

  public static void spiSetAutoTransmitData(int port, byte[] dataToSend, int zeroSize) {}

  public static void spiForceAutoRead(int port) {}

  public static int spiReadAutoReceivedData(
      int port, ByteBuffer buffer, int numToRead, double timeout) {
    int[] recv = Buffers.ints(buffer, numToRead);
    int ret = SPIData.get(port).readAutoReceivedData(recv, numToRead);
    Buffers.putInts(buffer, recv, recv.length);
    return ret;
  }

  public static int spiReadAutoReceivedData(int port, int[] buffer, int numToRead, double timeout) {
    if (numToRead < 0) {
      throw new IllegalArgumentException("SPIJNI.spiReadAutoReceivedData() numToRead < 0");
    }
    int[] recv = new int[numToRead];
    int ret = SPIData.get(port).readAutoReceivedData(recv, numToRead);
    System.arraycopy(recv, 0, buffer, 0, numToRead);
    return ret;
  }

  public static int spiGetAutoDroppedCount(int port) {
    return 0;
  }

  public static void spiConfigureAutoStall(
      int port, int csToSclkTicks, int stallTicks, int pow2BytesPerRead) {}
}
