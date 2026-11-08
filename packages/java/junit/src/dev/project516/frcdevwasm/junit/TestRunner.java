package dev.project516.frcdevwasm.junit;

import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Runs JUnit tests as Gradle's test task does and prints what its console prints. Arguments are the
 * names of the classes to start from, and {@code --tests <pattern>} to run only the tests whose class or
 * {@code class.method} matches. Exits with 1 when a test failed, 3 when the patterns match no test, and 2 when
 * the run itself fails.
 */
public final class TestRunner {
  private TestRunner() {}

  public static void main(String[] args) {
    int code;
    try {
      code = run(args, System.out, System.err);
    } catch (Throwable t) {
      t.printStackTrace();
      code = 2;
    }
    System.exit(code);
  }

  // An inner class (@Nested) is found through its enclosing class, in the order JUnit gives its tests
  private static boolean isInner(String name) {
    if (name.indexOf('$') < 0) {
      return false;
    }
    try {
      Class<?> type = Class.forName(name, false, TestRunner.class.getClassLoader());
      return type.isMemberClass() && !Modifier.isStatic(type.getModifiers());
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** Returns the exit code: 0 when every test passed. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    List<String> classes = new ArrayList<>();
    List<String> patterns = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--tests") && i + 1 < args.length) {
        patterns.add(args[++i]);
      } else {
        classes.add(args[i]);
      }
    }
    GradleReporter reporter = new GradleReporter(out, err);
    LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request();
    for (String name : classes) {
      if (!isInner(name)) {
        request.selectors(DiscoverySelectors.selectClass(name));
      }
    }
    if (!patterns.isEmpty()) {
      request.filters(new TestFilter(patterns));
    }
    Launcher launcher = LauncherFactory.create();
    TestPlan plan = launcher.discover(request.build());
    reporter.capture();
    try {
      launcher.execute(plan, reporter);
    } finally {
      reporter.release();
    }
    if (!patterns.isEmpty() && reporter.total() == 0) {
      out.println();
      out.println("No tests found for given includes: " + patterns + "(--tests filter)");
      return 3;
    }
    reporter.summary();
    return reporter.failed() > 0 ? 1 : 0;
  }
}
