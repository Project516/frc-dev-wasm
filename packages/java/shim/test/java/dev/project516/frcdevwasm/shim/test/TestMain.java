package dev.project516.frcdevwasm.shim.test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Runs every public static void test*() in dev.project516.frcdevwasm.shim.test.*Test classes. */
public final class TestMain {
  public static void main(String[] args) throws IOException {
    Path classes = Path.of(args[0]);
    List<String> filters = List.of(args).subList(1, args.length);
    List<String> names = new ArrayList<>();
    try (Stream<Path> s = Files.list(classes.resolve("dev/project516/frcdevwasm/shim/test"))) {
      s.map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith("Test.class"))
          .map(n -> "dev.project516.frcdevwasm.shim.test." + n.substring(0, n.length() - 6))
          .sorted()
          .forEach(names::add);
    }
    int passed = 0;
    int failed = 0;
    for (String name : names) {
      if (!filters.isEmpty() && filters.stream().noneMatch(name::contains)) {
        continue;
      }
      Class<?> cls;
      try {
        cls = Class.forName(name);
      } catch (ClassNotFoundException | LinkageError e) {
        System.out.println("FAIL " + name + " (load): " + e);
        failed++;
        continue;
      }
      for (Method m : cls.getDeclaredMethods()) {
        if (!m.getName().startsWith("test") || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
          continue;
        }
        try {
          m.invoke(null);
          passed++;
          System.out.println("ok   " + name + "." + m.getName());
        } catch (InvocationTargetException e) {
          failed++;
          System.out.println("FAIL " + name + "." + m.getName() + ": " + e.getCause());
          e.getCause().printStackTrace(System.out);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    System.out.println(passed + " passed, " + failed + " failed");
    System.exit(failed == 0 ? 0 : 1);
  }
}
