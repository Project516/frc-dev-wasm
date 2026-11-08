package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim;

/** A Pigeon 2. The sim state writes the raw yaw, which integrates so that a later setYaw survives. */
public final class Pigeon2Sim extends Device {
  private double rawYaw;
  private double lastRaw;
  private double yawOffset;
  private double pitch;
  private double roll;
  private double rateX;
  private double rateY;
  private double rateZ;
  private double supplyVoltage = 12;

  Pigeon2Sim(int id) {
    super("pigeon 2", id);
  }

  @Override
  public void setInput(String name, double value) {
    synchronized (PhoenixSim.LOCK) {
      switch (name) {
        case "RawYaw" -> {
          rawYaw += value - lastRaw;
          lastRaw = value;
        }
        case "AddYaw" -> {
          rawYaw += value;
          lastRaw += value;
        }
        case "Pitch" -> pitch = value;
        case "Roll" -> roll = value;
        case "AngularVelocityX" -> rateX = value;
        case "AngularVelocityY" -> rateY = value;
        case "AngularVelocityZ" -> rateZ = value;
        case "SupplyVoltage" -> supplyVoltage = value;
        default -> super.setInput(name, value);
      }
    }
  }

  /** The yaw in degrees. */
  public double yaw() {
    synchronized (PhoenixSim.LOCK) {
      return rawYaw + yawOffset;
    }
  }

  @Override
  public double signal(String spn) {
    synchronized (PhoenixSim.LOCK) {
      return switch (spn) {
        case "Pigeon2Yaw" -> yaw();
        case "Pigeon2Pitch" -> pitch;
        case "Pigeon2Roll" -> roll;
        case "Pigeon2AngularVelocityX", "Pigeon2AngularVelocityXWorld" -> rateX;
        case "Pigeon2AngularVelocityY", "Pigeon2AngularVelocityYWorld" -> rateY;
        case "Pigeon2AngularVelocityZ", "Pigeon2AngularVelocityZWorld" -> rateZ;
        case "Pigeon2_SupplyVoltage" -> supplyVoltage;
        case "Pigeon2Temperature" -> 30;
        case "Pigeon2QuatW", "Pigeon2QuatX", "Pigeon2QuatY", "Pigeon2QuatZ" -> quaternion(spn);
        case "Pigeon2GravityVectorX" -> -Math.sin(Math.toRadians(pitch));
        case "Pigeon2GravityVectorY" ->
            Math.sin(Math.toRadians(roll)) * Math.cos(Math.toRadians(pitch));
        case "Pigeon2GravityVectorZ" ->
            Math.cos(Math.toRadians(roll)) * Math.cos(Math.toRadians(pitch));
        default -> Double.NaN;
      };
    }
  }

  private double quaternion(String spn) {
    double cy = Math.cos(Math.toRadians(yaw()) / 2);
    double sy = Math.sin(Math.toRadians(yaw()) / 2);
    double cp = Math.cos(Math.toRadians(pitch) / 2);
    double sp = Math.sin(Math.toRadians(pitch) / 2);
    double cr = Math.cos(Math.toRadians(roll) / 2);
    double sr = Math.sin(Math.toRadians(roll) / 2);
    return switch (spn) {
      case "Pigeon2QuatW" -> cr * cp * cy + sr * sp * sy;
      case "Pigeon2QuatX" -> sr * cp * cy - cr * sp * sy;
      case "Pigeon2QuatY" -> cr * sp * cy + sr * cp * sy;
      default -> cr * cp * sy - sr * sp * cy;
    };
  }

  @Override
  protected void action(String spn, double value) {
    if (spn.equals("Pigeon2_SetYaw")) {
      yawOffset = value - rawYaw;
    }
  }
}
