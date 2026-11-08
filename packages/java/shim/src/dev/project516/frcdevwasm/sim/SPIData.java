package dev.project516.frcdevwasm.sim;

import edu.wpi.first.hal.simulation.BufferCallback;
import edu.wpi.first.hal.simulation.ConstBufferCallback;
import edu.wpi.first.hal.simulation.SpiReadAutoReceiveBufferCallback;

/** Simulation data of one SPI port (hal/src/main/native/sim/mockdata/SPIData.cpp). */
public final class SPIData {
  private static final SPIData[] ALL = new SPIData[Ports.SPI_PORTS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new SPIData();
    }
  }

  public final BoolValue initialized = new BoolValue("Initialized");
  public final CallbackRegistry<BufferCallback> read = new CallbackRegistry<>("Read");
  public final CallbackRegistry<ConstBufferCallback> write = new CallbackRegistry<>("Write");
  public final CallbackRegistry<SpiReadAutoReceiveBufferCallback> autoReceivedData =
      new CallbackRegistry<>("AutoReceive");

  private SPIData() {}

  public static SPIData get(int index) {
    return ALL[index];
  }

  public static void resetAll() {
    for (SPIData d : ALL) {
      d.reset();
    }
  }

  /** Resets values and drops callbacks, as HALSIM_ResetSPIData. */
  public void reset() {
    initialized.reset(false);
    read.reset();
    write.reset();
    autoReceivedData.reset();
  }

  /** Lets the read callbacks fill buffer, whose length is count. */
  public int read(byte[] buffer, int count) {
    read.invokeCatching(cb -> cb.callback(read.name, buffer, count));
    return count;
  }

  public int write(byte[] data, int size) {
    write.invokeCatching(cb -> cb.callback(write.name, data.clone(), size));
    return size;
  }

  public int transaction(byte[] dataToSend, byte[] dataReceived, int size) {
    write(dataToSend, size);
    read(dataReceived, size);
    return size;
  }

  /**
   * SPIData::ReadAutoReceivedData: each callback fills buffer and returns the count it produced;
   * the last callback's count wins.
   */
  public int readAutoReceivedData(int[] buffer, int numToRead) {
    int[] out = {0};
    autoReceivedData.invokeCatching(
        cb -> {
          int ret = cb.callback(autoReceivedData.name, buffer, numToRead);
          out[0] = ret;
        });
    return out[0];
  }
}
