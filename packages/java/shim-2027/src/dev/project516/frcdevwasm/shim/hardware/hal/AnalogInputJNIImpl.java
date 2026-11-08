package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.AnalogInData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/** Port of hal/src/main/native/sim/AnalogInput.cpp and the AnalogInputJNI wrappers. */
public final class AnalogInputJNIImpl {
  private static final Handles.Limited<Integer> INPUTS =
      new Handles.Limited<>(Handles.ANALOG_INPUT, Ports.ANALOG_INPUTS);

  private AnalogInputJNIImpl() {}

  private static AnalogInData data(int handle) {
    if (INPUTS.get(handle) == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
      return null;
    }
    return AnalogInData.get(handle & 0xffff);
  }

  public static int initializeAnalogInputPort(int channel) {
    Sim.ensureInitialized();
    if (channel < 0 || channel >= Ports.ANALOG_INPUTS) {
      Status.indexOutOfRange(
          Status.RESOURCE_OUT_OF_RANGE,
          "Invalid Index for Analog Input",
          0,
          Ports.ANALOG_INPUTS - 1,
          channel);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    int handle = INPUTS.allocate(channel, channel, "Analog", Status.allocationLocation(), 0);
    AnalogInData data = AnalogInData.get(channel);
    data.initialized.set(true);
    data.simDevice = 0;
    return handle;
  }

  public static void freeAnalogInputPort(int handle) {
    if (INPUTS.free(handle) != null) {
      AnalogInData.get(handle & 0xffff).initialized.set(false);
    }
  }

  public static boolean checkAnalogModule(byte module) {
    return module == 1;
  }

  public static boolean checkAnalogInputChannel(int channel) {
    return channel < Ports.ANALOG_INPUTS && channel >= 0;
  }

  public static void setAnalogInputSimDevice(int handle, int device) {
    if (INPUTS.get(handle) != null) {
      AnalogInData.get(handle & 0xffff).simDevice = device;
    }
  }

  public static short getAnalogValue(int handle) {
    AnalogInData d = data(handle);
    return d == null ? 0 : (short) getAnalogVoltsToValue(handle, d.voltage.get());
  }

  public static int getAnalogVoltsToValue(int handle, double voltage) {
    return (int) (voltage * 4095.0 / 3.3);
  }

  public static double getAnalogVoltage(int handle) {
    AnalogInData d = data(handle);
    return d == null ? 0.0 : d.voltage.get();
  }

  public static double getAnalogValueToVolts(int handle, int rawValue) {
    return rawValue / 4095.0 * 3.3;
  }
}
