package dev.project516.frcdevwasm.sim;

/** Simulation data of one analog gyro (hal/src/main/native/sim/mockdata/AnalogGyroData.cpp). */
public final class AnalogGyroData {
  private static final AnalogGyroData[] ALL = new AnalogGyroData[Ports.ACCUMULATORS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new AnalogGyroData();
    }
  }

  public final DoubleValue angle = new DoubleValue("Angle");
  public final DoubleValue rate = new DoubleValue("Rate");
  public final BoolValue initialized = new BoolValue("Initialized");

  private AnalogGyroData() {}

  public static AnalogGyroData get(int index) {
    return ALL[index];
  }

  /** Resets values and drops callbacks, as HALSIM_ResetAnalogGyroData. */
  public void reset() {
    angle.reset(0);
    rate.reset(0);
    initialized.reset(false);
  }
}
