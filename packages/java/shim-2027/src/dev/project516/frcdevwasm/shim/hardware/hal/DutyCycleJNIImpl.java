package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.DutyCycleData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/** Port of sim/DutyCycle.cpp and the DutyCycleJNI wrappers. */
public final class DutyCycleJNIImpl {
  private static final Handles.Limited<Integer> DUTY_CYCLES =
      new Handles.Limited<>(Handles.DUTY_CYCLE, Ports.DUTY_CYCLES);

  private DutyCycleJNIImpl() {}

  private static DutyCycleData data(int handle) {
    if (DUTY_CYCLES.get(handle) == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
      return null;
    }
    return DutyCycleData.get(handle & 0xffff);
  }

  public static int initialize(int channel) {
    Sim.ensureInitialized();
    int handle = DUTY_CYCLES.allocate(channel, channel, "Duty Cycle", Status.allocationLocation(), 0);
    DutyCycleData data = DutyCycleData.get(channel);
    data.initialized.set(true);
    data.simDevice = 0;
    return handle;
  }

  public static void free(int handle) {
    if (DUTY_CYCLES.free(handle) != null) {
      DutyCycleData.get(handle & 0xffff).initialized.set(false);
    }
  }

  public static double getFrequency(int handle) {
    DutyCycleData d = data(handle);
    return d == null ? 0 : d.frequency.get();
  }

  public static double getOutput(int handle) {
    DutyCycleData d = data(handle);
    return d == null ? 0 : d.output.get();
  }

  public static int getHighTime(int handle) {
    DutyCycleData d = data(handle);
    if (d == null || d.frequency.get() == 0) {
      return 0;
    }
    double periodNanoSeconds = 1e9 / d.frequency.get();
    return (int) (periodNanoSeconds * d.output.get());
  }
}
