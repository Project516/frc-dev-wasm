package dev.project516.frcdevwasm.sim;

/**
 * Simulation data of one power distribution module
 * (hal/src/main/native/sim/mockdata/PowerDistributionData.cpp). The index is the module number:
 * 0 to 62 for a CTRE PDP, 1 to 63 for a REV PDH.
 */
public final class PowerDistributionData {
  private static final PowerDistributionData[] ALL =
      new PowerDistributionData[Ports.PD_SIM_MODULES + 1];

  public final BoolValue initialized = new BoolValue("Initialized");
  public final DoubleValue temperature = new DoubleValue("Temperature");
  public final DoubleValue voltage = new DoubleValue("Voltage", 12.0);
  public final DoubleValue[] current = new DoubleValue[Ports.PD_SIM_CHANNELS];

  private final Ws.Device ws;
  private final Ws.Device[] channelWs = new Ws.Device[Ports.PD_SIM_CHANNELS];

  private PowerDistributionData(int index) {
    ws = new Ws.Device("PowerDistribution", String.valueOf(index), initialized);
    for (int i = 0; i < current.length; i++) {
      current[i] = new DoubleValue("Current");
      channelWs[i] = new Ws.Device("PDChannel", index + "," + i);
    }
    bindWs();
  }

  // Not in halsim_ws. The keys follow its conventions, see docs/bridge-protocol.md.
  private void bindWs() {
    ws.bindEcho(">temperature", temperature).bindEcho(">voltage", voltage);
    for (int i = 0; i < channelWs.length; i++) {
      channelWs[i].bindEcho(">current", current[i]);
    }
  }

  public static synchronized PowerDistributionData get(int index) {
    PowerDistributionData d = ALL[index];
    if (d == null) {
      d = new PowerDistributionData(index);
      ALL[index] = d;
    }
    return d;
  }

  /** Resolver for Ws.type "PowerDistribution". */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : get(i).ws;
  }

  /** Resolver for Ws.type "PDChannel": the id is "module,channel". */
  static Ws.Device channelWs(String id) {
    int comma = id.indexOf(',');
    if (comma < 0) {
      return null;
    }
    int module = Ws.index(id.substring(0, comma), ALL.length);
    int channel = Ws.index(id.substring(comma + 1), Ports.PD_SIM_CHANNELS);
    return module < 0 || channel < 0 ? null : get(module).channelWs[channel];
  }

  /** Resets values and drops callbacks, as HALSIM_ResetPowerDistributionData. */
  public void reset() {
    initialized.reset(false);
    temperature.reset(0.0);
    voltage.reset(12.0);
    for (DoubleValue c : current) {
      c.reset(0.0);
    }
    ws.rebind();
    for (Ws.Device d : channelWs) {
      d.rebind();
    }
  }
}
