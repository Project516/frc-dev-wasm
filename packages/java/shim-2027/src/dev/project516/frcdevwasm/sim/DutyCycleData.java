package dev.project516.frcdevwasm.sim;

/** Simulation data of one duty cycle input (hal/src/main/native/sim/mockdata/DutyCycleData.cpp). */
public final class DutyCycleData {
  private static final DutyCycleData[] ALL = new DutyCycleData[Ports.DUTY_CYCLES];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new DutyCycleData();
    }
  }

  public final BoolValue initialized = new BoolValue("Initialized");
  public volatile int simDevice;
  public final DoubleValue frequency = new DoubleValue("Frequency");
  public final DoubleValue output = new DoubleValue("Output");

  private DutyCycleData() {}

  public static DutyCycleData get(int index) {
    return ALL[index];
  }

  /** Resets values and drops callbacks, as HALSIM_ResetDutyCycleData. */
  public void reset() {
    initialized.reset(false);
    simDevice = 0;
    frequency.reset(0);
    output.reset(0);
  }
}
