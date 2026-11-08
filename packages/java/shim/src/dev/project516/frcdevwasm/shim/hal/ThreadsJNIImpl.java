package dev.project516.frcdevwasm.shim.hal;

/** Port of hal/src/main/native/sim/Threads.cpp: thread priorities are not simulated. */
public final class ThreadsJNIImpl {
  private ThreadsJNIImpl() {}

  public static int getCurrentThreadPriority() {
    return 0;
  }

  public static boolean getCurrentThreadIsRealTime() {
    return false;
  }

  public static boolean setCurrentThreadPriority(boolean realTime, int priority) {
    return true;
  }
}
