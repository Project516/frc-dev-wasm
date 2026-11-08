package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.hardware.jni;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HardwareJNIImpl {
  private static final int MAX_DEVICE_ID = 62;

  /** A device the robot program constructed. Its hash is its index plus one. */
  public record Device(int id, String model) {}

  private static final Map<String, Integer> hashes = new HashMap<>();
  private static final List<Device> devices = new ArrayList<>();

  private HardwareJNIImpl() {}

  /** A unique non-zero hash per device. Zero means the id is invalid. */
  public static synchronized int getDeviceHash(int id, String model, String canbus) {
    if (id < 0 || id > MAX_DEVICE_ID) {
      return 0;
    }
    return hashes.computeIfAbsent(
        canbus + "/" + model + "/" + id,
        key -> {
          devices.add(new Device(id, model));
          return devices.size();
        });
  }

  public static synchronized Device deviceOf(int hash) {
    return hash >= 1 && hash <= devices.size() ? devices.get(hash - 1) : null;
  }
}
