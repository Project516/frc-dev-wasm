package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.ImuData;
import dev.project516.frcdevwasm.sim.Sim;

/** Port of hal/src/main/native/sim/IMU.cpp and the IMUJNI wrappers. */
public final class IMUJNIImpl {
  private IMUJNIImpl() {}

  private static ImuData d() {
    Sim.ensureInitialized();
    return ImuData.get();
  }

  private static void put(double[] out, String name, double x, double y, double z) {
    if (out.length < 3) {
      throw new IllegalArgumentException(
          name + " array too small: expected at least 3, got " + out.length);
    }
    out[0] = x;
    out[1] = y;
    out[2] = z;
  }

  public static void getIMUAcceleration(double[] accel) {
    put(accel, "accel", d().accelX.get(), d().accelY.get(), d().accelZ.get());
  }

  public static void getIMUGyroRates(double[] rates) {
    put(rates, "rates", d().gyroRateX.get(), d().gyroRateY.get(), d().gyroRateZ.get());
  }

  private static void angles(double[] angles) {
    put(angles, "angles", d().angleX.get(), d().angleY.get(), d().angleZ.get());
  }

  public static void getIMUEulerAnglesFlat(double[] angles) {
    angles(angles);
  }

  public static void getIMUEulerAnglesLandscape(double[] angles) {
    angles(angles);
  }

  public static void getIMUEulerAnglesPortrait(double[] angles) {
    angles(angles);
  }

  /** The sim has no orientation estimate: the quaternion is all zero, as upstream. */
  public static void getIMUQuaternion(double[] quat) {
    if (quat.length < 4) {
      throw new IllegalArgumentException(
          "quat array too small: expected at least 4, got " + quat.length);
    }
    quat[0] = 0;
    quat[1] = 0;
    quat[2] = 0;
    quat[3] = 0;
  }

  public static double getIMUYawFlat() {
    return d().yaw.get();
  }

  public static double getIMUYawLandscape() {
    return d().yaw.get();
  }

  public static double getIMUYawPortrait() {
    return d().yaw.get();
  }
}
