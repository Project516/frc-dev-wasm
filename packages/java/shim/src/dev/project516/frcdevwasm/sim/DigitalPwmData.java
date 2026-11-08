package dev.project516.frcdevwasm.sim;

/** Simulation data of one digital PWM generator (mockdata/DigitalPWMData.cpp). */
public final class DigitalPwmData {
  private static final DigitalPwmData[] ALL = new DigitalPwmData[Ports.DIGITAL_PWM_OUTPUTS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new DigitalPwmData(i);
    }
  }

  public final BoolValue initialized = new BoolValue("Initialized");
  public final DoubleValue dutyCycle = new DoubleValue("DutyCycle");
  public final IntValue pin = new IntValue("Pin");

  private final Ws.Device ws;

  private DigitalPwmData(int index) {
    ws = new Ws.Device("dPWM", String.valueOf(index), initialized);
    bindWs();
  }

  private void bindWs() {
    ws.bind("<duty_cycle", dutyCycle).bind("<dio_pin", pin);
  }

  public static DigitalPwmData get(int index) {
    return ALL[index];
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : ALL[i].ws;
  }

  /** Index of the initialized generator driving the channel, or -1. */
  public static int findForChannel(int channel) {
    for (int i = 0; i < ALL.length; i++) {
      if (ALL[i].initialized.get() && ALL[i].pin.get() == channel) {
        return i;
      }
    }
    return -1;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetDigitalPWMData. */
  public void reset() {
    initialized.reset(false);
    dutyCycle.reset(0);
    pin.reset(0);
    ws.rebind();
  }
}
