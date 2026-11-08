package dev.project516.frcdevwasm.bridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Message loop between the robot JVM and the page. A daemon thread flushes queued output and
 * polls input every 10 ms. Protocol: docs/bridge-protocol.md.
 *
 * <p>System property dev.project516.frcdevwasm.bridge=js selects the Bridge natives; otherwise messages go to an
 * in-process {@link LocalTransport}.
 */
public final class BridgeLoop {
  /** Receives a message whose type matches the registration. */
  public interface Handler {
    void handle(String type, String device, Map<String, Object> data);
  }

  private static final class Msg {
    final String type;
    final String device;
    final Map<String, Object> data;
    final String raw;

    Msg(String type, String device, Map<String, Object> data) {
      this.type = type;
      this.device = device;
      this.data = data;
      this.raw = null;
    }

    Msg(String raw) {
      this.type = "";
      this.device = "";
      this.data = null;
      this.raw = raw;
    }
  }

  private static final long PERIOD_MS = 10;

  private static final Object QUEUE_LOCK = new Object();
  private static List<Msg> pending = new ArrayList<>();
  private static Map<String, Msg> deltas = new HashMap<>();
  private static final Object FLUSH_LOCK = new Object();

  private static final Map<String, Handler> HANDLERS = new HashMap<>();
  private static final List<Handler> PREFIX_HANDLERS = new CopyOnWriteArrayList<>();
  private static final List<String> PREFIXES = new CopyOnWriteArrayList<>();
  private static final List<Runnable> HELLO_HOOKS = new CopyOnWriteArrayList<>();
  private static final List<Handler> UNKNOWN_HANDLERS = new CopyOnWriteArrayList<>();

  private static Transport transport;
  private static boolean started;
  private static volatile int exitCode;
  private static final Object EXIT_LOCK = new Object();
  private static boolean exitSent;

  private BridgeLoop() {}

  /** The in-process transport, creating it if no transport is selected yet. */
  public static synchronized LocalTransport local() {
    if (transport == null) {
      transport = new LocalTransport();
    }
    if (!(transport instanceof LocalTransport l)) {
      throw new IllegalStateException("bridge uses the js transport");
    }
    return l;
  }

  /** Replaces the transport. Must be called before start. */
  public static synchronized void setTransport(Transport t) {
    transport = t;
  }

