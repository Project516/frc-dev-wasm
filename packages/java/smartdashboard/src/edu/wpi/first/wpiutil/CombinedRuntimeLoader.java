package edu.wpi.first.wpiutil;

import java.util.List;

/** Replaces the loader of native libraries. CheerpJ cannot load them, and the JNI classes need none. */
public final class CombinedRuntimeLoader {
  private CombinedRuntimeLoader() {}

  public static synchronized String getExtractionDirectory() {
    return null;
  }

  public static String setDllDirectory(String directory) {
    return directory;
  }

  public static <T> List<String> extractLibraries(Class<T> clazz, String resourceName) {
    return List.of();
  }

  public static void loadLibrary(String libraryName, List<String> extractedFiles) {}

  public static <T> void loadLibraries(Class<T> clazz, String... librariesToLoad) {}
}
