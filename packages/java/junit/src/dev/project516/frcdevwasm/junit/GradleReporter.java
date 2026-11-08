package dev.project516.frcdevwasm.junit;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Prints test results as Gradle's console does with the logging that GradleRIO sets: every result,
 * and the output of each test, with the failure of a test below its line.
 */
final class GradleReporter implements TestExecutionListener {
  private final PrintStream out;
  private final PrintStream err;
  private PrintStream savedOut;
  private PrintStream savedErr;
  private final Capture stdout;
  private final Capture stderr;
  private TestPlan plan;
  private int total;
  private int failed;
  private int skipped;

  GradleReporter(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
    stdout = new Capture(out, out, "STANDARD_OUT");
    stderr = new Capture(out, err, "STANDARD_ERROR");
  }

  int failed() {
    return failed;
  }

  int total() {
    return total;
  }

  /** Routes what the tests print here, so it is shown with the test that printed it. */
  void capture() {
    savedOut = System.out;
    savedErr = System.err;
    System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
  }

  void release() {
    System.out.flush();
    System.err.flush();
    System.setOut(savedOut);
    System.setErr(savedErr);
  }

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    plan = testPlan;
  }

  @Override
  public void executionStarted(TestIdentifier id) {
    if (id.isTest()) {
      stdout.start();
      stderr.start();
    }
  }

  @Override
  public void executionSkipped(TestIdentifier id, String reason) {
    if (id.isTest()) {
      skip(id);
    } else {
      for (TestIdentifier test : plan.getDescendants(id)) {
        if (test.isTest()) {
          skip(test);
        }
      }
    }
  }

  private void skip(TestIdentifier id) {
    total++;
    skipped++;
    line(id, "SKIPPED");
  }

  @Override
  public void executionFinished(TestIdentifier id, TestExecutionResult result) {
    boolean ok = result.getStatus() == TestExecutionResult.Status.SUCCESSFUL;
    if (!id.isTest() && ok) {
      return;
    }
    String label = label(id);
    stdout.flushTo(label);
    stderr.flushTo(label);
    stdout.stop();
    stderr.stop();
    total++;
    if (ok) {
      line(id, "PASSED");
    } else if (result.getStatus() == TestExecutionResult.Status.ABORTED) {
      skipped++;
      line(id, "SKIPPED");
    } else {
      failed++;
      line(id, "FAILED");
      result.getThrowable().ifPresent(error -> StackTraces.print(out, error, topLevelClass(id)));
    }
  }

  void summary() {
    if (failed == 0) {
      return;
    }
    StringBuilder text = new StringBuilder(total + (total == 1 ? " test" : " tests") + " completed, " + failed + " failed");
    if (skipped > 0) {
      text.append(", ").append(skipped).append(" skipped");
    }
    out.println();
    out.println(text);
    out.println();
  }

  private void line(TestIdentifier id, String state) {
    out.println();
    out.println(label(id) + " " + state);
  }

  // "BasicTest > Inner > nestedFails()": the top-level class, then the display names below it
  private String label(TestIdentifier id) {
    Deque<String> names = new ArrayDeque<>();
    TestIdentifier current = id;
    while (current != null && !isTopLevelClass(current)) {
      names.addFirst(current.getDisplayName());
      current = parent(current);
    }
    String top = current == null ? "" : simpleName(className(current));
    // A class that fails before its tests run, as Gradle names it
    if (names.isEmpty()) {
      names.add("initializationError");
    }
    return top + " > " + String.join(" > ", names);
  }

  private String topLevelClass(TestIdentifier id) {
    TestIdentifier current = id;
    while (current != null && !isTopLevelClass(current)) {
      current = parent(current);
    }
    return current == null ? "" : className(current);
  }

  private TestIdentifier parent(TestIdentifier id) {
    Optional<TestIdentifier> parent = plan.getParent(id);
    return parent.orElse(null);
  }

  private boolean isTopLevelClass(TestIdentifier id) {
    TestIdentifier parent = parent(id);
    return className(id) != null && (parent == null || className(parent) == null);
  }

  private static String className(TestIdentifier id) {
    return id.getSource().filter(ClassSource.class::isInstance).map(source -> ((ClassSource) source).getClassName()).orElse(null);
  }

  private static String simpleName(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  /** What one test wrote to a stream, shown under a header when the test ends. Other output goes straight through. */
  private static final class Capture extends OutputStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintStream target;
    private final PrintStream passThrough;
    private final String event;
    private boolean active;

    // Gradle shows both streams in one console, so a test's error output goes under its header on `target` too
    Capture(PrintStream target, PrintStream passThrough, String event) {
      this.target = target;
      this.passThrough = passThrough;
      this.event = event;
    }

    void start() {
      buffer.reset();
      active = true;
    }

    void stop() {
      active = false;
    }

    void flushTo(String label) {
      if (buffer.size() == 0) {
        return;
      }
      String text = buffer.toString(StandardCharsets.UTF_8);
      buffer.reset();
      target.println();
      target.println(label + " " + event);
      if (text.endsWith("\n")) {
        text = text.substring(0, text.length() - 1);
      }
      for (String line : text.split("\r?\n", -1)) {
        target.println("    " + line);
      }
    }

    @Override
    public void write(int b) {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      if (active) {
        buffer.write(bytes, offset, length);
      } else {
        passThrough.write(bytes, offset, length);
      }
    }
  }
}
