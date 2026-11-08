package dev.project516.frcdevwasm.shim.networktables;

import java.util.function.LongSupplier;

/** Microsecond clock behind NetworkTablesJNI.now(). */
public final class NtTime {
  private static final long START = System.nanoTime();
  private static volatile LongSupplier source = () -> (System.nanoTime() - START) / 1000 + 1;

  private NtTime() {}

  /** Replaces the clock, for example with {@code HALUtil::getFPGATime}. */
  public static void setSource(LongSupplier micros) {
    source = micros;
  }

  public static long now() {
    return source.getAsLong();
  }
}
