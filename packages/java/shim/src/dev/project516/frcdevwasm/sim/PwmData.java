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
  public final DoubleValue speed = new DoubleValue("Speed");
  public final DoubleValue position = new DoubleValue("Position");
  public final IntValue periodScale = new IntValue("PeriodScale");
  public final BoolValue zeroLatch = new BoolValue("ZeroLatch");

  private final Ws.Device ws;

  private PwmData(int index) {
    ws = new Ws.Device("PWM", String.valueOf(index), initialized);
    bindWs();
  }

  private void bindWs() {
    ws.bind("<speed", speed)
        .bind("<position", position)
        .bind("<raw", pulseMicrosecond)
        .bind("<period_scale", periodScale)
        .bind("<zero_latch", zeroLatch);
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
    pulseMicrosecond.reset(0);
    speed.reset(0);
    position.reset(0);
    periodScale.reset(0);
    zeroLatch.reset(false);
    ws.rebind();
  }
}
