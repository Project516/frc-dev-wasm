package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.configs.ffi;

import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.ffi.Ffi;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.configs.jni.ConfigJNIImpl;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.Config;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.Device;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.PhoenixSim;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

/** Configs apply to the simulated device and read back, including defaults. */
public final class ConfigNativeImpl {
  private static String lastParsed;
  private static Map<Integer, Double> lastEntries = Map.of();

  private ConfigNativeImpl() {}

  private static MemorySegment text(String value) {
    return Ffi.allocate(value);
  }

  public static int c_ctre_phoenix6_serialize_double(int spn, double value, MemorySegment out) {
    out.set(ValueLayout.ADDRESS, 0, text(Config.entry(spn, value)));
    return 0;
  }

  public static int c_ctre_phoenix6_serialize_int(int spn, int value, MemorySegment out) {
    out.set(ValueLayout.ADDRESS, 0, text(Config.entry(spn, value)));
    return 0;
  }

  public static int c_ctre_phoenix6_serialize_bool(int spn, boolean value, MemorySegment out) {
    out.set(ValueLayout.ADDRESS, 0, text(Config.entry(spn, value ? 1 : 0)));
    return 0;
  }

  private static synchronized double entry(int spn, MemorySegment serialized) {
    String str = Ffi.string(serialized);
    if (!str.equals(lastParsed)) {
      lastEntries = Config.parse(str);
      lastParsed = str;
    }
    return lastEntries.getOrDefault(spn, 0.0);
  }

  public static int c_ctre_phoenix6_deserialize_double(int spn, MemorySegment serialized, int length, MemorySegment out) {
    out.set(ValueLayout.JAVA_DOUBLE, 0, entry(spn, serialized));
    return 0;
  }

  public static int c_ctre_phoenix6_deserialize_int(int spn, MemorySegment serialized, int length, MemorySegment out) {
    out.set(ValueLayout.JAVA_INT, 0, (int) Math.round(entry(spn, serialized)));
    return 0;
  }

  public static int c_ctre_phoenix6_deserialize_bool(int spn, MemorySegment serialized, int length, MemorySegment out) {
    out.set(ValueLayout.JAVA_BOOLEAN, 0, entry(spn, serialized) != 0);
    return 0;
  }

  public static int c_ctre_phoenix6_set_configs(
      int reserved,
      MemorySegment network,
      int deviceHash,
      double timeoutSeconds,
      MemorySegment serialized,
      int length,
      boolean futureProofConfigs,
      boolean overrideIfDuplicate,
      boolean udpOnly) {
    Device device = ConfigJNIImpl.deviceOf(deviceHash);
    if (device != null) {
      synchronized (PhoenixSim.LOCK) {
        device.apply(Config.parse(Ffi.string(serialized)));
      }
    }
    return 0;
  }

  public static int c_ctre_phoenix6_get_configs(
      int reserved, MemorySegment network, int deviceHash, double timeoutSeconds, MemorySegment out, boolean udpOnly) {
    Device device = ConfigJNIImpl.deviceOf(deviceHash);
    if (device != null) {
      synchronized (PhoenixSim.LOCK) {
        out.set(ValueLayout.ADDRESS, 0, text(device.config.serialize()));
      }
    }
    return 0;
  }
}
