package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.configs.jni;

import com.ctre.phoenix6.configs.jni.ConfigJNI;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.hardware.jni.HardwareJNIImpl;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.Config;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.Device;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.PhoenixSim;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Configs apply to the simulated device and read back, including defaults. Phoenix 6 26.1 passes the
 * CAN bus as a string and later versions as a buffer, so both overloads exist.
 */
public final class ConfigJNIImpl {
  private static String lastParsed;
  private static Map<Integer, Double> lastEntries = Map.of();

  private ConfigJNIImpl() {}

  public static String Serializedouble(int spn, double val) {
    return Config.entry(spn, val);
  }

  public static String Serializeint(int spn, int val) {
    return Config.entry(spn, val);
  }

  public static String Serializeboolean(int spn, boolean val) {
    return Config.entry(spn, val ? 1 : 0);
  }

  private static synchronized double entry(int spn, String str) {
    if (!str.equals(lastParsed)) {
      lastEntries = Config.parse(str);
      lastParsed = str;
    }
    return lastEntries.getOrDefault(spn, 0.0);
  }

  public static double Deserializedouble(int spn, String str) {
    return entry(spn, str);
  }

  public static int Deserializeint(int spn, String str) {
    return (int) Math.round(entry(spn, str));
  }

  public static boolean Deserializeboolean(int spn, String str) {
    return entry(spn, str) != 0;
  }

  public static int SetConfigs(
      ConfigJNI self, ByteBuffer network, int deviceHash, double timeoutSeconds, boolean futureProofConfigs, boolean overrideIfDuplicate) {
    return set(self, deviceHash);
  }

  public static int SetConfigs(
      ConfigJNI self, String network, int deviceHash, double timeoutSeconds, boolean futureProofConfigs, boolean overrideIfDuplicate) {
    return set(self, deviceHash);
  }

  public static int GetConfigs(ConfigJNI self, ByteBuffer network, int deviceHash, double timeoutSeconds) {
    return get(self, deviceHash);
  }

  public static int GetConfigs(ConfigJNI self, String network, int deviceHash, double timeoutSeconds) {
    return get(self, deviceHash);
  }

  /** The simulated device behind a device hash, or null for an unknown hash. */
  public static Device deviceOf(int deviceHash) {
    HardwareJNIImpl.Device hw = HardwareJNIImpl.deviceOf(deviceHash);
    return hw == null ? null : PhoenixSim.device(PhoenixSim.typeOf(hw.model()), hw.id());
  }

  private static int set(ConfigJNI self, int deviceHash) {
    Device device = deviceOf(deviceHash);
    if (device != null) {
      synchronized (PhoenixSim.LOCK) {
        device.apply(Config.parse(self.serializedString));
      }
    }
    return 0;
  }

  private static int get(ConfigJNI self, int deviceHash) {
    Device device = deviceOf(deviceHash);
    if (device != null) {
      synchronized (PhoenixSim.LOCK) {
        self.serializedString = device.config.serialize();
      }
    }
    return 0;
  }
}
