package dev.project516.frcdevwasm.junit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Gradle's {@code --tests}: a pattern with {@code *} wildcards that matches the class name, or the
 * class name and method name joined by a dot. Containers pass, and JUnit drops the empty ones.
 */
final class TestFilter implements PostDiscoveryFilter {
  private final List<Pattern> patterns = new ArrayList<>();

  TestFilter(List<String> wanted) {
    for (String pattern : wanted) {
      List<String> parts = new ArrayList<>();
      for (String part : pattern.split("\\*", -1)) {
        parts.add(Pattern.quote(part));
      }
      patterns.add(Pattern.compile(String.join(".*", parts)));
    }
  }

  @Override
  public FilterResult apply(TestDescriptor descriptor) {
    // Classes pass. Methods are matched where they are first described, so the invocations of a template, which are created later, follow it.
    Optional<MethodSource> source = descriptor.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast);
    if (source.isEmpty()) {
      return FilterResult.included("not a method");
    }
    String className = source.get().getClassName();
    String name = className + "." + source.get().getMethodName();
    for (Pattern pattern : patterns) {
      if (matches(pattern, name) || matches(pattern, className)) {
        return FilterResult.included("matches " + pattern);
      }
    }
    return FilterResult.excluded("no --tests pattern matches");
  }

  // Also without the package, so ExampleTest.passes finds frc.robot.ExampleTest.passes
  private static boolean matches(Pattern pattern, String name) {
    for (int dot = -1; ; dot = name.indexOf('.', dot + 1)) {
      if (pattern.matcher(name.substring(dot + 1)).matches()) {
        return true;
      }
      if (name.indexOf('.', dot + 1) < 0) {
        return false;
      }
    }
  }
}
