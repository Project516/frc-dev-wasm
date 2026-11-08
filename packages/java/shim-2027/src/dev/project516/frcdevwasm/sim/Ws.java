package dev.project516.frcdevwasm.sim;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Bridge side of the HAL simulation, in the halsim_ws message format (docs/bridge-protocol.md).
 *
 * <p>A {@link Device} binds JSON keys to {@link SimValue}s. Keys starting with "&lt;" are robot
 * outputs and "&gt;" are robot inputs; "&lt;&gt;" is both. Outputs are sent as deltas while the
 * device is active. Inputs are applied when the page sends them. {@link #type} makes a message
 * type reachable before the robot creates the device.
 */
public final class Ws {
  private static final Map<String, Function<String, Device>> TYPES = new HashMap<>();
  private static final List<Device> DEVICES = new ArrayList<>();

  /** The value being set from a page message, so its change is not echoed back. */
  private static final ThreadLocal<SimValue> APPLYING = new ThreadLocal<>();

  private Ws() {}

  /**
   * Registers a message type. The resolver maps the message's device string to the Device,
   * creating it if needed, or returns null if there is no such device.
   */
  public static synchronized void type(String type, Function<String, Device> resolver) {
    if (TYPES.put(type, resolver) == null) {
      BridgeLoop.on(
          type,
          (t, device, data) -> {
            Function<String, Device> r;
            synchronized (Ws.class) {
              r = TYPES.get(t);
            }
            Device d = r.apply(device);
            if (d != null) {
              d.receive(data);
            } else {
              BridgeLoop.dispatchUnknown(t, device, data);
            }
          });
    }
  }

  /** Sends the full state of every active device. Run on hello. */
  public static void resendAll() {
    List<Device> all;
    synchronized (Ws.class) {
      all = new ArrayList<>(DEVICES);
    }
    for (Device d : all) {
      if (d.active()) {
        d.sendState();
      }
    }
  }

  private static synchronized void add(Device d) {
    DEVICES.add(d);
  }

  /** Parses a device id such as "3". Returns -1 if it is not an integer in [0, count). */
  public static int index(String id, int count) {
    try {
      int i = Integer.parseInt(id);
      return i >= 0 && i < count ? i : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** One halsim_ws device: a (type, device id) pair. */
  public static class Device {
    public final String type;
    public final String id;
    private final List<SimValue> gates = new ArrayList<>();
    private final Map<String, SimValue> keys = new LinkedHashMap<>();
    private final java.util.Set<String> echoed = new java.util.HashSet<>();

    /** A device that is always active until gates are added. */
    public Device(String type, String id) {
      this.type = type;
      this.id = id;
      add(this);
    }

    /** A device that is active while gate is true. The gate is bound as "&lt;init". */
    public Device(String type, String id, BoolValue gate) {
      this(type, id);
      gate("<init", gate);
    }

    /**
     * Adds an initialization flag. The device is active while any gate is true. When a gate turns
     * true the full state is sent; when it turns false only that key is sent.
     */
    public Device gate(String key, BoolValue gate) {
      gates.add(gate);
      keys.put(key, gate);
      attach(key, gate);
      return this;
    }

    /** Binds a key to a value. The key prefix gives the direction. */
    public Device bind(String key, SimValue value) {
      keys.put(key, value);
      attach(key, value);
      return this;
    }

    /**
     * Binds an input key that is also sent to the page when robot or test code changes it, as the
     * halsim_ws DriverStation provider does.
     */
    public Device bindEcho(String key, SimValue value) {
      echoed.add(key);
      return bind(key, value);
    }

    /** JSON for a key's value. Override for values that are not plain numbers or booleans. */
    protected Object json(String key, SimValue value) {
      return value.toJson();
    }

    private void attach(String key, SimValue value) {
      if (gates.contains(value)) {
        value.register(
            (name, v) -> {
              if (v.getBoolean()) {
                sendState();
              } else {
                BridgeLoop.sendDelta(type, id, key, false);
              }
            },
            false);
      } else if (key.startsWith("<") || echoed.contains(key)) {
        value.register(
            (name, v) -> {
              if (active() && APPLYING.get() != value) {
                BridgeLoop.sendDelta(type, id, key, json(key, value));
              }
            },
            false);
      }
    }

    /** Registers the callbacks again after the values were reset (which drops callbacks). */
    public void rebind() {
      for (Map.Entry<String, SimValue> e : keys.entrySet()) {
        attach(e.getKey(), e.getValue());
      }
    }

    /** True if the robot has initialized this device. */
    public boolean active() {
      if (gates.isEmpty()) {
        return true;
      }
      for (SimValue g : gates) {
        if (g instanceof BoolValue b && b.get()) {
          return true;
        }
      }
      return false;
    }

    /** Applies page inputs: keys with a "&gt;" or "&lt;&gt;" prefix that are bound. */
    public void receive(Map<String, Object> data) {
      for (Map.Entry<String, Object> e : data.entrySet()) {
        String key = e.getKey();
        SimValue v = keys.get(key);
        if (v != null && (key.startsWith(">") || key.startsWith("<>"))) {
          APPLYING.set(v);
          try {
            v.fromJson(e.getValue());
          } finally {
            APPLYING.remove();
          }
        }
      }
    }

    /** Current value of every bound key. */
    public Map<String, Object> state() {
      Map<String, Object> m = new LinkedHashMap<>();
      for (Map.Entry<String, SimValue> e : keys.entrySet()) {
        m.put(e.getKey(), json(e.getKey(), e.getValue()));
      }
      return m;
    }

    public void sendState() {
      BridgeLoop.sendDeltas(type, id, state());
    }
  }
}
