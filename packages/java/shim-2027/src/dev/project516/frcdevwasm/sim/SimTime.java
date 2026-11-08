package dev.project516.frcdevwasm.sim;

/**
 * Simulation clock, as hal/src/main/native/sim/MockHooks.cpp. Times are nanoseconds. Real time
 * while running; frozen while paused, and advanced by step.
 */
public final class SimTime {
  private static long pauseTime;
  private static long offset;
  private static long programStart = nowDefault();

  private SimTime() {}

  /** Monotonic real clock, nanoseconds. */
  public static long nowDefault() {
    return System.nanoTime();
  }

  /** Simulated monotonic time in nanoseconds, HAL_GetMonotonicTime. */
  public static synchronized long now() {
    return (pauseTime != 0 ? pauseTime : nowDefault()) + offset;
  }

  /** Simulated time at the last restart, wpi::util::GetProgramStartTime. */
  public static synchronized long programStartTime() {
    return programStart;
  }

  /** Microseconds since program start, the time base of NetworkTables and DataLog. */
  public static long fpgaTime() {
    return (now() - programStartTime()) / 1000;
  }

  /** Sets the program start time to the current simulated time. */
  public static synchronized void restart() {
    programStart = now();
  }

  public static synchronized void pause() {
    if (pauseTime == 0) {
      pauseTime = nowDefault();
    }
  }

  public static synchronized void resume() {
    if (pauseTime != 0) {
      offset -= nowDefault() - pauseTime;
      pauseTime = 0;
    }
  }

  public static synchronized boolean isPaused() {
    return pauseTime != 0;
  }

  /** Advances the clock without waking notifiers. */
  public static synchronized void advance(long delta) {
    offset += delta;
  }
}
