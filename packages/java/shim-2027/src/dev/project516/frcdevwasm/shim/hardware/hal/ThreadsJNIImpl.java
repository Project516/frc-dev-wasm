package dev.project516.frcdevwasm.shim.hardware.hal;

/** Port of hal/src/main/native/sim/Threads.cpp: thread priorities are not simulated. */
public final class ThreadsJNIImpl {
  private ThreadsJNIImpl() {}

  public static int getCurrentThreadPriority() {
    return 0;
  }

  public static boolean setCurrentThreadPriority(int priority) {
    return true;
  }
}
