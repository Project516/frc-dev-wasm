package dev.project516.frcdevwasm.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** In-process transport for desktop runs and tests: inject incoming messages, capture outgoing. */
public final class LocalTransport implements Transport {
  private final List<String> incoming = new ArrayList<>();
  private final List<Map<String, Object>> sent = new ArrayList<>();
  private Consumer<Map<String, Object>> listener;

  /** Queues one message (a JSON object) for the robot. */
  public synchronized void inject(String json) {
    incoming.add(json);
  }

  /** Builds and queues one message. */
  public void inject(String type, String device, Map<String, Object> data) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    m.put("device", device);
    m.put("data", data);
    inject(Json.write(m));
  }

  /** Receives every outgoing message as it is sent, on the bridge thread. */
  public synchronized void onSend(Consumer<Map<String, Object>> listener) {
    this.listener = listener;
  }

  /** Copy of all outgoing messages so far. */
  public synchronized List<Map<String, Object>> sent() {
    return new ArrayList<>(sent);
  }

  public synchronized void clearSent() {
    sent.clear();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void send(String json) {
    List<Object> messages = (List<Object>) Json.parse(json);
    Consumer<Map<String, Object>> l;
    synchronized (this) {
      for (Object o : messages) {
        sent.add((Map<String, Object>) o);
      }
      l = listener;
    }
    if (l != null) {
      for (Object o : messages) {
        l.accept((Map<String, Object>) o);
      }
    }
  }

  @Override
  public synchronized String receive() {
    if (incoming.isEmpty()) {
      return "[]";
    }
    String out = "[" + String.join(",", incoming) + "]";
    incoming.clear();
    return out;
  }
}
