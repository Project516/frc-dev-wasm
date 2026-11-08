package dev.project516.frcdevwasm.shim.vendor.revrobotics.jni;

import com.revrobotics.spark.config.SparkParameters;
import java.util.HashMap;
import java.util.Map;

/**
 * The parameters of one SPARK. Configs flatten to {@code id:value;} entries, and the type of a
 * parameter id decides how a value reads back.
 */
final class SparkParams {
  static final int INT32 = 1;
  static final int UINT32 = 2;
  static final int FLOAT = 3;
  static final int BOOL = 4;

  private static final Map<Integer, Integer> TYPES = new HashMap<>();

  static {
    for (SparkParameters p : SparkParameters.values()) {
      TYPES.put(
          p.value,
          switch (p.type) {
            case INT32 -> INT32;
            case UINT32 -> UINT32;
            case FLOAT -> FLOAT;
            case BOOL -> BOOL;
          });
    }
  }

  private final Map<Integer, Object> values = new HashMap<>();

  static int typeOf(int id) {
    return TYPES.getOrDefault(id, 0);
  }

  static String flatten(int id, Object value) {
    return id + ":" + value + ";";
  }

  private static Object defaultOf(int id) {
    int slotParam = id >= SparkParameters.kP_0.value && id <= SparkParameters.kOutputMax_3.value ? (id - SparkParameters.kP_0.value) % 8 : -1;
    return switch (typeOf(id)) {
      case FLOAT ->
          slotParam == 6
              ? -1f
              : slotParam == 7
                  ? 1f
                  : id == SparkParameters.kPositionConversionFactor.value || id == SparkParameters.kVelocityConversionFactor.value ? 1f : 0f;
      case BOOL -> false;
      default ->
          id == SparkParameters.kSmartCurrentStallLimit.value ? 80 : id == SparkParameters.kSmartCurrentFreeLimit.value ? 20 : 0;
    };
  }

  synchronized void reset() {
    values.clear();
  }

  synchronized void set(int id, Object value) {
    values.put(id, value);
  }

  synchronized Object get(int id) {
    Object v = values.get(id);
    return v == null ? defaultOf(id) : v;
  }

  float getFloat(int id) {
    return ((Number) get(id)).floatValue();
  }

  int getInt(int id) {
    return ((Number) get(id)).intValue();
  }

  boolean getBool(int id) {
    return (Boolean) get(id);
  }

  /** Applies flattened parameters, converting each to the type of its id. */
  void apply(String flattened) {
    for (String entry : flattened.split(";")) {
      int colon = entry.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      int id = Integer.parseInt(entry.substring(0, colon));
      String text = entry.substring(colon + 1);
      switch (typeOf(id)) {
        case FLOAT -> set(id, Float.parseFloat(text));
        case BOOL -> set(id, Boolean.parseBoolean(text));
        case INT32, UINT32 -> set(id, Integer.parseInt(text));
        default -> {}
      }
    }
  }
}
