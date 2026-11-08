package dev.project516.frcdevwasm.sim;

/** Simulation data of one analog input (hal/src/main/native/sim/mockdata/AnalogInData.cpp). */
public final class AnalogInData {
  private static final AnalogInData[] ALL = new AnalogInData[Ports.ANALOG_INPUTS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new AnalogInData(i);
    }
  }

  public final BoolValue initialized = new BoolValue("Initialized");
  public volatile int simDevice;
  public final DoubleValue voltage = new DoubleValue("Voltage");

  private final Ws.Device ws;

  private AnalogInData(int index) {
    ws = new Ws.Device("AI", String.valueOf(index), initialized);
    bindWs();
  }

  private void bindWs() {
    ws.bindEcho(">voltage", voltage);
  }

  public static AnalogInData get(int index) {
    return ALL[index];
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : ALL[i].ws;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetAnalogInData. */
  public void reset() {
    initialized.reset(false);
    simDevice = 0;
    voltage.reset(0);
    ws.rebind();
  }
}
