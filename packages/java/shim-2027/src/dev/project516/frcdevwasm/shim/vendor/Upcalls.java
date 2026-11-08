package dev.project516.frcdevwasm.shim.vendor;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Callbacks that a vendor library hands to its native code. Nothing native runs here, so the Impl
 * classes call the Java target of a stub directly, found by the stub's address.
 */
public final class Upcalls {
  // Weak keys: an entry lasts while the library holds its stub, as a native stub would.
  private static final Map<MemorySegment, MethodHandle> TARGETS = Collections.synchronizedMap(new WeakHashMap<>());
  private static long fakeAddress = 1L << 40;

  private Upcalls() {}

  /** Stands in for {@code Linker.upcallStub} in transformed jars. */
  public static MemorySegment upcallStub(
      Linker linker, MethodHandle target, FunctionDescriptor descriptor, Arena arena, Linker.Option... options) {
    MemorySegment stub;
    try {
      stub = linker.upcallStub(target, descriptor, arena, options);
    } catch (UnsupportedOperationException | LinkageError e) {
      synchronized (Upcalls.class) {
        stub = MemorySegment.ofAddress(fakeAddress += 16);
      }
    }
    TARGETS.put(stub, target);
    return stub;
  }

  /** The Java method behind a stub, or null for a null or unknown stub. */
  public static MethodHandle target(MemorySegment stub) {
    return stub == null ? null : TARGETS.get(stub);
  }
}
