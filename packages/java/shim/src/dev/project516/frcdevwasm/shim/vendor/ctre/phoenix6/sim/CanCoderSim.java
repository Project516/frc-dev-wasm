package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim;

/**
 * A CANcoder. The sim state writes the raw sensor position, which integrates into the reported
 * position so that a later setPosition survives. The sensor direction and magnet offset configs
 * apply as on the device.
 */
public final class CanCoderSim extends Device {
  private double rawPosition;
  private double lastRaw;
  private double velocity;
  private double homeOffset;
  private double supplyVoltage = 12;
  private double magnetHealth = 3;

  CanCoderSim(int id) {
    super("cancoder", id);
  }

  @Override
  public void setInput(String name, double value) {
    synchronized (PhoenixSim.LOCK) {
      switch (name) {
        case "RawPosition" -> {
          rawPosition += value - lastRaw;
          lastRaw = value;
        }
        case "AddPosition" -> {
          rawPosition += value;
          lastRaw += value;
        }
        case "Velocity" -> velocity = value;
        case "SupplyVoltage" -> supplyVoltage = value;
        case "MagnetHealth" -> magnetHealth = value;
        default -> super.setInput(name, value);
      }
    }
  }

  private double direction() {
    return config.get("CANcoder_SensorDirection") == 1 ? -1 : 1;
  }

  private double magnetOffset() {
    return config.get("CANCoder_MagnetOffset");
  }

  /** The relative position in rotations. */
  public double position() {
    synchronized (PhoenixSim.LOCK) {
      return direction() * rawPosition + magnetOffset() + homeOffset;
    }
  }

  /** The velocity in rotations per second. */
  public double velocity() {
    synchronized (PhoenixSim.LOCK) {
      return direction() * velocity;
    }
  }

  private double absolutePosition() {
    double discontinuity = config.get("Config_AbsoluteSensorDiscontinuityPoint");
    double p = direction() * rawPosition + magnetOffset();
    double low = discontinuity - 1;
    return p - Math.floor(p - low);
  }

  @Override
  public double signal(String spn) {
    synchronized (PhoenixSim.LOCK) {
      return switch (spn) {
        case "CANcoder_Position" -> position();
        case "CANCoder_RawPos" -> direction() * rawPosition;
        case "CANcoder_AbsPosition" -> absolutePosition();
        case "CANcoder_Velocity", "CANCoder_RawVel" -> velocity();
        case "CANCoder_SupplyVoltage" -> supplyVoltage;
        case "CANcoder_MagHealth" -> magnetHealth;
        default -> Double.NaN;
      };
    }
  }

  @Override
  protected void action(String spn, double value) {
    if (spn.equals("CANcoder_SetSensorPosition")) {
      homeOffset = value - (direction() * rawPosition + magnetOffset());
    }
  }
}
