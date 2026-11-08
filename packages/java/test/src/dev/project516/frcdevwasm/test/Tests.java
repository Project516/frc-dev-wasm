package dev.project516.frcdevwasm.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Minimal test harness: named cases, assertions that throw, a summary at the end. */
public final class Tests {
  public interface Case {
    void run() throws Exception;
  }

  private final List<String> failures = new ArrayList<>();
  private int passed;

  public void test(String name, Case c) {
    long t0 = System.nanoTime();
    try {
      c.run();
      passed++;
      System.out.printf("PASS %s (%d ms)%n", name, (System.nanoTime() - t0) / 1_000_000);
    } catch (Throwable t) {
      failures.add(name);
      System.out.println("FAIL " + name);
      t.printStackTrace(System.out);
    }
  }

  public int finish() {
    System.out.printf("%d passed, %d failed%n", passed, failures.size());
    for (String f : failures) {
      System.out.println("  failed: " + f);
    }
    return failures.isEmpty() ? 0 : 1;
  }

  public static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  public static void eq(Object expected, Object actual, String message) {
    if (!Objects.equals(expected, actual)) {
      throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
    }
  }

  public static void near(double expected, double actual, double tol, String message) {
    if (Math.abs(expected - actual) > tol) {
      throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
    }
  }
}
