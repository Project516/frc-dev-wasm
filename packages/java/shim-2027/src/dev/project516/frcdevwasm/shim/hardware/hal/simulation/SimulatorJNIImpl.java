package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.SimTime;

/** Simulation timing and lifecycle control (SimulatorJNI.cpp, MockHooks.cpp). */
public final class SimulatorJNIImpl {
  private static volatile int runtimeType = 1;

  private SimulatorJNIImpl() {}

  public static void setRuntimeType(int type) {
    runtimeType = type;
  }

  public static int runtimeType() {
    return runtimeType;
  }

  public static void waitForProgramStart(boolean waitForFirstNotifier) {
    Sim.waitForProgramStart(waitForFirstNotifier);
  }

  public static void setProgramStarted(boolean started) {
    Sim.setProgramStarted(started);
  }

  public static boolean getProgramStarted() {
    return Sim.getProgramStarted();
  }

  public static void setProgramState(long word) {
    Sim.setProgramState(word);
  }

  public static long nativeGetProgramState() {
    return Sim.getProgramState();
  }

  public static void restartTiming() {
    SimTime.restart();
  }

  public static void pauseTiming() {
    Sim.pauseTiming();
  }

  public static void resumeTiming() {
    Sim.resumeTiming();
  }

  public static boolean isTimingPaused() {
    return SimTime.isPaused();
  }

  public static void stepTiming(long delta) {
    Sim.stepTiming(delta);
  }

  public static void stepTimingAsync(long delta) {
    Sim.stepTimingAsync(delta);
  }

  public static void resetHandles() {}
}
