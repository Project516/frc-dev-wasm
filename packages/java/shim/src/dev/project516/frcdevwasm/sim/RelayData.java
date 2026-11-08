package dev.project516.frcdevwasm.sim;

/** Simulation data of one relay header (mockdata/RelayData.cpp). */
public final class RelayData {
  private static final RelayData[] ALL = new RelayData[Ports.RELAY_HEADERS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new RelayData(i);
    }
  }

  public final BoolValue initializedForward = new BoolValue("InitializedForward");
  public final BoolValue initializedReverse = new BoolValue("InitializedReverse");
  public final BoolValue forward = new BoolValue("Forward");
  public final BoolValue reverse = new BoolValue("Reverse");

  private final Ws.Device ws;

  private RelayData(int index) {
    ws = new Ws.Device("Relay", String.valueOf(index));
    ws.gate("<init_fwd", initializedForward).gate("<init_rev", initializedReverse);
    bindWs();
  }

  private void bindWs() {
    ws.bind("<fwd", forward).bind("<rev", reverse);
  }

  public static RelayData get(int index) {
    return ALL[index];
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : ALL[i].ws;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetRelayData. */
  public void reset() {
    initializedForward.reset(false);
    initializedReverse.reset(false);
    forward.reset(false);
    reverse.reset(false);
    ws.rebind();
  }
}
