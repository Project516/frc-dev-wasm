package dev.project516.frcdevwasm.sim;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.Json;
import dev.project516.frcdevwasm.sim.SimDevices.Val;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge side of SimDevices, as halsim_ws WSProvider_SimDevice. A device named "Type:Id" is
 * message type "Type", device "Id"; a name without a colon is type "SimDevice". Value keys carry
 * the direction prefix. Any message type nobody else handles is looked up here.
 */
public final class SimDevicesWs {
  private static final class ValueData {
    int handle;
    int type;
    String[] options = new String[0];
    double[] optionValues = new double[0];
    double doubleOffset;
    long intOffset;
  }

  private static final class Dev {
    final String type;
    final String id;
    final int handle;
    final Map<String, ValueData> values = new HashMap<>();

    Dev(String type, String id, int handle) {
      this.type = type;
      this.id = id;
      this.handle = handle;
    }
  }

  private static final Map<String, Dev> DEVICES = new HashMap<>();
  private static final ThreadLocal<Integer> APPLYING = new ThreadLocal<>();

  private SimDevicesWs() {}

  /** Starts mirroring devices to the page. */
  static void start() {
    BridgeLoop.onUnknown(SimDevicesWs::receive);
    BridgeLoop.onHello(SimDevicesWs::resend);
    register();
  }

  private static void register() {
    SimDevices.get().registerDeviceCreatedCallback("", SimDevicesWs::deviceCreated, true);
    SimDevices.get()
        .registerDeviceFreedCallback(
            "",
            (name, handle) -> {
              synchronized (DEVICES) {
                DEVICES.remove(key(name));
              }
            });
  }

  /** Registers again after SimDevices.resetData dropped all callbacks and devices. */
  public static void rebind() {
    synchronized (DEVICES) {
      DEVICES.clear();
    }
    register();
  }

  private static String key(String deviceName) {
    int colon = deviceName.indexOf(':');
    return colon < 0 ? "SimDevice\u0000" + deviceName : deviceName.substring(0, colon) + "\u0000" + deviceName.substring(colon + 1);
  }

  private static void deviceCreated(String name, int handle) {
    int colon = name.indexOf(':');
    Dev dev =
        colon < 0
            ? new Dev("SimDevice", name, handle)
            : new Dev(name.substring(0, colon), name.substring(colon + 1), handle);
    synchronized (DEVICES) {
      DEVICES.put(dev.type + "\u0000" + dev.id, dev);
    }
    SimDevices.get().registerValueCreatedCallback(handle, (n, vh, dir, v) -> valueCreated(dev, n, vh, dir, v), true);
  }

  private static String prefix(String name, int direction) {
    if (name.startsWith("<") || name.startsWith(">")) {
      return "";
    }
    return switch (direction) {
      case SimDevices.INPUT -> ">";
      case SimDevices.OUTPUT -> "<";
      case SimDevices.BIDIR -> "<>";
      default -> "";
    };
  }

  private static void valueCreated(
      Dev dev, String name, int handle, int direction, org.wpilib.hardware.hal.HALValue value) {
    String key = prefix(name, direction) + name;
    ValueData data = new ValueData();
    data.handle = handle;
    data.type = value.getType();
    SimDevices.Value sv = SimDevices.get().value(handle);
    if (data.type == SimValue.ENUM && sv != null) {
      data.options = sv.enumOptions;
      data.optionValues = sv.enumOptionValues;
    }
    synchronized (DEVICES) {
      dev.values.put(key, data);
    }
    SimDevices.get()
        .registerValueResetCallback(
            handle,
            (n, h, d, v) -> {
              switch (v.getType()) {
                case SimValue.DOUBLE -> data.doubleOffset += v.getDouble();
                case SimValue.INT, SimValue.LONG -> data.intOffset += v.getLong();
                default -> {}
              }
            });
    SimDevices.get()
        .registerValueChangedCallback(
            handle,
            (n, h, d, v) -> {
              Object json = toJson(data, v);
              Integer applying = APPLYING.get();
              if (json != null && (applying == null || applying != h)) {
                BridgeLoop.sendDelta(dev.type, dev.id, key, json);
              }
            },
            true);
  }

  private static Object toJson(ValueData data, org.wpilib.hardware.hal.HALValue v) {
    switch (v.getType()) {
      case SimValue.BOOLEAN:
        return v.getBoolean();
      case SimValue.DOUBLE:
        return v.getDouble() + data.doubleOffset;
      case SimValue.ENUM:
        int e = (int) v.getLong();
        if (e >= 0 && e < data.optionValues.length) {
          return data.optionValues[e];
        }
        return e >= 0 && e < data.options.length ? data.options[e] : null;
      case SimValue.INT:
      case SimValue.LONG:
        return v.getLong() + data.intOffset;
      default:
        return null;
    }
  }

  private static void receive(String type, String device, Map<String, Object> data) {
    Dev dev;
    synchronized (DEVICES) {
      dev = DEVICES.get(type + "\u0000" + device);
    }
    if (dev == null) {
      return;
    }
    for (Map.Entry<String, Object> e : data.entrySet()) {
      ValueData vd;
      synchronized (DEVICES) {
        vd = dev.values.get(e.getKey());
      }
      if (vd == null) {
        continue;
      }
      Val val = fromJson(vd, e.getValue());
      if (val != null) {
        APPLYING.set(vd.handle);
        try {
          SimDevices.get().setValue(vd.handle, val);
        } finally {
          APPLYING.remove();
        }
      }
    }
  }

  private static Val fromJson(ValueData vd, Object json) {
    switch (vd.type) {
      case SimValue.BOOLEAN:
        return new Val(vd.type, Json.toBool(json) ? 1 : 0, 0);
      case SimValue.DOUBLE:
        return new Val(vd.type, 0, Json.toDouble(json) - vd.doubleOffset);
      case SimValue.ENUM:
        if (json instanceof String s) {
          for (int i = 0; i < vd.options.length; i++) {
            if (vd.options[i].equals(s)) {
              return new Val(vd.type, i, 0);
            }
          }
          return null;
        }
        double num = Json.toDouble(json);
        for (int i = 0; i < vd.optionValues.length; i++) {
          if (Math.abs(vd.optionValues[i] - num) < 1e-4) {
            return new Val(vd.type, i, 0);
          }
        }
        return new Val(vd.type, (long) num, 0);
      case SimValue.INT:
      case SimValue.LONG:
        return new Val(vd.type, Json.toLong(json) - vd.intOffset, 0);
      default:
        return null;
    }
  }

  /** Sends every value of every device, on hello. */
  private static void resend() {
    List<Dev> all;
    synchronized (DEVICES) {
      all = new ArrayList<>(DEVICES.values());
    }
    for (Dev dev : all) {
      Map<String, Object> state = new LinkedHashMap<>();
      List<Map.Entry<String, ValueData>> entries;
      synchronized (DEVICES) {
        entries = new ArrayList<>(dev.values.entrySet());
      }
      for (Map.Entry<String, ValueData> e : entries) {
        SimDevices.Val v = SimDevices.get().getValue(e.getValue().handle);
        Object json =
            toJson(e.getValue(), org.wpilib.hardware.hal.HALValue.fromNative(v.type(), v.l(), v.d()));
        if (json != null) {
          state.put(e.getKey(), json);
        }
      }
      if (!state.isEmpty()) {
        BridgeLoop.sendDeltas(dev.type, dev.id, state);
      }
    }
  }
}
