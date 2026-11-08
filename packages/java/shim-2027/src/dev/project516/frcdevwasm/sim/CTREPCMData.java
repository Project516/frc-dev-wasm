package dev.project516.frcdevwasm.sim;

/** Simulation data of one CTRE PCM (hal/src/main/native/sim/mockdata/CTREPCMData.cpp). */
public final class CTREPCMData {
  private static final CTREPCMData[] ALL = new CTREPCMData[Ports.CTRE_PCM_MODULES];

  public final BoolValue initialized = new BoolValue("Initialized");
  public final BoolValue[] solenoidOutput = new BoolValue[Ports.CTRE_SOLENOID_CHANNELS];
  public final BoolValue compressorOn = new BoolValue("CompressorOn");
  public final BoolValue closedLoopEnabled = new BoolValue("ClosedLoopEnabled", true);
  public final BoolValue pressureSwitch = new BoolValue("PressureSwitch");
  public final DoubleValue compressorCurrent = new DoubleValue("CompressorCurrent");

  private final Ws.Device ws;
  private final Ws.Device[] solenoidWs = new Ws.Device[Ports.CTRE_SOLENOID_CHANNELS];

  private CTREPCMData(int index) {
    ws = new Ws.Device("CTREPCM", String.valueOf(index), initialized);
    for (int i = 0; i < solenoidOutput.length; i++) {
      solenoidOutput[i] = new BoolValue("SolenoidOutput");
      solenoidWs[i] = new Ws.Device("Solenoid", index + "," + i);
    }
    bindWs();
  }

  private void bindWs() {
    ws.bindEcho(">on", compressorOn)
        .bind("<closed_loop", closedLoopEnabled)
        .bindEcho(">pressure_switch", pressureSwitch)
        .bindEcho(">current", compressorCurrent);
    for (int i = 0; i < solenoidWs.length; i++) {
      solenoidWs[i].bind("<output", solenoidOutput[i]);
    }
  }

  public static synchronized CTREPCMData get(int index) {
    CTREPCMData d = ALL[index];
    if (d == null) {
      d = new CTREPCMData(index);
      ALL[index] = d;
    }
    return d;
  }

  /** Resolver for Ws.type "CTREPCM". */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : get(i).ws;
  }

  /** Resolver for Ws.type "Solenoid": the id is "module,channel". */
  static Ws.Device solenoidWs(String id) {
    int comma = id.indexOf(',');
    if (comma < 0) {
      return null;
    }
    int module = Ws.index(id.substring(0, comma), ALL.length);
    int channel = Ws.index(id.substring(comma + 1), Ports.CTRE_SOLENOID_CHANNELS);
    return module < 0 || channel < 0 ? null : get(module).solenoidWs[channel];
  }

  /** Solenoid outputs as a bit mask, as HALSIM_GetCTREPCMAllSolenoids. */
  public int solenoidMask() {
    int mask = 0;
    for (int i = 0; i < solenoidOutput.length; i++) {
      if (solenoidOutput[i].get()) {
        mask |= 1 << i;
      }
    }
    return mask;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetCTREPCMData. */
  public void reset() {
    for (BoolValue s : solenoidOutput) {
      s.reset(false);
    }
    initialized.reset(false);
    compressorOn.reset(false);
    closedLoopEnabled.reset(true);
    pressureSwitch.reset(false);
    compressorCurrent.reset(0.0);
    ws.rebind();
    for (Ws.Device d : solenoidWs) {
      d.rebind();
    }
  }
}
