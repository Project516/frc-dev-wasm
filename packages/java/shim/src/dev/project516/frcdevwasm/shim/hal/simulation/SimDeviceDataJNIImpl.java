package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.SimDevices;
import dev.project516.frcdevwasm.sim.SimDevices.Device;
import dev.project516.frcdevwasm.sim.SimDevices.Value;
import dev.project516.frcdevwasm.sim.SimDevicesWs;
import edu.wpi.first.hal.simulation.SimDeviceCallback;
import edu.wpi.first.hal.simulation.SimDeviceDataJNI.SimDeviceInfo;
import edu.wpi.first.hal.simulation.SimDeviceDataJNI.SimValueInfo;
import edu.wpi.first.hal.simulation.SimValueCallback;
import java.util.List;

/** HALSIM SimDevice data access (SimDeviceDataJNI.cpp). */
public final class SimDeviceDataJNIImpl {
  private SimDeviceDataJNIImpl() {}

  public static void setSimDeviceEnabled(String prefix, boolean enabled) {
    SimDevices.get().setDeviceEnabled(prefix, enabled);
  }

  public static boolean isSimDeviceEnabled(String name) {
    return SimDevices.get().isDeviceEnabled(name);
  }

  public static int registerSimDeviceCreatedCallback(
      String prefix, SimDeviceCallback callback, boolean initialNotify) {
    return SimDevices.get().registerDeviceCreatedCallback(prefix, callback, initialNotify);
  }

  public static void cancelSimDeviceCreatedCallback(int uid) {
    SimDevices.get().cancelCallback(uid);
  }

  public static int registerSimDeviceFreedCallback(
      String prefix, SimDeviceCallback callback, boolean initialNotify) {
    return SimDevices.get().registerDeviceFreedCallback(prefix, callback);
  }

  public static void cancelSimDeviceFreedCallback(int uid) {
    SimDevices.get().cancelCallback(uid);
  }

  public static int getSimDeviceHandle(String name) {
    return SimDevices.get().getDeviceHandle(name);
  }

  public static String getSimDeviceName(int handle) {
    return SimDevices.get().getDeviceName(handle);
  }

  public static int getSimValueDeviceHandle(int handle) {
    return SimDevices.valueDeviceHandle(handle);
  }

  public static SimDeviceInfo[] enumerateSimDevices(String prefix) {
    List<Device> devices = SimDevices.get().enumerateDevices(prefix);
    SimDeviceInfo[] out = new SimDeviceInfo[devices.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = new SimDeviceInfo(devices.get(i).name, devices.get(i).handle);
    }
    return out;
  }

  public static int registerSimValueCreatedCallback(
      int device, SimValueCallback callback, boolean initialNotify) {
    return SimDevices.get().registerValueCreatedCallback(device, callback, initialNotify);
  }

  public static void cancelSimValueCreatedCallback(int uid) {
    SimDevices.get().cancelCallback(uid);
  }

  public static int registerSimValueChangedCallback(
      int handle, SimValueCallback callback, boolean initialNotify) {
    return SimDevices.get().registerValueChangedCallback(handle, callback, initialNotify);
  }

  public static void cancelSimValueChangedCallback(int uid) {
    SimDevices.get().cancelCallback(uid);
  }

  public static int registerSimValueResetCallback(
      int handle, SimValueCallback callback, boolean initialNotify) {
    return SimDevices.get().registerValueResetCallback(handle, callback);
  }

  public static void cancelSimValueResetCallback(int uid) {
    SimDevices.get().cancelCallback(uid);
  }

  public static int getSimValueHandle(int device, String name) {
    return SimDevices.get().getValueHandle(device, name);
  }

  public static SimValueInfo[] enumerateSimValues(int device) {
    List<Value> values = SimDevices.get().enumerateValues(device);
    SimValueInfo[] out = new SimValueInfo[values.size()];
    for (int i = 0; i < out.length; i++) {
      Value v = values.get(i);
      out[i] = new SimValueInfo(v.name, v.handle, v.direction, v.value.type(), v.value.l(), v.value.d());
    }
    return out;
  }

  public static String[] getSimValueEnumOptions(int handle) {
    Value v = SimDevices.get().value(handle);
    return v == null ? new String[0] : v.enumOptions.clone();
  }

  public static double[] getSimValueEnumDoubleValues(int handle) {
    Value v = SimDevices.get().value(handle);
    return v == null ? new double[0] : v.enumOptionValues.clone();
  }

  public static void resetSimDeviceData() {
    SimDevices.get().resetData();
    SimDevicesWs.rebind();
  }
}
