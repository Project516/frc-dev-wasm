package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim;

import com.ctre.phoenix6.spns.SpnValue;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Device configs by SPN. Configs serialize as {@code spn=value;} entries. A device that was never
 * configured reads as the default config of its model.
 */
public final class Config {
  private static final Map<String, Integer> BY_NAME = new HashMap<>();
  private static final Map<Integer, String> BY_VALUE = new HashMap<>();
  private static final Map<String, Map<Integer, Double>> DEFAULTS = new ConcurrentHashMap<>();

  static {
    for (SpnValue spn : SpnValue.values()) {
      BY_NAME.put(spn.name(), spn.value);
      BY_VALUE.put(spn.value, spn.name());
    }
  }

  private static final String CLASS_PREFIX = "com.ctre.phoenix6.configs.";

  final String model;
  final Map<Integer, Double> values = new HashMap<>();

  Config(String model) {
    this.model = model;
  }

  static String nameOf(int spn) {
    return BY_VALUE.get(spn);
  }

  static boolean isAction(String name) {
    return name.equals("TalonFX_SetSensorPosition")
        || name.equals("CANcoder_SetSensorPosition")
        || name.equals("Pigeon2_SetYaw")
        || name.startsWith("ClearStickyFault_")
        || name.equals("SPN_ClearStickyFaults");
  }

  /** The config value of an SPN: what was applied, else the model default, else zero. */
  public double get(String name) {
    Integer spn = BY_NAME.get(name);
    if (spn == null) {
      return 0;
    }
    Double v = values.get(spn);
    if (v == null) {
      v = defaults(model).get(spn);
    }
    return v == null ? 0 : v;
  }

  public boolean flag(String name) {
    return get(name) != 0;
  }

  /** The whole config as a serialized string. */
  public String serialize() {
    Map<Integer, Double> all = new HashMap<>(defaults(model));
    all.putAll(values);
    StringBuilder sb = new StringBuilder();
    all.forEach((spn, v) -> sb.append(entry(spn, v)));
    return sb.toString();
  }

  private static String configClass(String model) {
    return switch (model) {
      case "talon fx" -> "TalonFXConfiguration";
      case "cancoder" -> "CANcoderConfiguration";
      case "pigeon 2" -> "Pigeon2Configuration";
      case "talon fxs" -> "TalonFXSConfiguration";
      case "canrange" -> "CANrangeConfiguration";
      case "candi" -> "CANdiConfiguration";
      case "candle" -> "CANdleConfiguration";
      default -> null;
    };
  }

  private static Map<Integer, Double> defaults(String model) {
    Map<Integer, Double> cached = DEFAULTS.get(model);
    if (cached != null) {
      return cached;
    }
    Map<Integer, Double> parsed = new HashMap<>();
    String name = configClass(model);
    if (name != null) {
      try {
        Object config = Class.forName(CLASS_PREFIX + name).getDeclaredConstructor().newInstance();
        parsed = parse((String) config.getClass().getMethod("serialize").invoke(config));
      } catch (ReflectiveOperationException | LinkageError e) {
        // the vendor version has no such config class
      }
    }
    DEFAULTS.put(model, parsed);
    return parsed;
  }

  public static Map<Integer, Double> parse(String serialized) {
    Map<Integer, Double> out = new HashMap<>();
    int start = 0;
    while (start < serialized.length()) {
      int end = serialized.indexOf(';', start);
      if (end < 0) {
        break;
      }
      int eq = serialized.indexOf('=', start);
      if (eq > start && eq < end) {
        try {
          out.put(Integer.parseInt(serialized, start, eq, 10), Double.parseDouble(serialized.substring(eq + 1, end)));
        } catch (NumberFormatException e) {
          // skip a malformed entry
        }
      }
      start = end + 1;
    }
    return out;
  }

  public static String entry(int spn, double value) {
    return spn + "=" + value + ";";
  }
}
