package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.SimDevices;
import dev.project516.frcdevwasm.sim.SimDevices.Val;
import dev.project516.frcdevwasm.sim.SimValue;
import org.wpilib.hardware.hal.HALValue;

/** SimDevice creation and value access (sim/SimDevice.cpp, SimDeviceJNI.cpp). */
public final class SimDeviceJNIImpl {
  private SimDeviceJNIImpl() {}

  public static int createSimDevice(String name) {
    return SimDevices.get().createDevice(name);
  }

  public static void freeSimDevice(int handle) {
    if (handle != 0) {
      SimDevices.get().freeDevice(handle);
    }
  }

  public static String getSimDeviceName(int handle) {
    return SimDevices.get().getDeviceName(handle);
  }

  public static int createSimValueNative(
      int device, String name, int direction, int type, long value1, double value2) {
    return SimDevices.get().createValue(device, name, direction, null, null, new Val(type, value1, value2));
  }

  public static int createSimValueEnum(
      int device, String name, int direction, String[] options, int initialValue) {
    for (String o : options) {
      if (o == null) {
        return 0;
      }
    }
    return SimDevices.get()
        .createValue(device, name, direction, options, null, new Val(SimValue.ENUM, initialValue, 0));
  }

  public static int createSimValueEnumDouble(
      int device,
      String name,
      int direction,
      String[] options,
      double[] optionValues,
      int initialValue) {
    if (options.length != optionValues.length) {
      return 0;
    }
    for (String o : options) {
      if (o == null) {
        return 0;
      }
    }
    return SimDevices.get()
        .createValue(
            device, name, direction, options, optionValues, new Val(SimValue.ENUM, initialValue, 0));
  }

  public static HALValue getSimValue(int handle) {
    Val v = SimDevices.get().getValue(handle);
    return HALValue.fromNative(v.type(), v.l(), v.d());
  }

  public static int getSimValueInt(int handle) {
    Val v = SimDevices.get().getValue(handle);
    return v.type() == SimValue.DOUBLE ? 0 : (int) v.l();
  }

  public static long getSimValueLong(int handle) {
    Val v = SimDevices.get().getValue(handle);
    return v.type() == SimValue.DOUBLE ? 0 : v.l();
  }

  public static double getSimValueDouble(int handle) {
    return SimDevices.get().getValue(handle).d();
  }

  public static int getSimValueEnum(int handle) {
    return getSimValueInt(handle);
  }

  public static boolean getSimValueBoolean(int handle) {
    Val v = SimDevices.get().getValue(handle);
    return v.type() != SimValue.DOUBLE && (int) v.l() != 0;
  }

  public static void setSimValueNative(int handle, int type, long value1, double value2) {
    SimDevices.get().setValue(handle, new Val(type, value1, value2));
  }

  public static void resetSimValue(int handle) {
    SimDevices.get().resetValue(handle);
  }
}
