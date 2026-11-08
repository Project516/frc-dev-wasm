package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.ffi;

import com.ctre.phoenix6.ffi.StatusSignalNative.return_values_t;
import com.ctre.phoenix6.ffi.StatusSignalNative.signal_values_t;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.jni.StatusSignalJNIImpl;
import dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim.PhoenixSim;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Status signals report OK with the values of the simulated device, as in the JNI binding. */
public final class StatusSignalNativeImpl {
  private static final MemorySegment NO_UNITS = Arena.global().allocateFrom("");

  private StatusSignalNativeImpl() {}

  public static int c_ctre_phoenix6_get_signal(
      long count, MemorySegment signals, MemorySegment returns, boolean waitForUpdate, double timeoutSeconds) {
    if (waitForUpdate) {
      StatusSignalJNIImpl.pause(timeoutSeconds);
    }
    synchronized (PhoenixSim.LOCK) {
      double now = PhoenixSim.now();
      for (long i = 0; i < count; i++) {
        MemorySegment signal = signal_values_t.asSlice(signals, i);
        MemorySegment result = return_values_t.asSlice(returns, i);
        double value = StatusSignalJNIImpl.read(signal_values_t.deviceHash(signal), signal_values_t.spn(signal));
        return_values_t.outValue(result, Double.isNaN(value) ? 0 : value);
        return_values_t.hwtimestampSeconds(result, now);
        return_values_t.swtimestampSeconds(result, now);
        return_values_t.ecutimestampSeconds(result, now);
        return_values_t.error(result, 0);
        return_values_t.unitsKey(result, 0);
      }
    }
    return 0;
  }

  public static int c_ctre_phoenix6_get_rets(short unitsKey, int fields, MemorySegment returns) {
    return_values_t.units(returns, NO_UNITS);
    return 0;
  }
}
