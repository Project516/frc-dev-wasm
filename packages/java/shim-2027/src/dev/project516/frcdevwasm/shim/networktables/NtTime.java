package dev.project516.frcdevwasm.shim.networktables;

import java.util.function.LongSupplier;

/** Nanosecond clock behind NetworkTablesJNI.now(). */
public final class NtTime {
  private static final long START = System.nanoTime();
  private static volatile LongSupplier source = () -> System.nanoTime() - START + 1000;
  private static volatile LongSupplier origin = () -> 0;

  private NtTime() {}

  /**
   * Replaces the clock, for example with {@code WPIUtilJNI::now}. The page sees times in
   * microseconds since origin.
   */
  public static void setSource(LongSupplier nanos, LongSupplier originNanos) {
    source = nanos;
    origin = originNanos;
  }

  public static long now() {
    return source.getAsLong();
  }

  /** Microseconds for the page; 0 stays 0 and anything else is at least 1. */
  public static long pageMicros(long nanos) {
    return nanos == 0 ? 0 : Math.max((nanos - origin.getAsLong()) / 1000, 1);
  }
}
