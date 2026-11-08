package dev.project516.frcdevwasm.bridge;

/**
 * Message channel between the robot JVM and the page. The natives are implemented in JS by
 * packages/runtime. Protocol: docs/bridge-protocol.md.
 */
public final class Bridge {
  private Bridge() {}

  /** Sends a JSON array of messages to the page. */
  public static native void send(String json);

  /** Returns a JSON array of messages from the page, or "[]" when there are none. */
  public static native String receive();
}