  /** Starts the loop thread, console redirect and exit hook. Safe to call repeatedly. */
  public static synchronized void start() {
    if (started) {
      return;
    }
    started = true;
    boolean js = "js".equals(System.getProperty("frcdevwasm.bridge"));
    if (transport == null) {
      transport = js ? new NativeTransport() : new LocalTransport();
    }
    redirectConsole(!js);
    // simulateJava and run are Gradle JavaExec tasks, which give the program an empty stdin
    System.setIn(InputStream.nullInputStream());
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (t, e) -> {
          if ("main".equals(t.getName())) {
            exitCode = 1;
          }
          if (previous != null) {
            previous.uncaughtException(t, e);
          } else {
            e.printStackTrace();
          }
        });
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(BridgeLoop::sendExit, "frcdevwasm-exit"));
    Thread t = new Thread(BridgeLoop::run, "frcdevwasm-bridge");
    t.setDaemon(true);
    t.start();
  }

  public static synchronized boolean isStarted() {
    return started;
  }

  /**
   * Registers a handler for page-to-Java messages. A type ending in "." (such as "nt.") matches
   * every type with that prefix.
   */
  public static synchronized void on(String type, Handler h) {
    if (type.endsWith(".")) {
      PREFIXES.add(type);
      PREFIX_HANDLERS.add(h);
    } else {
      HANDLERS.put(type, h);
    }
  }

  /** Receives messages whose type has no other handler (SimDevice types are arbitrary). */
  public static void onUnknown(Handler h) {
    UNKNOWN_HANDLERS.add(h);
  }

  /** Gives a message to the unknown-type handlers, for types shared with other providers. */
  public static void dispatchUnknown(String type, String device, Map<String, Object> data) {
    for (Handler u : UNKNOWN_HANDLERS) {
      u.handle(type, device, data);
    }
  }

  /** Runs when the page says hello: resend full state. */
  public static void onHello(Runnable r) {
    HELLO_HOOKS.add(r);
  }

  /** Queues a message. Order is kept relative to other send calls. */
  public static void send(String type, String device, Map<String, Object> data) {
    synchronized (QUEUE_LOCK) {
      pending.add(new Msg(type, device, data));
    }
  }

  /** Queues a message that is already a JSON object, in order with the other send calls. */
  public static void sendJson(String messageJson) {
    synchronized (QUEUE_LOCK) {
      pending.add(new Msg(messageJson));
    }
  }

  /**
   * Queues one changed key. Deltas for the same type and device that are queued before the next
   * flush are merged into a single message.
   */
  public static void sendDelta(String type, String device, String key, Object value) {
    String id = type + '\u0000' + device;
    synchronized (QUEUE_LOCK) {
      Msg m = deltas.get(id);
      if (m == null) {
        m = new Msg(type, device, new LinkedHashMap<>());
        deltas.put(id, m);
        pending.add(m);
      }
      m.data.put(key, value);
    }
  }

  /** Queues several keys of one device as one message, merging like sendDelta. */
  public static void sendDeltas(String type, String device, Map<String, Object> data) {
    String id = type + '\u0000' + device;
    synchronized (QUEUE_LOCK) {
      Msg m = deltas.get(id);
      if (m == null) {
        m = new Msg(type, device, new LinkedHashMap<>());
        deltas.put(id, m);
        pending.add(m);
      }
      m.data.putAll(data);
    }
  }

  static void sendConsole(String stream, String text) {
    synchronized (QUEUE_LOCK) {
      if (!pending.isEmpty()) {
        Msg last = pending.get(pending.size() - 1);
        if (last.raw == null && last.type.equals("console") && stream.equals(last.data.get("stream"))) {
          last.data.put("text", last.data.get("text") + text);
          return;
        }
      }
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("stream", stream);
      data.put("text", text);
      pending.add(new Msg("console", "", data));
    }
  }

  /** Sends everything queued now. Usable from any thread. */
  public static void flush() {
    synchronized (FLUSH_LOCK) {
      List<Msg> batch;
      synchronized (QUEUE_LOCK) {
        if (pending.isEmpty()) {
          return;
        }
        batch = pending;
        pending = new ArrayList<>();
        deltas = new HashMap<>();
      }
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < batch.size(); i++) {
        Msg m = batch.get(i);
        if (i > 0) {
          sb.append(',');
        }
        if (m.raw != null) {
          sb.append(m.raw);
          continue;
        }
        sb.append("{\"type\":");
        Json.write(sb, m.type);
        sb.append(",\"device\":");
        Json.write(sb, m.device);
        sb.append(",\"data\":");
        Json.write(sb, m.data);
        sb.append('}');
      }
      sb.append(']');
      transport.send(sb.toString());
    }
  }

  /** Receives and dispatches pending incoming messages now. Usable from any thread. */
  @SuppressWarnings("unchecked")
  public static void poll() {
    String json = transport.receive();
    if (json == null || json.length() <= 2) {
      return;
    }
    Object parsed;
    try {
      parsed = Json.parse(json);
    } catch (RuntimeException e) {
      System.err.println("[frc-dev-wasm] bad bridge input: " + e.getMessage());
      return;
    }
    if (!(parsed instanceof List<?> list)) {
      return;
    }
    for (Object o : list) {
      if (o instanceof Map<?, ?> m && m.get("type") instanceof String type) {
        Object device = m.get("device");
        Object data = m.get("data");
        dispatch(
            type,
            device instanceof String d ? d : "",
            data instanceof Map<?, ?> dm ? (Map<String, Object>) dm : new LinkedHashMap<>());
      }
    }
  }

  private static void dispatch(String type, String device, Map<String, Object> data) {
    try {
      switch (type) {
        case "hello" -> {
          for (Runnable r : HELLO_HOOKS) {
            r.run();
          }
        }
        case "stop" -> exit(0);
        default -> {
          Handler h;
          synchronized (BridgeLoop.class) {
            h = HANDLERS.get(type);
          }
          boolean handled = h != null;
          if (handled) {
            h.handle(type, device, data);
          }
          for (int i = 0; i < PREFIXES.size(); i++) {
            if (type.startsWith(PREFIXES.get(i))) {
              handled = true;
              PREFIX_HANDLERS.get(i).handle(type, device, data);
            }
          }
          if (!handled) {
            for (Handler u : UNKNOWN_HANDLERS) {
              u.handle(type, device, data);
            }
          }
        }
      }
    } catch (RuntimeException e) {
      e.printStackTrace();
    }
  }

  /** Tells the page the JVM is ending, once, with whatever exit code is known by now. */
  private static void sendExit() {
    synchronized (EXIT_LOCK) {
      if (exitSent) {
        return;
      }
      exitSent = true;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("code", exitCode);
    send("exit", "", data);
    try {
      flush();
    } catch (RuntimeException e) {
      // the page is gone, nothing left to tell
    }
  }

  /** Sends the exit message, then ends the JVM. */
  public static void exit(int code) {
    exitCode = code;
    sendExit();
    System.exit(code);
  }

  private static void run() {
    while (true) {
      try {
        flush();
        poll();
        Thread.sleep(PERIOD_MS);
      } catch (InterruptedException e) {
        return;
      } catch (RuntimeException e) {
        e.printStackTrace();
      }
    }
  }

  private static void redirectConsole(boolean tee) {
    PrintStream out = System.out;
    PrintStream err = System.err;
    System.setOut(stream("out", tee ? out : null));
    System.setErr(stream("err", tee ? err : null));
  }

  private static PrintStream stream(String name, PrintStream tee) {
    return new PrintStream(
        new OutputStream() {
          @Override
          public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
          }

          @Override
          public void write(byte[] b, int off, int len) {
            if (tee != null) {
              tee.write(b, off, len);
            }
            sendConsole(name, new String(b, off, len, StandardCharsets.UTF_8));
          }

          @Override
          public void flush() {
            if (tee != null) {
              tee.flush();
            }
          }
        },
        false,
        StandardCharsets.UTF_8);
  }
}
