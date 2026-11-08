package dev.project516.frcdevwasm.smartdashboard;

import dev.project516.frcdevwasm.bridge.Bridge;
import dev.project516.frcdevwasm.bridge.Json;
import dev.project516.frcdevwasm.bridge.Transport;
import java.util.List;
import java.util.Map;

/** Moves an {@link NtClient}'s messages over the bridge every 10 ms. */
public final class NtLink {
  private static Transport testTransport;

  private final NtClient client;
  private final Transport transport;

  private NtLink(NtClient client, Transport transport) {
    this.client = client;
    this.transport = transport;
  }

  /** Replaces the bridge natives, and runs the loop only when step is called. For tests. */
  public static synchronized void useTransport(Transport transport) {
    testTransport = transport;
  }

  /** The link for a client that connects, or null in a JVM that has no bridge. */
  static synchronized NtLink open(NtClient client) {
    if (testTransport != null) {
      return new NtLink(client, testTransport);
    }
    if (!"js".equals(System.getProperty("frcdevwasm.bridge"))) {
      return null;
    }
    NtLink link = new NtLink(client, new Transport() {
      @Override
      public void send(String json) {
        Bridge.send(json);
      }

      @Override
      public String receive() {
        return Bridge.receive();
      }
    });
    Thread thread = new Thread(link::run, "frcdevwasm-nt-link");
    thread.setDaemon(true);
    thread.start();
    return link;
  }

  /** Sends what the client queued, then handles what the page sent. */
  public void step() {
    List<String> out = client.drainOutgoing();
    if (!out.isEmpty()) {
      transport.send("[" + String.join(",", out) + "]");
    }
    String in = transport.receive();
    if (in == null || in.length() <= 2) {
      return;
    }
    for (Object o : (List<?>) Json.parse(in)) {
      if (o instanceof Map<?, ?> m && m.get("type") instanceof String type) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = m.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        client.receive(type, data);
      }
    }
  }

  private void run() {
    while (true) {
      try {
        step();
        Thread.sleep(10);
      } catch (InterruptedException e) {
        return;
      } catch (RuntimeException e) {
        e.printStackTrace();
      }
    }
  }
}
