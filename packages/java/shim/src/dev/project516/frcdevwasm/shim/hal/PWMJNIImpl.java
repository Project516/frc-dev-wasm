package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.DigitalPorts;
import dev.project516.frcdevwasm.sim.DigitalPorts.Port;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.PwmData;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;
import edu.wpi.first.hal.PWMConfigDataResult;
import edu.wpi.first.hal.ShimAccess;

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

  public static int initializePWMPort(int halPortHandle) {
    Sim.ensureInitialized();
    int channel = Handles.portChannel(halPortHandle);
    if (channel == -1) {
      Status.indexOutOfRange(
          Status.RESOURCE_OUT_OF_RANGE, "Invalid Index for PWM", 0, Ports.PWM_CHANNELS, channel);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    int origChannel = channel;
    if (origChannel < Ports.PWM_HEADERS) {
      channel += Ports.DIGITAL_CHANNELS;
    } else {
      channel = DigitalPorts.remapMxpPwmChannel(channel) + 10;
    }
    DigitalPorts.Allocation a = DigitalPorts.allocate(channel, Handles.PWM);
    if (a.status != 0) {
      if (a.port != null) {
        Status.previouslyAllocated(a.status, "PWM or DIO", channel, a.port.previousAllocation);
      } else {
        Status.indexOutOfRange(
            a.status, "Invalid Index for PWM", 0, Ports.PWM_CHANNELS, channel);
      }
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    a.port.channel = origChannel;
    PwmData.get(origChannel).initialized.set(true);
    setConfig(a.port, 2000, 1501, 1500, 1499, 1000);
    a.port.previousAllocation = Status.allocationLocation();
    return a.handle;
  }

  public static boolean checkPWMChannel(int channel) {
    return channel < Ports.PWM_CHANNELS && channel >= 0;
  }

  public static void freePWMPort(int pwmPortHandle) {
    if (pwmPortHandle == Handles.INVALID) {
      return;
    }
    Port p = DigitalPorts.get(pwmPortHandle, Handles.PWM);
    if (p == null) {
      return;
    }
    PwmData.get(p.channel).initialized.set(false);
    DigitalPorts.free(pwmPortHandle, Handles.PWM);
  }

  private static void setConfig(Port p, int max, int deadbandMax, int center, int deadbandMin, int min) {
    p.maxPwm = max;
    p.deadbandMaxPwm = deadbandMax;
    p.deadbandMinPwm = deadbandMin;
    p.centerPwm = center;
    p.minPwm = min;
    p.configSet = true;
  }

  public static void setPWMConfigMicroseconds(
      int pwmPortHandle, int max, int deadbandMax, int center, int deadbandMin, int min) {
    Port p = port(pwmPortHandle);
    if (p != null) {
      setConfig(p, max, deadbandMax, center, deadbandMin, min);
    }
  }

  public static PWMConfigDataResult getPWMConfigMicroseconds(int pwmPortHandle) {
    Port p = port(pwmPortHandle);
    if (p == null) {
      return ShimAccess.pwmConfigDataResult(0, 0, 0, 0, 0);
    }
    return ShimAccess.pwmConfigDataResult(
        p.maxPwm, p.deadbandMaxPwm, p.centerPwm, p.deadbandMinPwm, p.minPwm);
  }

  public static void setPWMEliminateDeadband(int pwmPortHandle, boolean eliminateDeadband) {
    Port p = port(pwmPortHandle);
    if (p != null) {
      p.eliminateDeadband = eliminateDeadband;
    }
  }

  public static boolean getPWMEliminateDeadband(int pwmPortHandle) {
    Port p = port(pwmPortHandle);
    return p != null && p.eliminateDeadband;
  }

  private static int maxPositive(Port p) {
    return p.maxPwm;
  }

  private static int minPositive(Port p) {
    return p.eliminateDeadband ? p.deadbandMaxPwm : p.centerPwm + 1;
  }

  private static int maxNegative(Port p) {
    return p.eliminateDeadband ? p.deadbandMinPwm : p.centerPwm - 1;
  }

  private static int minNegative(Port p) {
    return p.minPwm;
  }

  private static int positiveScale(Port p) {
    return maxPositive(p) - minPositive(p);
  }

  private static int negativeScale(Port p) {
    return maxNegative(p) - minNegative(p);
  }

  private static int fullRangeScale(Port p) {
    return maxPositive(p) - minNegative(p);
  }

  public static void setPulseTimeMicroseconds(int pwmPortHandle, int value) {
    Port p = port(pwmPortHandle);
    if (p != null) {
      setPulse(p, value);
    }
  }

  private static void setPulse(Port p, int value) {
    PwmData data = PwmData.get(p.channel);
    data.pulseMicrosecond.set(value);

    double speed;
    if (value == DigitalPorts.PWM_DISABLED) {
      speed = 0.0;
    } else if (value > maxPositive(p)) {
      speed = 1.0;
    } else if (value < minNegative(p)) {
      speed = -1.0;
    } else if (value > minPositive(p)) {
      speed = (double) (value - minPositive(p)) / (double) positiveScale(p);
    } else if (value < maxNegative(p)) {
      speed = (double) (value - maxNegative(p)) / (double) negativeScale(p);
    } else {
      speed = 0.0;
    }
    data.speed.set(speed);

    double pos;
    if (value < minNegative(p)) {
      pos = 0.0;
    } else if (value > maxPositive(p)) {
      pos = 1.0;
    } else {
      pos = (double) (value - minNegative(p)) / (double) fullRangeScale(p);
    }
    data.position.set(pos);
  }

  public static void setPWMSpeed(int pwmPortHandle, double speed) {
    Port p = port(pwmPortHandle);
    if (p == null) {
      return;
    }
    if (!p.configSet) {
      Status.check(Status.INCOMPATIBLE_STATE);
      return;
    }
    speed = Double.isFinite(speed) ? Math.max(-1.0, Math.min(1.0, speed)) : 0.0;

    int rawValue;
    if (speed == 0.0) {
      rawValue = p.centerPwm;
    } else if (speed > 0.0) {
      rawValue = (int) Math.round(speed * positiveScale(p) + minPositive(p));
    } else {
      rawValue = (int) Math.round(speed * negativeScale(p) + maxNegative(p));
    }
    if (!(rawValue >= minNegative(p) && rawValue <= maxPositive(p))
        || rawValue == DigitalPorts.PWM_DISABLED) {
      Status.check(Status.HAL_PWM_SCALE_ERROR);
      return;
    }
    setPulse(p, rawValue);
  }

  public static void setPWMPosition(int pwmPortHandle, double pos) {
    Port p = port(pwmPortHandle);
    if (p == null) {
      return;
    }
    if (!p.configSet) {
      Status.check(Status.INCOMPATIBLE_STATE);
      return;
    }
    pos = Math.max(0.0, Math.min(1.0, pos));
    int rawValue = (int) ((pos * fullRangeScale(p)) + minNegative(p));
    if (rawValue == DigitalPorts.PWM_DISABLED) {
      Status.check(Status.HAL_PWM_SCALE_ERROR);
      return;
    }
    setPulse(p, rawValue);
  }

  public static void setPWMDisabled(int pwmPortHandle) {
    Port p = port(pwmPortHandle);
    if (p != null) {
      PwmData data = PwmData.get(p.channel);
      data.pulseMicrosecond.set(0);
      data.position.set(0);
      data.speed.set(0);
    }
  }

  public static int getPulseTimeMicroseconds(int pwmPortHandle) {
    Port p = port(pwmPortHandle);
    return p == null ? 0 : PwmData.get(p.channel).pulseMicrosecond.get();
  }

  public static double getPWMSpeed(int pwmPortHandle) {
    Port p = port(pwmPortHandle);
    if (p == null) {
      return 0;
    }
    if (!p.configSet) {
      Status.check(Status.INCOMPATIBLE_STATE);
      return 0;
    }
    return Math.max(-1, Math.min(1, PwmData.get(p.channel).speed.get()));
  }

  public static double getPWMPosition(int pwmPortHandle) {
    Port p = port(pwmPortHandle);
    if (p == null) {
      return 0;
    }
    if (!p.configSet) {
      Status.check(Status.INCOMPATIBLE_STATE);
      return 0;
    }
    return Math.max(0, Math.min(1, PwmData.get(p.channel).position.get()));
  }

  public static void latchPWMZero(int pwmPortHandle) {
    Port p = port(pwmPortHandle);
    if (p != null) {
      PwmData data = PwmData.get(p.channel);
      data.zeroLatch.set(true);
      data.zeroLatch.set(false);
    }
  }

  public static void setAlwaysHighMode(int pwmPortHandle) {
    Port p = port(pwmPortHandle);
    if (p != null) {
      PwmData data = PwmData.get(p.channel);
      data.pulseMicrosecond.set(0xFFFF);
      data.position.set(0xFFFF);
      data.speed.set(0xFFFF);
    }
  }

  public static void setPWMPeriodScale(int pwmPortHandle, int squelchMask) {
    Port p = port(pwmPortHandle);
    if (p != null) {
      PwmData.get(p.channel).periodScale.set(squelchMask);
    }
  }
}
