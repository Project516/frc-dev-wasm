package dev.project516.frcdevwasm.sim;

/**
 * Simulation data of one REV PH (hal/src/main/native/sim/mockdata/REVPHData.cpp). The index is
 * the module number, 1 to 63.
 */
public final class REVPHData {
  /** HAL_REVPHCompressorConfigType values. */
  public static final int CONFIG_DISABLED = 0;

  public static final int CONFIG_DIGITAL = 1;
  public static final int CONFIG_ANALOG = 2;
  public static final int CONFIG_HYBRID = 3;

  private static final REVPHData[] ALL = new REVPHData[Ports.REV_PH_MODULES + 1];

  public final BoolValue initialized = new BoolValue("Initialized");
  public final BoolValue[] solenoidOutput = new BoolValue[Ports.REV_PH_CHANNELS];
  public final BoolValue compressorOn = new BoolValue("CompressorOn");
  public final IntValue compressorConfigType =
      IntValue.asEnum("CompressorConfigType", CONFIG_DISABLED);
  public final BoolValue pressureSwitch = new BoolValue("PressureSwitch");
  public final DoubleValue compressorCurrent = new DoubleValue("CompressorCurrent");

  private final Ws.Device ws;
  private final Ws.Device[] solenoidWs = new Ws.Device[Ports.REV_PH_CHANNELS];

  private REVPHData(int index) {
    ws = new Ws.Device("REVPH", String.valueOf(index), initialized);
    for (int i = 0; i < solenoidOutput.length; i++) {
      solenoidOutput[i] = new BoolValue("SolenoidOutput");
      solenoidWs[i] = new Ws.Device("REVPHSolenoid", index + "," + i);
    }
    bindWs();
  }

  // Not in halsim_ws. The keys follow its CTREPCM ones, see docs/bridge-protocol.md.
  private void bindWs() {
    ws.bindEcho(">on", compressorOn)
        .bind("<config", compressorConfigType)
        .bindEcho(">pressure_switch", pressureSwitch)
        .bindEcho(">current", compressorCurrent);
    for (int i = 0; i < solenoidWs.length; i++) {
      solenoidWs[i].bind("<output", solenoidOutput[i]);
    }
  }

  public static synchronized REVPHData get(int index) {
    REVPHData d = ALL[index];
    if (d == null) {
      d = new REVPHData(index);
      ALL[index] = d;
    }
    return d;
  }

  /** Resolver for Ws.type "REVPH". */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : get(i).ws;
  }

  /** Resolver for Ws.type "REVPHSolenoid": the id is "module,channel". */
  static Ws.Device solenoidWs(String id) {
    int comma = id.indexOf(',');
    if (comma < 0) {
      return null;
    }
    int module = Ws.index(id.substring(0, comma), ALL.length);
    int channel = Ws.index(id.substring(comma + 1), Ports.REV_PH_CHANNELS);
    return module < 0 || channel < 0 ? null : get(module).solenoidWs[channel];
  }

  /** Solenoid outputs as a bit mask. */
  public int solenoidMask() {
    int mask = 0;
    for (int i = 0; i < solenoidOutput.length; i++) {
      if (solenoidOutput[i].get()) {
        mask |= 1 << i;
      }
    }
    return mask;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetREVPHData. */
  public void reset() {
    for (BoolValue s : solenoidOutput) {
      s.reset(false);
    }
    initialized.reset(false);
    compressorOn.reset(false);
    compressorConfigType.reset(CONFIG_DISABLED);
    pressureSwitch.reset(false);
    compressorCurrent.reset(0.0);
    ws.rebind();
    for (Ws.Device d : solenoidWs) {
      d.rebind();
    }
  }
}
