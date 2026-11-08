package dev.project516.frcdevwasm.shim;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Warns once per native method that has no handwritten implementation. */
public final class Unimplemented {
  private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

  private Unimplemented() {}

  public static void warn(String method) {
    if (WARNED.add(method)) {
      System.err.println("[frc-dev-wasm] unimplemented native " + method);
    }
  }
}
