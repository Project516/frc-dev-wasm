package dev.project516.frcdevwasm.sim;

/** Simulation data of one PWM channel (hal/src/main/native/sim/mockdata/PWMData.cpp). */
public final class PwmData {
  private static final PwmData[] ALL = new PwmData[Ports.PWM_CHANNELS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new PwmData(i);
    }
  }

  public final BoolValue initialized = new BoolValue("Initialized");
  public final IntValue pulseMicrosecond = new IntValue("PulseMicrosecond");
  public final IntValue outputPeriod = new IntValue("OutputPeriod");
  public volatile int simDevice;

  private final Ws.Device ws;

  private PwmData(int index) {
    ws = new Ws.Device("PWM", String.valueOf(index), initialized);
    bindWs();
  }

  private void bindWs() {
    ws.bind("<raw", pulseMicrosecond).bind("<output_period", outputPeriod);
  }

  public static PwmData get(int index) {
    return ALL[index];
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : ALL[i].ws;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetPWMData. */
  public void reset() {
    initialized.reset(false);
    simDevice = 0;
    pulseMicrosecond.reset(0);
    outputPeriod.reset(0);
    ws.rebind();
  }
}
