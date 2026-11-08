package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.ImuData;
import dev.project516.frcdevwasm.sim.Sim;

/** HALSIM IMU data access (IMUDataJNI.cpp and mockdata/IMUData.cpp). */
public final class IMUDataJNIImpl {
  private IMUDataJNIImpl() {}

  private static ImuData d() {
    Sim.ensureInitialized();
    return ImuData.get();
  }

  public static void setAngleX(double angleRad) {
    d().angleX.set(angleRad);
  }

  public static void setAngleY(double angleRad) {
    d().angleY.set(angleRad);
  }

  public static void setAngleZ(double angleRad) {
    d().angleZ.set(angleRad);
  }

  public static void setGyroRateX(double rateRadPerSec) {
    d().gyroRateX.set(rateRadPerSec);
  }

  public static void setGyroRateY(double rateRadPerSec) {
    d().gyroRateY.set(rateRadPerSec);
  }

  public static void setGyroRateZ(double rateRadPerSec) {
    d().gyroRateZ.set(rateRadPerSec);
  }

  public static void setAccelX(double accelMpss) {
    d().accelX.set(accelMpss);
  }

  public static void setAccelY(double accelMpss) {
    d().accelY.set(accelMpss);
  }

  public static void setAccelZ(double accelMpss) {
    d().accelZ.set(accelMpss);
  }

  public static void setYaw(double angleRad) {
    d().yaw.set(angleRad);
  }
}
