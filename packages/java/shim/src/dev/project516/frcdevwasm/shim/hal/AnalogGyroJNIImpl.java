package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.AnalogGyroData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/** Port of sim/AnalogGyro.cpp and the AnalogGyroJNI wrappers. */
public final class AnalogGyroJNIImpl {
  private static final class Gyro {
    int handle;
    int index;
    String previousAllocation = "";
  }

  private static final Handles.Limited<Gyro> GYROS =
      new Handles.Limited<>(Handles.ANALOG_GYRO, Ports.ACCUMULATORS);

  private AnalogGyroJNIImpl() {}

  private static Gyro gyro(int handle) {
    Gyro g = GYROS.get(handle);
    if (g == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return g;
  }

  public static int initializeAnalogGyro(int id) {
    Sim.ensureInitialized();
    int channel = id & 0xffff;
    AnalogJNIImpl.InputPort analog = AnalogJNIImpl.inputPort(id);
    if (analog == null) {
      Status.checkForceThrow(Status.HAL_HANDLE_ERROR);
      return 0;
    }
    if (!AnalogJNIImpl.isAccumulator(analog)) {
      Status.indexOutOfRange(
          Status.HAL_INVALID_ACCUMULATOR_CHANNEL,
          "Invalid Index for Analog Gyro",
          0,
          Ports.ACCUMULATORS,
          channel);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    Gyro gyro = new Gyro();
    int handle = GYROS.allocate(channel, gyro);
    if (handle == Handles.INVALID) {
      Gyro existing = GYROS.get(Handles.make(channel, Handles.ANALOG_GYRO));
      Status.previouslyAllocated(
          Status.RESOURCE_IS_ALLOCATED,
          "Analog Gyro",
          channel,
          existing == null ? "" : existing.previousAllocation);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    gyro.handle = id;
    gyro.index = channel;
    AnalogGyroData.get(channel).initialized.set(true);
    gyro.previousAllocation = Status.allocationLocation();
    return handle;
  }

  public static void setupAnalogGyro(int id) {}

  public static void freeAnalogGyro(int id) {
    if (id == Handles.INVALID) {
      return;
    }
    Gyro gyro = GYROS.free(id);
    if (gyro != null) {
      AnalogGyroData.get(gyro.index).initialized.set(false);
    }
  }

  public static void setAnalogGyroParameters(
      int id, double voltsPerDegreePerSecond, double offset, int center) {}

  public static void setAnalogGyroVoltsPerDegreePerSecond(int id, double voltsPerDegreePerSecond) {}

  public static void resetAnalogGyro(int id) {
    Gyro gyro = gyro(id);
    if (gyro != null) {
      AnalogGyroData.get(gyro.index).angle.set(0.0);
    }
  }

  public static void calibrateAnalogGyro(int id) {
    resetAnalogGyro(id);
  }

  public static void setAnalogGyroDeadband(int id, double deadband) {}

  public static double getAnalogGyroAngle(int id) {
    Gyro gyro = gyro(id);
    return gyro == null ? 0 : AnalogGyroData.get(gyro.index).angle.get();
  }

  public static double getAnalogGyroRate(int id) {
    Gyro gyro = gyro(id);
    return gyro == null ? 0 : AnalogGyroData.get(gyro.index).rate.get();
  }

  public static double getAnalogGyroOffset(int id) {
    return 0.0;
  }

  public static int getAnalogGyroCenter(int id) {
    return 0;
  }
}
