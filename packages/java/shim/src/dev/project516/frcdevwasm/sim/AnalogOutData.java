package dev.project516.frcdevwasm.sim;

/** Simulation data of one analog output (hal/src/main/native/sim/mockdata/AnalogOutData.cpp). */
public final class AnalogOutData {
  private static final AnalogOutData[] ALL = new AnalogOutData[Ports.ANALOG_OUTPUTS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new AnalogOutData(i);
    }
  }

  public final DoubleValue voltage = new DoubleValue("Voltage");
  public final BoolValue initialized = new BoolValue("Initialized");

  private final Ws.Device ws;

  private AnalogOutData(int index) {
    ws = new Ws.Device("AO", String.valueOf(index), initialized);
    bindWs();
  }

  private void bindWs() {
    ws.bind("<voltage", voltage);
  }

  public static AnalogOutData get(int index) {
    return ALL[index];
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : ALL[i].ws;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetAnalogOutData. */
  public void reset() {
    voltage.reset(0);
    initialized.reset(false);
    ws.rebind();
  }
}
