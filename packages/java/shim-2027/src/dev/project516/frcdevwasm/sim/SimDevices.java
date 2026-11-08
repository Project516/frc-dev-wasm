package dev.project516.frcdevwasm.sim;

import org.wpilib.hardware.hal.simulation.SimDeviceCallback;
import org.wpilib.hardware.hal.simulation.SimValueCallback;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SimDevice store, port of mockdata/SimDeviceData.cpp. Devices are named, hold typed values, and
 * notify created/freed/changed/reset callbacks. Handles: device = index + 1, value =
 * (device &lt;&lt; 16) | (index + 1).
 */
public final class SimDevices {
  public static final int INPUT = 0;
  public static final int OUTPUT = 1;
  public static final int BIDIR = 2;

  /** A HAL_Value: type plus the long or double payload. */
  public record Val(int type, long l, double d) {
    public static final Val UNASSIGNED = new Val(0, 0, 0);
  }

  private static final class Reg<T> {
    final int uid;
    final T callback;
    final String prefix;

    Reg(int uid, T callback, String prefix) {
      this.uid = uid;
      this.callback = callback;
      this.prefix = prefix;
    }
  }

  public static final class Value {
    public final String name;
    public final int direction;
    public final int handle;
    public Val value;
    public String[] enumOptions = new String[0];
    public double[] enumOptionValues = new double[0];
    final List<Reg<SimValueCallback>> changed = new ArrayList<>();
    final List<Reg<SimValueCallback>> reset = new ArrayList<>();

    Value(String name, int direction, int handle, Val value) {
      this.name = name;
      this.direction = direction;
      this.handle = handle;
      this.value = value;
    }
  }

  public static final class Device {
    public final String name;
    public final int handle;
    public final List<Value> values = new ArrayList<>();
    final Map<String, Value> valueMap = new HashMap<>();
    final List<Reg<SimValueCallback>> valueCreated = new ArrayList<>();

    Device(String name, int handle) {
      this.name = name;
      this.handle = handle;
    }
  }

  private static final SimDevices INSTANCE = new SimDevices();

  public static SimDevices get() {
    return INSTANCE;
  }

  private final List<Device> devices = new ArrayList<>();
  private final Map<String, Device> deviceMap = new HashMap<>();
  private final List<String[]> prefixEnabled = new ArrayList<>();
  private final List<Boolean> prefixEnabledValue = new ArrayList<>();
  private final List<Reg<SimDeviceCallback>> deviceCreated = new ArrayList<>();
  private final List<Reg<SimDeviceCallback>> deviceFreed = new ArrayList<>();
  private int nextUid = 1;

  private SimDevices() {}

  private Device lookupDevice(int handle) {
    if (handle <= 0 || handle > devices.size()) {
      return null;
    }
    return devices.get(handle - 1);
  }

  private Value lookupValue(int handle) {
    if (handle <= 0) {
      return null;
    }
    Device d = lookupDevice(handle >> 16);
    if (d == null) {
      return null;
    }
    int index = (handle & 0xffff) - 1;
    return index < 0 || index >= d.values.size() ? null : d.values.get(index);
  }

  public synchronized void setDeviceEnabled(String prefix, boolean enabled) {
    for (int i = 0; i < prefixEnabled.size(); i++) {
      if (prefixEnabled.get(i)[0].equals(prefix)) {
        prefixEnabledValue.set(i, enabled);
        return;
      }
    }
    // Longer prefixes first so the most specific one wins.
    int at = 0;
    while (at < prefixEnabled.size() && prefixEnabled.get(at)[0].compareTo(prefix) > 0) {
      at++;
    }
    prefixEnabled.add(at, new String[] {prefix});
    prefixEnabledValue.add(at, enabled);
  }

  public synchronized boolean isDeviceEnabled(String name) {
    for (int i = 0; i < prefixEnabled.size(); i++) {
      if (name.startsWith(prefixEnabled.get(i)[0])) {
        return prefixEnabledValue.get(i);
      }
    }
    return true;
  }

