package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.DigitalPorts;
import dev.project516.frcdevwasm.sim.DigitalPorts.Port;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.PwmData;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/** Port of hal/src/main/native/sim/PWM.cpp and the PWMJNI wrappers. */
public final class PWMJNIImpl {
  private PWMJNIImpl() {}

  private static Port port(int handle) {
    Port p = DigitalPorts.get(handle, Handles.PWM);
    if (p == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return p;
  }

  public static int initializePWMPort(int channel) {
    Sim.ensureInitialized();
    if (channel < 0 || channel >= Ports.PWM_CHANNELS) {
      Status.indexOutOfRange(
          Status.RESOURCE_OUT_OF_RANGE, "Invalid Index for PWM", 0, Ports.PWM_CHANNELS - 1, channel);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    int origChannel = channel;
    if (origChannel < Ports.PWM_HEADERS) {
      channel += Ports.DIGITAL_CHANNELS;
    } else {
      channel = DigitalPorts.remapMxpPwmChannel(channel) + 10;
    }
    DigitalPorts.Allocation a = DigitalPorts.allocate(channel, Handles.PWM, "PWM");
    if (a.status != 0) {
      Status.checkForceThrow(a.status);
      return 0;
    }
    a.port.channel = origChannel;
    PwmData.get(origChannel).initialized.set(true);
    PwmData.get(origChannel).pulseMicrosecond.set(0);
    a.port.previousAllocation = Status.allocationLocation();
    return a.handle;
  }

  public static boolean checkPWMChannel(int channel) {
    return channel < Ports.PWM_CHANNELS && channel >= 0;
  }

  public static void freePWMPort(int pwmPortHandle) {
    Port p = DigitalPorts.get(pwmPortHandle, Handles.PWM);
    if (p == null) {
      return;
    }
    PwmData.get(p.channel).initialized.set(false);
    DigitalPorts.free(pwmPortHandle, Handles.PWM);
  }

  public static void setPWMSimDevice(int handle, int device) {
    Port p = DigitalPorts.get(handle, Handles.PWM);
    if (p != null) {
      PwmData.get(p.channel).simDevice = device;
    }
  }

  public static void setPulseTimeMicroseconds(int pwmPortHandle, int microsecondPulseTime) {
    Port p = port(pwmPortHandle);
    if (p != null) {
      PwmData.get(p.channel).pulseMicrosecond.set(microsecondPulseTime);
    }
  }

  public static int getPulseTimeMicroseconds(int pwmPortHandle) {
    Port p = port(pwmPortHandle);
    return p == null ? 0 : PwmData.get(p.channel).pulseMicrosecond.get();
  }

  public static void setPWMOutputPeriod(int pwmPortHandle, int period) {
    Port p = port(pwmPortHandle);
    if (p != null) {
      PwmData.get(p.channel).outputPeriod.set(period);
    }
  }
}
