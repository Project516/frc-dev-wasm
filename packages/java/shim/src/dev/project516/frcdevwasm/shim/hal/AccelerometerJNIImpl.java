package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.AccelerometerData;
import dev.project516.frcdevwasm.sim.Sim;

/** Port of sim/Accelerometer.cpp and the AccelerometerJNI wrappers. */
public final class AccelerometerJNIImpl {
  private AccelerometerJNIImpl() {}

  private static AccelerometerData d() {
    Sim.ensureInitialized();
    return AccelerometerData.get(0);
  }

  public static void setAccelerometerActive(boolean active) {
    d().active.set(active);
  }

  public static void setAccelerometerRange(int range) {
    d().range.set(range);
  }

  public static double getAccelerometerX() {
    return d().x.get();
  }

  public static double getAccelerometerY() {
    return d().y.get();
  }

  public static double getAccelerometerZ() {
    return d().z.get();
  }
}
