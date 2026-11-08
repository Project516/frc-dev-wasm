package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.jni;

import com.ctre.phoenix6.jni.StatusSignalJNI;
import com.ctre.phoenix6.spns.SpnValue;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.hardware.jni.HardwareJNIImpl;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.PhoenixSim;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Status signals report OK with the values of the simulated device. Signals the device does not
 * emulate read zero. Phoenix 6 26.1 passes the CAN bus as a string, so {@code waitForAll} has both
 * overloads.
 */
public final class StatusSignalJNIImpl {
  private static final long WAIT_MILLIS = 4;
  private static final Map<Integer, String> NAMES = new HashMap<>();

  static {
    for (SpnValue spn : SpnValue.values()) {
      NAMES.put(spn.value, spn.name());
    }
  }

  private StatusSignalJNIImpl() {}

  public static int JNI_RefreshSignal(StatusSignalJNI self, double timeoutSeconds) {
    HardwareJNIImpl.Device device = HardwareJNIImpl.deviceOf(self.deviceHash);
    String spn = NAMES.get(self.spn);
    if (device != null && spn != null) {
      int type = PhoenixSim.typeOf(device.model());
      double value = PhoenixSim.device(type, device.id()).signal(spn);
      if (!Double.isNaN(value)) {
        self.value = value;
      }
    }
    double now = PhoenixSim.now();
    self.hwtimeStampSeconds = now;
    self.swtimeStampSeconds = now;
    self.ecutimeStampSeconds = now;
    self.statusCode = 0;
    return 0;
  }

  public static int JNI_WaitForSignal(StatusSignalJNI self, double timeoutSeconds) {
    pause(timeoutSeconds);
    return JNI_RefreshSignal(self, timeoutSeconds);
  }

  public static int JNI_WaitForAll(ByteBuffer network, double timeoutSeconds, StatusSignalJNI[] signals) {
    return waitForAll(timeoutSeconds, signals);
  }

  public static int JNI_WaitForAll(String network, double timeoutSeconds, StatusSignalJNI[] signals) {
    return waitForAll(timeoutSeconds, signals);
  }

  private static int waitForAll(double timeoutSeconds, StatusSignalJNI[] signals) {
    pause(timeoutSeconds);
    for (StatusSignalJNI signal : signals) {
      JNI_RefreshSignal(signal, timeoutSeconds);
    }
    return 0;
  }

  /** A real wait blocks until fresh data, so a loop that waits does not spin. */
  private static void pause(double timeoutSeconds) {
    long millis = Math.min(WAIT_MILLIS, (long) (timeoutSeconds * 1000));
    if (millis > 0) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