  public int createDevice(String name) {
    Sim.ensureInitialized();
    Device d;
    List<Reg<SimDeviceCallback>> cbs;
    synchronized (this) {
      if (!isDeviceEnabled(name) || deviceMap.containsKey(name) || devices.size() >= 4095) {
        return 0;
      }
      int slot = devices.indexOf(null);
      if (slot < 0) {
        slot = devices.size();
        devices.add(null);
      }
      d = new Device(name, slot + 1);
      devices.set(slot, d);
      deviceMap.put(name, d);
      cbs = new ArrayList<>(deviceCreated);
    }
    for (Reg<SimDeviceCallback> r : cbs) {
      if (name.startsWith(r.prefix)) {
        r.callback.callback(name, d.handle);
      }
    }
    return d.handle;
  }

  public void freeDevice(int handle) {
    Device d;
    List<Reg<SimDeviceCallback>> cbs;
    synchronized (this) {
      d = lookupDevice(handle);
      if (d == null) {
        return;
      }
      devices.set(handle - 1, null);
      deviceMap.remove(d.name);
      cbs = new ArrayList<>(deviceFreed);
    }
    for (Reg<SimDeviceCallback> r : cbs) {
      if (d.name.startsWith(r.prefix)) {
        r.callback.callback(d.name, handle);
      }
    }
  }

  public synchronized String getDeviceName(int handle) {
    Device d = lookupDevice(handle);
    return d == null ? null : d.name;
  }

  public int createValue(
      int device, String name, int direction, String[] options, double[] optionValues, Val initial) {
    Device d;
    Value v;
    List<Reg<SimValueCallback>> cbs;
    synchronized (this) {
      d = lookupDevice(device);
      if (d == null || d.valueMap.containsKey(name) || d.values.size() >= 4095) {
        return 0;
      }
      int handle = (device << 16) | (d.values.size() + 1);
      v = new Value(name, direction, handle, initial);
      if (options != null) {
        v.enumOptions = options.clone();
      }
      if (options != null && optionValues != null) {
        v.enumOptionValues = optionValues.clone();
      }
      d.values.add(v);
      d.valueMap.put(name, v);
      cbs = new ArrayList<>(d.valueCreated);
    }
    for (Reg<SimValueCallback> r : cbs) {
      call(r.callback, v, initial);
    }
    return v.handle;
  }

  private static void call(SimValueCallback cb, Value v, Val val) {
    cb.callbackNative(v.name, v.handle, v.direction, val.type(), val.l(), val.d());
  }

  public synchronized Val getValue(int handle) {
    Value v = lookupValue(handle);
    return v == null ? Val.UNASSIGNED : v.value;
  }

  /** HAL_SetSimValue: stores the value and always notifies, even if unchanged. */
  public void setValue(int handle, Val value) {
    Value v;
    List<Reg<SimValueCallback>> cbs;
    synchronized (this) {
      v = lookupValue(handle);
      if (v == null) {
        return;
      }
      v.value = value;
      cbs = new ArrayList<>(v.changed);
    }
    for (Reg<SimValueCallback> r : cbs) {
      call(r.callback, v, value);
    }
  }

  /** HAL_ResetSimValue: int, long and double values go back to 0. */
  public void resetValue(int handle) {
    Value v;
    List<Reg<SimValueCallback>> resets;
    List<Reg<SimValueCallback>> changes;
    Val old;
    Val zero;
    synchronized (this) {
      v = lookupValue(handle);
      if (v == null) {
        return;
      }
      int t = v.value.type();
      if (t != SimValue.INT && t != SimValue.LONG && t != SimValue.DOUBLE) {
        return;
      }
      old = v.value;
      zero = new Val(t, 0, 0);
      resets = new ArrayList<>(v.reset);
      changes = new ArrayList<>(v.changed);
    }
    for (Reg<SimValueCallback> r : resets) {
      call(r.callback, v, old);
    }
    synchronized (this) {
      v.value = zero;
    }
    for (Reg<SimValueCallback> r : changes) {
      call(r.callback, v, zero);
    }
  }

