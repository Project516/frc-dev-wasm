package dev.project516.frcdevwasm.sim;

import org.wpilib.hardware.hal.simulation.BufferCallback;
import org.wpilib.hardware.hal.simulation.ConstBufferCallback;

/** Simulation data of one I2C port (hal/src/main/native/sim/mockdata/I2CData.cpp). */
public final class I2CData {
  private static final I2CData[] ALL = new I2CData[Ports.I2C_PORTS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new I2CData();
    }
  }

  public final BoolValue initialized = new BoolValue("Initialized");
  public final CallbackRegistry<BufferCallback> read = new CallbackRegistry<>("Read");
  public final CallbackRegistry<ConstBufferCallback> write = new CallbackRegistry<>("Write");

  private I2CData() {}

  public static I2CData get(int index) {
    return ALL[index];
  }

  public static void resetAll() {
    for (I2CData d : ALL) {
      d.reset();
    }
  }

  /** Resets values and drops callbacks, as HALSIM_ResetI2CData. */
  public void reset() {
    initialized.reset(false);
    read.reset();
    write.reset();
  }

  public void write(byte[] data, int size) {
    write.invokeCatching(cb -> cb.callback(write.name, data.clone(), size));
  }

  /** Lets the read callbacks fill buffer, whose length is count. */
  public void read(byte[] buffer, int count) {
    read.invokeCatching(cb -> cb.callback(read.name, buffer, count));
  }
}
