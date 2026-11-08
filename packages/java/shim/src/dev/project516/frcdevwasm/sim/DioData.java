package dev.project516.frcdevwasm.sim;

/** Simulation data of one DIO channel (hal/src/main/native/sim/mockdata/DIOData.cpp). */
public final class DioData {
  private static final DioData[] ALL = new DioData[Ports.DIGITAL_CHANNELS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new DioData(i);
    }
  }

  public final BoolValue initialized = new BoolValue("Initialized");
  public final BoolValue value = new BoolValue("Value", true);
  public final DoubleValue pulseLength = new DoubleValue("PulseLength");
  public final BoolValue isInput = new BoolValue("IsInput", true);
  public final IntValue filterIndex = new IntValue("FilterIndex", -1);
  public volatile int simDevice;

  private final Ws.Device ws;

  private DioData(int index) {
    ws = new Ws.Device("DIO", String.valueOf(index), initialized);
    bindWs();
  }

  private void bindWs() {
    ws.bind("<>value", value).bind("<pulse_length", pulseLength).bind("<input", isInput);
  }

  public static DioData get(int index) {
    return ALL[index];
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : ALL[i].ws;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetDIOData. */
  public void reset() {
    initialized.reset(false);
    simDevice = 0;
    value.reset(true);
    pulseLength.reset(0);
    isInput.reset(true);
    filterIndex.reset(-1);
    ws.rebind();
  }
}
