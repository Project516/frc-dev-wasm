package dev.project516.frcdevwasm.sim;

/** Simulation data of one analog trigger (hal/src/main/native/sim/mockdata/AnalogTriggerData.cpp). */
public final class AnalogTriggerData {
  /** HALSIM_AnalogTriggerMode values. */
  public static final int UNASSIGNED = 0;

  public static final int FILTERED = 1;
  public static final int DUTY_CYCLE = 2;
  public static final int AVERAGED = 3;

  private static final AnalogTriggerData[] ALL = new AnalogTriggerData[Ports.ANALOG_TRIGGERS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new AnalogTriggerData();
    }
  }

  public final BoolValue initialized = new BoolValue("Initialized");
  public final DoubleValue triggerLowerBound = new DoubleValue("TriggerLowerBound");
  public final DoubleValue triggerUpperBound = new DoubleValue("TriggerUpperBound");
  public final IntValue triggerMode = IntValue.asEnum("TriggerMode", UNASSIGNED);
  public volatile int inputPort;

  private AnalogTriggerData() {}

  public static AnalogTriggerData get(int index) {
    return ALL[index];
  }

  /** HALSIM_FindAnalogTriggerForChannel. */
  public static int findForChannel(int channel) {
    for (int i = 0; i < ALL.length; i++) {
      if (ALL[i].initialized.get() && ALL[i].inputPort == channel) {
        return i;
      }
    }
    return -1;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetAnalogTriggerData. */
  public void reset() {
    initialized.reset(false);
    triggerLowerBound.reset(0);
    triggerUpperBound.reset(0);
    triggerMode.reset(UNASSIGNED);
  }
}
