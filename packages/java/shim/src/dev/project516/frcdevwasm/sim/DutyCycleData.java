package dev.project516.frcdevwasm.sim;

/** Simulation data of one duty cycle input (hal/src/main/native/sim/mockdata/DutyCycleData.cpp). */
public final class DutyCycleData {
  private static final DutyCycleData[] ALL = new DutyCycleData[Ports.DUTY_CYCLES];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new DutyCycleData();
    }
  }

  public volatile int digitalChannel;
  public final BoolValue initialized = new BoolValue("Initialized");
  public volatile int simDevice;
  public final IntValue frequency = new IntValue("Frequency");
  public final DoubleValue output = new DoubleValue("Output");

  private DutyCycleData() {}

  public static DutyCycleData get(int index) {
    return ALL[index];
  }

  /** HALSIM_FindDutyCycleForChannel. */
  public static int findForChannel(int channel) {
    for (int i = 0; i < ALL.length; i++) {
      if (ALL[i].initialized.get() && ALL[i].digitalChannel == channel) {
        return i;
      }
    }
    return -1;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetDutyCycleData. */
  public void reset() {
    digitalChannel = 0;
    initialized.reset(false);
    simDevice = 0;
    frequency.reset(0);
    output.reset(0);
  }
}