  public int registerDeviceCreatedCallback(
      String prefix, SimDeviceCallback callback, boolean initialNotify) {
    int uid;
    List<Device> existing = new ArrayList<>();
    synchronized (this) {
      uid = nextUid++;
      deviceCreated.add(new Reg<>(uid, callback, prefix));
      for (Device d : devices) {
        if (d != null && d.name.startsWith(prefix)) {
          existing.add(d);
        }
      }
    }
    if (initialNotify) {
      for (Device d : existing) {
        callback.callback(d.name, d.handle);
      }
    }
    return uid;
  }

  public synchronized int registerDeviceFreedCallback(String prefix, SimDeviceCallback callback) {
    int uid = nextUid++;
    deviceFreed.add(new Reg<>(uid, callback, prefix));
    return uid;
  }

  public int registerValueCreatedCallback(
      int device, SimValueCallback callback, boolean initialNotify) {
    int uid;
    List<Value> existing;
    synchronized (this) {
      Device d = lookupDevice(device);
      if (d == null) {
        return -1;
      }
      uid = nextUid++;
      d.valueCreated.add(new Reg<>(uid, callback, ""));
      existing = new ArrayList<>(d.values);
    }
    if (initialNotify) {
      for (Value v : existing) {
        call(callback, v, v.value);
      }
    }
    return uid;
  }

  public int registerValueChangedCallback(
      int handle, SimValueCallback callback, boolean initialNotify) {
    Value v;
    int uid;
    Val current;
    synchronized (this) {
      v = lookupValue(handle);
      if (v == null) {
        return -1;
      }
      uid = nextUid++;
      v.changed.add(new Reg<>(uid, callback, ""));
      current = v.value;
    }
    if (initialNotify) {
      call(callback, v, current);
    }
    return uid;
  }

  public synchronized int registerValueResetCallback(int handle, SimValueCallback callback) {
    Value v = lookupValue(handle);
    if (v == null) {
      return -1;
    }
    int uid = nextUid++;
    v.reset.add(new Reg<>(uid, callback, ""));
    return uid;
  }

  /** Cancels any callback registered through this store. */
  public synchronized void cancelCallback(int uid) {
    if (uid <= 0) {
      return;
    }
    deviceCreated.removeIf(r -> r.uid == uid);
    deviceFreed.removeIf(r -> r.uid == uid);
    for (Device d : devices) {
      if (d == null) {
        continue;
      }
      d.valueCreated.removeIf(r -> r.uid == uid);
      for (Value v : d.values) {
        v.changed.removeIf(r -> r.uid == uid);
        v.reset.removeIf(r -> r.uid == uid);
      }
    }
  }

  public synchronized int getDeviceHandle(String name) {
    Device d = deviceMap.get(name);
    return d == null ? 0 : d.handle;
  }

  public synchronized int getValueHandle(int device, String name) {
    Device d = lookupDevice(device);
    if (d == null) {
      return 0;
    }
    Value v = d.valueMap.get(name);
    return v == null ? 0 : v.handle;
  }

  public static int valueDeviceHandle(int valueHandle) {
    return valueHandle <= 0 ? 0 : valueHandle >> 16;
  }

  public synchronized List<Device> enumerateDevices(String prefix) {
    List<Device> out = new ArrayList<>();
    for (Device d : devices) {
      if (d != null && d.name.startsWith(prefix)) {
        out.add(d);
      }
    }
    return out;
  }

  public synchronized List<Value> enumerateValues(int device) {
    Device d = lookupDevice(device);
    return d == null ? new ArrayList<>() : new ArrayList<>(d.values);
  }

  public synchronized Value value(int handle) {
    return lookupValue(handle);
  }

  public synchronized Device device(String name) {
    return deviceMap.get(name);
  }

  /** ResetData: forgets devices and callbacks. */
  public synchronized void resetData() {
    devices.clear();
    deviceMap.clear();
    prefixEnabled.clear();
    prefixEnabledValue.clear();
    deviceCreated.clear();
    deviceFreed.clear();
  }
}
