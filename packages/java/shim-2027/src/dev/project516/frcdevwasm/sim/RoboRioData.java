package dev.project516.frcdevwasm.sim;

/** Simulation data of the roboRIO (hal/src/main/native/sim/mockdata/RoboRioData.cpp). */
public final class RoboRioData {
  private static final RoboRioData INSTANCE = new RoboRioData();

  private static final int MAX_SERIAL_LENGTH = 8;
  private static final int MAX_COMMENTS_LENGTH = 64;

  public final DoubleValue vInVoltage = new DoubleValue("VInVoltage", 12.0);
  public final DoubleValue userVoltage3V3 = new DoubleValue("UserVoltage3V3", 3.3);
  public final DoubleValue userCurrent3V3 = new DoubleValue("UserCurrent3V3");
  public final BoolValue userActive3V3 = new BoolValue("UserActive3V3", true);
  public final IntValue userFaults3V3 = new IntValue("UserFaults3V3");
  public final DoubleValue brownoutVoltage = new DoubleValue("BrownoutVoltage", 6.75);
  public final DoubleValue brownoutRecoveryVoltage =
      new DoubleValue("BrownoutRecoveryVoltage", 7.25);
  public final DoubleValue cpuTemp = new DoubleValue("CPUTemp", 45.0);
  public final IntValue teamNumber = new IntValue("TeamNumber");

  private String serialNumber = "";
  private String comments = "";

  private final Ws.Device ws = new Ws.Device("RoboRIO", "");

  private RoboRioData() {
    bindWs();
  }

  private void bindWs() {
    ws.bindEcho(">vin_voltage", vInVoltage)
        .bindEcho(">3v3_voltage", userVoltage3V3)
        .bindEcho(">3v3_current", userCurrent3V3)
        .bindEcho(">3v3_active", userActive3V3)
        .bindEcho(">3v3_faults", userFaults3V3);
  }

  public static RoboRioData get() {
    return INSTANCE;
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    return INSTANCE.ws;
  }

  public synchronized String getSerialNumber() {
    return serialNumber;
  }

  public synchronized void setSerialNumber(String value) {
    serialNumber = truncate(value, MAX_SERIAL_LENGTH);
  }

  public synchronized String getComments() {
    return comments;
  }

  public synchronized void setComments(String value) {
    comments = truncate(value, MAX_COMMENTS_LENGTH);
  }

  private static String truncate(String value, int max) {
    return value.length() > max ? value.substring(0, max) : value;
  }

  /** Resets values and drops callbacks, as HALSIM_ResetRoboRioData. */
  public void reset() {
    vInVoltage.reset(12.0);
    userVoltage3V3.reset(3.3);
    userCurrent3V3.reset(0.0);
    userActive3V3.reset(true);
    userFaults3V3.reset(0);
    brownoutVoltage.reset(6.75);
    brownoutRecoveryVoltage.reset(7.25);
    cpuTemp.reset(45.0);
    teamNumber.reset(0);
    synchronized (this) {
      serialNumber = "";
      comments = "";
    }
    ws.rebind();
  }
}
