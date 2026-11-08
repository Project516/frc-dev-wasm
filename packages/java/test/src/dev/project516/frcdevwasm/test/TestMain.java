package dev.project516.frcdevwasm.test;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs desktop test classes. Every class named *Test in this package with {@code public static
 * void run(Tests t)} is run, or only the simple class names given as arguments.
 * System properties: frcdevwasm.jdk (JDK home), frcdevwasm.runtime (apps/ide/public/runtime/<year>).
 */
public final class TestMain {
  private TestMain() {}

  public static void main(String[] args) throws Exception {
    List<String> names = new ArrayList<>(Arrays.asList(args));
    if (names.isEmpty()) {
      File dir =
          new File(TestMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
              .toPath()
              .resolve(TestMain.class.getPackageName().replace('.', '/'))
              .toFile();
      String[] files = dir.list();
      Arrays.sort(files);
      for (String f : files) {
        if (f.endsWith("Test.class")) {
          names.add(f.substring(0, f.length() - ".class".length()));
        }
      }
    }
    Tests t = new Tests();
    for (String name : names) {
      Class<?> cls;
      try {
        cls = Class.forName(TestMain.class.getPackageName() + "." + name);
      } catch (ClassNotFoundException e) {
        continue;
      }
      cls.getMethod("run", Tests.class).invoke(null, t);
    }
    System.exit(t.finish());
  }
}
