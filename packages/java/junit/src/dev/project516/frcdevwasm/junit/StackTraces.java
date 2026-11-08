package dev.project516.frcdevwasm.junit;

import java.io.PrintStream;

/**
 * A failure as Gradle's short exception format shows it: the exception class and where in the test
 * class it came from, then its causes. The message goes below, since there is no test report to read it in.
 */
final class StackTraces {
  private StackTraces() {}

  /** {@code testClass} is the class name of the test, whose frames {@code Outer$Inner} also count. */
  static void print(PrintStream out, Throwable error, String testClass) {
    String indent = "    ";
    for (Throwable t = error, last = null; t != null && t != last; last = t, t = t.getCause()) {
      String where = where(t, testClass);
      out.println(indent + (t == error ? "" : "Caused by: ") + t.getClass().getName() + where);
      String message = t.getMessage();
      if (message != null && !message.isEmpty()) {
        for (String line : message.split("\r?\n", -1)) {
          out.println(indent + "    " + line);
        }
      }
      indent += "    ";
    }
  }

  // " at BasicTest.java:18", from the first frame in the test class. Without line numbers (CheerpJ has none) the method.
  private static String where(Throwable t, String testClass) {
    for (StackTraceElement frame : t.getStackTrace()) {
      String name = frame.getClassName();
      if (name.equals(testClass) || name.startsWith(testClass + "$")) {
        if (frame.getFileName() == null || frame.getLineNumber() < 0) {
          return " at " + name.substring(name.lastIndexOf('.') + 1) + "." + frame.getMethodName();
        }
        return " at " + frame.getFileName() + ":" + frame.getLineNumber();
      }
    }
    return "";
  }
}
