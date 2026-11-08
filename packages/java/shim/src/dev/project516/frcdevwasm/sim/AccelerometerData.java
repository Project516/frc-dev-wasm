package dev.project516.frcdevwasm.sim;

/** Simulation data of the built-in accelerometer (mockdata/AccelerometerData.cpp). */
public final class AccelerometerData {
  /** Device id of the built-in accelerometer in halsim_ws. */
  public static final String WS_DEVICE = "BuiltInAccel";

  private static final AccelerometerData[] ALL = new AccelerometerData[Ports.ACCELEROMETERS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new AccelerometerData();
    }
  }

  public final BoolValue active = new BoolValue("Active");
  public final IntValue range = IntValue.asEnum("Range", 0);
  public final DoubleValue x = new DoubleValue("X");
  public final DoubleValue y = new DoubleValue("Y");
  public final DoubleValue z = new DoubleValue("Z");

  private final Ws.Device ws =
      new Ws.Device("Accel", WS_DEVICE, active) {
        @Override
        protected Object json(String key, SimValue value) {
          if (value == range) {
            return switch (range.get()) {
              case 0 -> 2.0;
              case 1 -> 4.0;
              default -> 8.0;
            };
          }
          return super.json(key, value);
        }
      };

  private AccelerometerData() {
    bindWs();
  }

  private void bindWs() {
    ws.bind("<range", range)
        .bindEcho(">x", x)
        .bindEcho(">y", y)
        .bindEcho(">z", z);
  }

  public static AccelerometerData get(int index) {
    return ALL[index];
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    return WS_DEVICE.equals(id) ? ALL[0].ws : null;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetAccelerometerData. */
  public void reset() {
    active.reset(false);
    range.reset(0);
    x.reset(0);
    y.reset(0);
    z.reset(0);
    ws.rebind();
  }
}
