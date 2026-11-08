package dev.project516.frcdevwasm.sim;

/**
 * Simulation clock, as hal/src/main/native/sim/MockHooks.cpp. Times are microseconds. Real time
 * while running; frozen while paused, and advanced by step.
 */
public final class SimTime {
  private static long startTime = nowDefault();
  private static long pauseTime;
  private static long stepTime;

  private SimTime() {}

  /** Monotonic real clock, microseconds. */
  public static long nowDefault() {
    return System.nanoTime() / 1000;
  }

  /** Microseconds since program start (or the last restart), sim-time aware. */
  public static synchronized long fpgaTime() {
    long cur = pauseTime != 0 ? pauseTime : nowDefault();
    return cur + stepTime - startTime;
  }

  public static synchronized void restart() {
    startTime = nowDefault();
    stepTime = 0;
    if (pauseTime != 0) {
      pauseTime = startTime;
    }
  }

  public static synchronized void pause() {
    if (pauseTime == 0) {
      pauseTime = nowDefault();
    }
  }

  public static synchronized void resume() {
    if (pauseTime != 0) {
      startTime += nowDefault() - pauseTime;
      pauseTime = 0;
    }
  }

  public static synchronized boolean isPaused() {
    return pauseTime != 0;
  }

  /** Advances the clock without waking notifiers. */
  public static synchronized void advance(long delta) {
    stepTime += delta;
  }
}
