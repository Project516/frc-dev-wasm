package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim;

import java.util.HashMap;
import java.util.Map;

/** A device whose firmware is not emulated: physics inputs read back and signals read zero. */
public class Device {
  public final String model;
  public final int id;
  public final Config config;
  private final Map<String, Double> inputs = new HashMap<>();

  Device(String model, int id) {
    this.model = model;
    this.id = id;
    this.config = new Config(model);
  }

  /** A physics input written by a sim state class. */
  public void setInput(String name, double value) {
    inputs.put(name, value);
  }

  /** A physics value read by a sim state class. */
  public double getValue(String name) {
    return inputs.getOrDefault(name, 0.0);
  }

  /** The value of a status signal by SPN name, or NaN when the signal is not emulated. */
  public double signal(String spn) {
    return Double.NaN;
  }

  /** Called after a config apply, so the device can cache what it needs. */
  protected void configChanged() {}

  /** A config write that is an action, like setting the position. */
  protected void action(String spn, double value) {}

  /** Applies a serialized config; action SPNs run instead of being stored. */
  public void apply(Map<Integer, Double> entries) {
    for (Map.Entry<Integer, Double> e : entries.entrySet()) {
      String name = Config.nameOf(e.getKey());
      if (name != null && Config.isAction(name)) {
        action(name, e.getValue());
      } else {
        config.values.put(e.getKey(), e.getValue());
      }
    }
    configChanged();
  }
}
