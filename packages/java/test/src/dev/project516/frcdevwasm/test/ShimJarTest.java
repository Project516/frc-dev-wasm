package dev.project516.frcdevwasm.test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** The shim jar replaces every WPILib JNI class and leaves no native method behind. */
public final class ShimJarTest {
  private ShimJarTest() {}

  private static List<File> wpilibJars() {
    File lib = new File(System.getProperty("frcdevwasm.runtime"), "lib");
    List<File> jars = new ArrayList<>();
    for (File f : lib.listFiles()) {
      String n = f.getName();
      if (n.endsWith(".jar") && !n.startsWith("opencv")) {
        jars.add(f);
      }
    }
    return jars;
  }

  private static List<String> classNames(File jar) throws Exception {
    List<String> names = new ArrayList<>();
    try (JarFile jf = new JarFile(jar)) {
      for (JarEntry e : Collections.list(jf.entries())) {
        String n = e.getName();
        if (n.endsWith(".class") && !n.contains("module-info") && !n.startsWith("META-INF")) {
          names.add(n.substring(0, n.length() - 6).replace('/', '.'));
        }
      }
    }
    return names;
  }

  public static void run(Tests t) {
    t.test("every WPILib class with natives is replaced and has none", () -> {
      File shim = new File(System.getProperty("frcdevwasm.runtime"), "frcdevwasm-shim.jar");
      List<URL> urls = new ArrayList<>();
      urls.add(shim.toURI().toURL());
      for (File f : wpilibJars()) {
        urls.add(f.toURI().toURL());
      }
      URLClassLoader shimFirst = new URLClassLoader(urls.toArray(new URL[0]), null);
      List<URL> realUrls = new ArrayList<>();
      for (File f : wpilibJars()) {
        realUrls.add(f.toURI().toURL());
      }
      URLClassLoader real = new URLClassLoader(realUrls.toArray(new URL[0]), null);

      int replaced = 0;
      for (File jar : wpilibJars()) {
        for (String name : classNames(jar)) {
          boolean hadNative = false;
          try {
            for (Method m : Class.forName(name, false, real).getDeclaredMethods()) {
              hadNative |= Modifier.isNative(m.getModifiers());
            }
          } catch (LinkageError e) {
            continue;
          }
          if (!hadNative) {
            continue;
          }
          Class<?> shimmed = Class.forName(name, false, shimFirst);
          Tests.check(
              shimmed.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("frcdevwasm-shim.jar"),
              name + " is not loaded from the shim jar");
          for (Method m : shimmed.getDeclaredMethods()) {
            Tests.check(!Modifier.isNative(m.getModifiers()), name + "." + m.getName() + " is still native");
          }
          replaced++;
        }
      }
      Tests.check(replaced >= 66, "expected at least 66 replaced classes, found " + replaced);
    });

    t.test("no class in the shim jar loads a native library", () -> {
      File shim = new File(System.getProperty("frcdevwasm.runtime"), "frcdevwasm-shim.jar");
      List<String> offenders = new ArrayList<>();
      for (String name : classNames(shim)) {
        if (name.startsWith("edu.wpi.first.")) {
          try (JarFile jf = new JarFile(shim)) {
            byte[] bytes = jf.getInputStream(jf.getEntry(name.replace('.', '/') + ".class")).readAllBytes();
            String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (text.contains("loadLibrary")) {
              offenders.add(name);
            }
          }
        }
      }
      Tests.check(offenders.isEmpty(), "classes still loading libraries: " + offenders);
    });
  }
}
