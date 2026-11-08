package dev.project516.frcdevwasm.shim.test;

import java.util.Arrays;

/** Minimal assertions for the shim test runner. */
public final class T {
  private T() {}

  public static void check(boolean cond, String msg) {
    if (!cond) {
      throw new AssertionError(msg);
    }
  }

  public static void eq(Object expected, Object actual, String msg) {
    if (!java.util.Objects.deepEquals(expected, actual)) {
      throw new AssertionError(msg + ": expected " + str(expected) + " but was " + str(actual));
    }
  }

  public static void eq(Object expected, Object actual) {
    eq(expected, actual, "values differ");
  }

  public static void near(double expected, double actual, double tol, String msg) {
    if (!(Math.abs(expected - actual) <= tol)) {
      throw new AssertionError(msg + ": expected " + expected + " but was " + actual + " (tol " + tol + ")");
    }
  }

  public static void near(double[] expected, double[] actual, double tol, String msg) {
    eq(expected.length, actual.length, msg + " length");
    for (int i = 0; i < expected.length; i++) {
      near(expected[i], actual[i], tol, msg + "[" + i + "]");
    }
  }

  public static <E extends Throwable> E throwsA(Class<E> type, Runnable r) {
    try {
      r.run();
    } catch (Throwable t) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
      throw new AssertionError("expected " + type.getName() + " but got " + t, t);
    }
    throw new AssertionError("expected " + type.getName() + " but nothing was thrown");
  }

  /** Polls until cond holds or the timeout passes. */
  public static void await(java.util.function.BooleanSupplier cond, String msg) {
    long end = System.nanoTime() + 5_000_000_000L;
    while (!cond.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out waiting for " + msg);
      }
      try {
        Thread.sleep(2);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
  }

  private static String str(Object o) {
    if (o instanceof double[] a) return Arrays.toString(a);
    if (o instanceof long[] a) return Arrays.toString(a);
    if (o instanceof int[] a) return Arrays.toString(a);
    if (o instanceof boolean[] a) return Arrays.toString(a);
    if (o instanceof float[] a) return Arrays.toString(a);
    if (o instanceof byte[] a) return Arrays.toString(a);
    if (o instanceof Object[] a) return Arrays.deepToString(a);
    return String.valueOf(o);
  }
}
