package dev.project516.frcdevwasm.shim.util;

import dev.project516.frcdevwasm.sim.SimTime;
import dev.project516.frcdevwasm.sim.Sync;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Time, events, semaphores and raw frames of wpiutil (WPIUtilJNI.cpp). */
public final class WPIUtilJNIImpl {
  private static final class Frame {
    ByteBuffer data;
    int size;
    int width;
    int height;
    int stride;
    int pixelFormat;
    long time;
    int timeSource;
  }

  private static final Map<Long, Frame> FRAMES = new HashMap<>();
  private static long nextFrame = 1;
  private static volatile boolean mockTime;
  private static volatile long mockNow;

  private WPIUtilJNIImpl() {}

  public static void checkMsvcRuntime() {}

  public static void writeStderr(String str) {
    System.err.print(str);
  }

  public static void enableMockTime() {
    mockTime = true;
  }

  public static void disableMockTime() {
    mockTime = false;
  }

  public static void setMockTime(long time) {
    mockNow = time;
  }

  /** The HAL clock, so timestamps match HALUtil.getFPGATime. */
  public static long now() {
    return mockTime ? mockNow : SimTime.fpgaTime();
  }

  /** Microseconds since the Unix epoch. */
  public static long getSystemTime() {
    return System.currentTimeMillis() * 1000;
  }

  public static int createEvent(boolean manualReset, boolean initialState) {
    return Sync.createEvent(manualReset, initialState);
  }

  public static void destroyEvent(int eventHandle) {
    Sync.destroyEvent(eventHandle);
  }

  public static void setEvent(int eventHandle) {
    Sync.setEvent(eventHandle);
  }

  public static void resetEvent(int eventHandle) {
    Sync.resetEvent(eventHandle);
  }

  public static int createSemaphore(int initialCount, int maximumCount) {
    return Sync.createSemaphore(initialCount, maximumCount);
  }

  public static void destroySemaphore(int semHandle) {
    Sync.destroySemaphore(semHandle);
  }

  public static boolean releaseSemaphore(int semHandle, int releaseCount) {
    return Sync.releaseSemaphore(semHandle, releaseCount);
  }

  public static void waitForObject(int handle) throws InterruptedException {
    if (!Sync.waitForObject(handle, -1, null)) {
      throw new InterruptedException("WaitForObject interrupted");
    }
  }

  /** Returns true if the wait timed out. */
  public static boolean waitForObjectTimeout(int handle, double timeout) throws InterruptedException {
    boolean[] timedOut = new boolean[1];
    if (!Sync.waitForObject(handle, timeout, timedOut) && !timedOut[0]) {
      throw new InterruptedException("WaitForObject interrupted");
    }
    return timedOut[0];
  }

  /**
   * Unlike the native, a destroyed handle also ends the wait with InterruptedException: that is how
   * listener threads of a closed object exit instead of spinning on the error bit.
   */
  private static int[] checked(Sync.WaitResult r) throws InterruptedException {
    int[] signaled = r.signaled();
    if (signaled.length == 0 && !r.timedOut()) {
      throw new InterruptedException("WaitForObjects interrupted");
    }
    for (int h : signaled) {
      if (h < 0) {
        throw new InterruptedException("WaitForObjects handle destroyed");
      }
    }
    return signaled;
  }

  public static int[] waitForObjects(int[] handles) throws InterruptedException {
    return checked(Sync.waitForObjects(handles, -1));
  }

  public static int[] waitForObjectsTimeout(int[] handles, double timeout)
      throws InterruptedException {
    return checked(Sync.waitForObjects(handles, timeout));
  }

  private static synchronized Frame frame(long handle) {
    return FRAMES.get(handle);
  }

  public static synchronized long allocateRawFrame() {
    FRAMES.put(nextFrame, new Frame());
    return nextFrame++;
  }

  public static synchronized void freeRawFrame(long frame) {
    FRAMES.remove(frame);
  }

  /** There are no native pointers in the browser JVM. */
  public static long getRawFrameDataPtr(long frame) {
    return 0;
  }

  public static void setRawFrameData(
      long frame,
      ByteBuffer data,
      int size,
      int width,
      int height,
      int stride,
      int pixelFormat) {
    Frame f = frame(frame);
    if (f != null) {
      f.data = data;
      f.size = size;
      f.width = width;
      f.height = height;
      f.stride = stride;
      f.pixelFormat = pixelFormat;
    }
  }

  public static void setRawFrameInfo(
      long frame, int size, int width, int height, int stride, int pixelFormat) {
    Frame f = frame(frame);
    if (f != null) {
      f.size = size;
      f.width = width;
      f.height = height;
      f.stride = stride;
      f.pixelFormat = pixelFormat;
    }
  }

  public static void setRawFrameTime(long frame, long time, int timeSource) {
    Frame f = frame(frame);
    if (f != null) {
      f.time = time;
      f.timeSource = timeSource;
    }
  }

  public static long createFileLogger(String file, long logger, String key) {
    return 0;
  }

  public static void freeFileLogger(long fileTail) {}
}
