package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/**
 * Port of hal/src/main/native/sim/Counter.cpp. The simulated HAL has no counters: initialization
 * returns handle 0 and every other call does nothing or returns zero.
 */
public final class CounterJNIImpl {
  private CounterJNIImpl() {}

  public static int initializeCounter(int channel, boolean risingEdge) {
    Sim.ensureInitialized();
    return 0;
  }

  public static void freeCounter(int id) {}

  public static void setCounterEdgeConfiguration(int id, boolean risingEdge) {}

  public static void setCounterRateWindow(int id, int windowMilliseconds) {
    if (windowMilliseconds < 5 || windowMilliseconds > 255) {
      Status.check(Status.PARAMETER_OUT_OF_RANGE);
    }
  }

  public static void resetCounter(int id) {}

  public static int getCounter(int id) {
    return 0;
  }

  public static double getCounterRate(int id) {
    return 0.0;
  }

  public static boolean getCounterStopped(int id) {
    return true;
  }
}
