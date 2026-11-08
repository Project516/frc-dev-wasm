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
  public final IntValue averageBits = new IntValue("AverageBits", 7);
  public final IntValue oversampleBits = new IntValue("OversampleBits");
  public final DoubleValue voltage = new DoubleValue("Voltage");
  public final BoolValue accumulatorInitialized = new BoolValue("AccumulatorInitialized");
  public final LongValue accumulatorValue = new LongValue("AccumulatorValue");
  public final LongValue accumulatorCount = new LongValue("AccumulatorCount");
  public final IntValue accumulatorCenter = new IntValue("AccumulatorCenter");
  public final IntValue accumulatorDeadband = new IntValue("AccumulatorDeadband");

  private final Ws.Device ws;

  private AnalogInData(int index) {
    ws = new Ws.Device("AI", String.valueOf(index), initialized);
    bindWs();
  }

  private void bindWs() {
    ws.bind("<avg_bits", averageBits)
        .bind("<oversample_bits", oversampleBits)
        .bindEcho(">voltage", voltage)
        .bind("<accum_init", accumulatorInitialized)
        .bindEcho(">accum_value", accumulatorValue)
        .bindEcho(">accum_count", accumulatorCount)
        .bind("<accum_center", accumulatorCenter)
        .bind("<accum_deadband", accumulatorDeadband);
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
    averageBits.reset(7);
    oversampleBits.reset(0);
    voltage.reset(0);
    accumulatorInitialized.reset(false);
    accumulatorValue.reset(0);
    accumulatorCount.reset(0);
    accumulatorCenter.reset(0);
    accumulatorDeadband.reset(0);
    ws.rebind();
  }
}
