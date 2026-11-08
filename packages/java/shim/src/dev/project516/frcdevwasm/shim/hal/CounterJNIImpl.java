package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.Sim;
import java.nio.IntBuffer;

/**
 * Port of hal/src/main/native/sim/Counter.cpp. The simulated HAL has no counters: initialization
 * returns handle 0 and every other call does nothing or returns zero.
 */
public final class CounterJNIImpl {
  private CounterJNIImpl() {}

  public static int initializeCounter(int mode, IntBuffer index) {
    Sim.ensureInitialized();
    return 0;
  }

  public static void setCounterAverageSize(int id, int value) {
  }

  public static void setCounterUpSource(int id, int digitalSourceHandle, int analogTriggerType) {
  }

  public static void setCounterUpSourceEdge(int id, boolean valueRise, boolean valueFall) {
  }

  public static void clearCounterUpSource(int id) {
  }

  public static void setCounterDownSource(int id, int digitalSourceHandle, int analogTriggerType) {
  }

  public static void setCounterDownSourceEdge(int id, boolean valueRise, boolean valueFall) {
  }

  public static void clearCounterDownSource(int id) {
  }

  public static void setCounterUpDownMode(int id) {
  }

  public static void setCounterExternalDirectionMode(int id) {
  }

  public static void setCounterSemiPeriodMode(int id, boolean value) {
  }

  public static void setCounterPulseLengthMode(int id, double value) {
  }

  public static int getCounterSamplesToAverage(int id) {
    return 0;
  }

  public static void setCounterSamplesToAverage(int id, int value) {
  }

  public static void resetCounter(int id) {
  }

  public static int getCounter(int id) {
    return 0;
  }

  public static double getCounterPeriod(int id) {
    return 0.0;
  }

  public static void setCounterMaxPeriod(int id, double value) {
  }

  public static void setCounterUpdateWhenEmpty(int id, boolean value) {
  }

  public static boolean getCounterStopped(int id) {
    return false;
  }

  public static boolean getCounterDirection(int id) {
    return false;
  }

  public static void setCounterReverseDirection(int id, boolean value) {
  }

  public static void freeCounter(int id) {
  }
}
