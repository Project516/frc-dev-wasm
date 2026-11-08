package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** C strings and out parameters for the Impl classes of the java.lang.foreign bindings. */
public final class Ffi {
  private static final Map<Long, Arena> OWNED = new ConcurrentHashMap<>();

  private Ffi() {}

  public static String string(MemorySegment cString) {
    return cString == null || cString.equals(MemorySegment.NULL) ? "" : cString.getString(0);
  }

  /** A C string that the library hands out, which the caller returns through free_memory. */
  public static MemorySegment allocate(String text) {
    Arena arena = Arena.ofShared();
    MemorySegment segment = arena.allocateFrom(text);
    OWNED.put(segment.address(), arena);
    return segment;
  }

  public static void free(MemorySegment cString) {
    Arena arena = OWNED.remove(cString.address());
    if (arena != null) {
      arena.close();
    }
  }
}
