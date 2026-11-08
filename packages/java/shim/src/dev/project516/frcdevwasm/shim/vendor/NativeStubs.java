package dev.project516.frcdevwasm.shim.vendor;

/** Helpers that transformed vendor natives call. */
public final class NativeStubs {
  private static final long IDLE_MILLIS = 20;

  private NativeStubs() {}

  /** Stands in for a native wait that has nothing to wait for. */
  public static void idle() {
    try {
      Thread.sleep(IDLE_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
