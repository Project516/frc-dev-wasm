package dev.project516.frcdevwasm.sim;

/** Simulation data of one SPI accelerometer (mockdata/SPIAccelerometerData.cpp). */
public final class SPIAccelerometerData {
  private static final SPIAccelerometerData[] ALL =
      new SPIAccelerometerData[Ports.SPI_ACCELEROMETERS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new SPIAccelerometerData();
    }
  }

  public final BoolValue active = new BoolValue("Active");
  public final IntValue range = new IntValue("Range");
  public final DoubleValue x = new DoubleValue("X");
  public final DoubleValue y = new DoubleValue("Y");
  public final DoubleValue z = new DoubleValue("Z");

  private SPIAccelerometerData() {}

  public static SPIAccelerometerData get(int index) {
    return ALL[index];
  }

  /** Resets values and drops callbacks, as HALSIM_ResetSPIAccelerometerData. */
  public void reset() {
    active.reset(false);
    range.reset(0);
    x.reset(0);
    y.reset(0);
    z.reset(0);
  }
}
