package dev.project516.frcdevwasm.sim;

/** Simulation data of the onboard IMU (hal/src/main/native/sim/mockdata/IMUData.cpp). */
public final class ImuData {
  private static final ImuData INSTANCE = new ImuData();

  public final DoubleValue angleX = new DoubleValue("AngleX");
  public final DoubleValue angleY = new DoubleValue("AngleY");
  public final DoubleValue angleZ = new DoubleValue("AngleZ");
  public final DoubleValue gyroRateX = new DoubleValue("GyroRateX");
  public final DoubleValue gyroRateY = new DoubleValue("GyroRateY");
  public final DoubleValue gyroRateZ = new DoubleValue("GyroRateZ");
  public final DoubleValue accelX = new DoubleValue("AccelX");
  public final DoubleValue accelY = new DoubleValue("AccelY");
  public final DoubleValue accelZ = new DoubleValue("AccelZ");
  public final DoubleValue yaw = new DoubleValue("Yaw");

  private ImuData() {}

  public static ImuData get() {
    return INSTANCE;
  }

  /** Resets values and drops callbacks. */
  public void reset() {
    angleX.reset(0);
    angleY.reset(0);
    angleZ.reset(0);
    gyroRateX.reset(0);
    gyroRateY.reset(0);
    gyroRateZ.reset(0);
    accelX.reset(0);
    accelY.reset(0);
    accelZ.reset(0);
    yaw.reset(0);
  }
}
