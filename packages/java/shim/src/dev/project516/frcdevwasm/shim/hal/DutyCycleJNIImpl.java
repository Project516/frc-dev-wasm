package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.DutyCycleData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/** Port of sim/DutyCycle.cpp and the DutyCycleJNI wrappers. */
public final class DutyCycleJNIImpl {
  private static final class DutyCycle {
    int index;
  }

  private static final Handles.Limited<DutyCycle> DUTY_CYCLES =
      new Handles.Limited<>(Handles.DUTY_CYCLE, Ports.DUTY_CYCLES);

  private DutyCycleJNIImpl() {}

  private static DutyCycle dutyCycle(int handle) {
    DutyCycle d = DUTY_CYCLES.get(handle);
    if (d == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return d;
  }

  public static int initialize(int digitalSourceHandle, int analogTriggerType) {
    Sim.ensureInitialized();
    DutyCycle dutyCycle = new DutyCycle();
    int handle = DUTY_CYCLES.allocate(dutyCycle);
    if (handle == Handles.INVALID) {
      Status.check(Status.NO_AVAILABLE_RESOURCES);
      return 0;
    }
    int index = handle & 0xffff;
    DutyCycleData data = DutyCycleData.get(index);
    data.digitalChannel = digitalSourceHandle & 0xffff;
    data.initialized.set(true);
    data.simDevice = 0;
    dutyCycle.index = index;
    return handle;
  }

  public static void free(int handle) {
    if (handle == Handles.INVALID) {
      return;
    }
    DutyCycle dutyCycle = DUTY_CYCLES.free(handle);
    if (dutyCycle != null) {
      DutyCycleData.get(dutyCycle.index).initialized.set(false);
    }
  }

  public static int getFrequency(int handle) {
    DutyCycle d = dutyCycle(handle);
    return d == null ? 0 : DutyCycleData.get(d.index).frequency.get();
  }

  public static double getOutput(int handle) {
    DutyCycle d = dutyCycle(handle);
    return d == null ? 0 : DutyCycleData.get(d.index).output.get();
  }

  public static int getHighTime(int handle) {
    DutyCycle d = dutyCycle(handle);
    if (d == null) {
      return 0;
    }
    DutyCycleData data = DutyCycleData.get(d.index);
    int frequency = data.frequency.get();
    if (frequency == 0) {
      return 0;
    }
    double periodNanoSeconds = 1.0 / frequency * 1e9;
    return (int) (periodNanoSeconds * data.output.get());
  }

  public static int getOutputScaleFactor(int handle) {
    return (int) (4e7 - 1);
  }

  public static int getFPGAIndex(int handle) {
    DutyCycle d = dutyCycle(handle);
    return d == null ? -1 : d.index;
  }
}
