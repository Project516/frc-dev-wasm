package dev.project516.frcdevwasm.bridge;

/** Carries JSON message arrays between the robot JVM and the page. */
public interface Transport {
  /** Sends a JSON array of messages. */
  void send(String json);

  /** Returns a JSON array of pending incoming messages, or "[]". */
  String receive();
}
