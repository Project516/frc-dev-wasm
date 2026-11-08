package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.DigitalPorts;
import dev.project516.frcdevwasm.sim.DigitalPorts.Port;
import dev.project516.frcdevwasm.sim.DigitalPwmData;
import dev.project516.frcdevwasm.sim.DioData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/** Port of hal/src/main/native/sim/DIO.cpp and the DIOJNI wrappers. */
public final class DIOJNIImpl {
  private static final Handles.Limited<Integer> DIGITAL_PWM =
      new Handles.Limited<>(Handles.DIGITAL_PWM, Ports.DIGITAL_PWM_OUTPUTS);

  private DIOJNIImpl() {}

  private static Port port(int handle) {
    Port p = DigitalPorts.get(handle, Handles.DIO);
    if (p == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return p;
  }

  public static int initializeDIOPort(int halPortHandle, boolean input) {
    Sim.ensureInitialized();
    int channel = Handles.portChannel(halPortHandle);
    if (channel == -1 || channel >= Ports.DIGITAL_CHANNELS) {
      Status.indexOutOfRange(
          Status.RESOURCE_OUT_OF_RANGE, "Invalid Index for DIO", 0, Ports.DIGITAL_CHANNELS, channel);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    DigitalPorts.Allocation a = DigitalPorts.allocate(channel, Handles.DIO);
    if (a.status != 0) {
      if (a.port != null) {
        Status.previouslyAllocated(a.status, "PWM or DIO", channel, a.port.previousAllocation);
      } else {
        Status.indexOutOfRange(
            a.status, "Invalid Index for DIO", 0, Ports.DIGITAL_CHANNELS, channel);
      }
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    a.port.channel = channel;
    DioData data = DioData.get(channel);
    data.initialized.set(true);
    data.isInput.set(input);
    data.simDevice = 0;
    data.value.set(true);
    a.port.previousAllocation = Status.allocationLocation();
    return a.handle;
  }

  public static boolean checkDIOChannel(int channel) {
    return channel < Ports.DIGITAL_CHANNELS && channel >= 0;
  }

  public static void freeDIOPort(int id) {
    if (id == Handles.INVALID) {
      return;
    }
    Port p = DigitalPorts.get(id, Handles.DIO);
    DigitalPorts.free(id, Handles.DIO);
    if (p != null) {
      DioData.get(p.channel).initialized.set(false);
    }
  }

  public static void setDIOSimDevice(int handle, int device) {
    Port p = DigitalPorts.get(handle, Handles.DIO);
    if (p != null) {
      DioData.get(p.channel).simDevice = device;
    }
  }

  public static void setDIO(int id, boolean value) {
    Port p = port(id);
    if (p == null) {
      return;
    }
    DioData data = DioData.get(p.channel);
    if (data.isInput.get()) {
      Status.check(
          Status.setLastError(
              Status.PARAMETER_OUT_OF_RANGE, "Cannot set output of an input channel"));
      return;
    }
    data.value.set(value);
  }

  public static void setDIODirection(int id, boolean input) {
    Port p = port(id);
    if (p != null) {
      DioData.get(p.channel).isInput.set(input);
    }
  }

  public static boolean getDIO(int id) {
    Port p = port(id);
    return p != null && DioData.get(p.channel).value.get();
  }

  public static boolean getDIODirection(int id) {
    Port p = port(id);
    return p != null && DioData.get(p.channel).isInput.get();
  }

  // Pulses are not simulated, as in the C++ sim.
  public static void pulse(int id, double value) {
    port(id);
  }

  public static void pulseMultiple(long channelMask, double value) {}

  public static boolean isPulsing(int id) {
    port(id);
    return false;
  }

  public static boolean isAnyPulsing() {
    return false;
  }

  public static short getLoopTiming() {
    return (short) DigitalPorts.EXPECTED_LOOP_TIMING;
  }

  public static int allocateDigitalPWM() {
    int handle = DIGITAL_PWM.allocate(0);
    if (handle == Handles.INVALID) {
      Status.check(Status.NO_AVAILABLE_RESOURCES);
      return 0;
    }
    DigitalPwmData.get(Handles.index(handle, Handles.DIGITAL_PWM)).initialized.set(true);
    return handle;
  }

  public static void freeDigitalPWM(int id) {
    if (id == Handles.INVALID) {
      return;
    }
    if (DIGITAL_PWM.free(id) != null) {
      DigitalPwmData.get(Handles.index(id, Handles.DIGITAL_PWM)).initialized.set(false);
    }
  }

  public static void setDigitalPWMRate(double value) {}

  public static void setDigitalPWMDutyCycle(int id, double value) {
    setDutyCycle(id, value);
  }

  public static void setDigitalPWMPPS(int id, double value) {
    setDutyCycle(id, value);
  }

  private static void setDutyCycle(int id, double dutyCycle) {
    if (DIGITAL_PWM.get(id) == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
      return;
    }
    DigitalPwmData.get(Handles.index(id, Handles.DIGITAL_PWM))
        .dutyCycle
        .set(Math.max(0.0, Math.min(1.0, dutyCycle)));
  }

  public static void setDigitalPWMOutputChannel(int id, int value) {
    if (DIGITAL_PWM.get(id) == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
      return;
    }
    DigitalPwmData.get(Handles.index(id, Handles.DIGITAL_PWM)).pin.set(value);
  }
}
