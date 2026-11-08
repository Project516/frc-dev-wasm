package dev.project516.frcdevwasm.shim.networktables;

import dev.project516.frcdevwasm.shim.util.WPIUtilJNIImpl;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Mirrors a NetworkTables instance to the page (docs/bridge-protocol.md).
 *
 * <p>The bridge loop calls {@link #setSink} once, then {@link #handleMessage} for every message
 * from the page whose type starts with "nt." or is "hello". Output is queued and sent from a
 * daemon thread every 10 ms; {@link #flush()} sends it immediately. Nothing is queued until the
 * page's "hello" arrives.
 */
public final class NtBridge {
  private static final Object LOCK = new Object();
  private static final Object FLUSH = new Object();
  private static Consumer<String> sink;
  private static NtInstance instance;
  private static Thread flusher;

  private NtBridge() {}

  /** Mirrors the instance with this handle instead of the default instance. */
  public static void bind(int instanceHandle) {
    synchronized (LOCK) {
      instance = NtInstance.typed(instanceHandle, NtInstance.H_INSTANCE);
    }
  }

  /** Receives one JSON message object per call. A null sink stops mirroring. */
  public static void setSink(Consumer<String> jsonMessageSink) {
    synchronized (LOCK) {
      sink = jsonMessageSink;
      if (jsonMessageSink == null) {
        if (instance != null) {
          instance.bridgeStop();
        }
        return;
      }
      if (instance == null) {
        instance = NtInstance.getDefault();
      }
      if (flusher == null) {
        flusher = new Thread(NtBridge::flushLoop, "NtBridge");
        flusher.setDaemon(true);
        flusher.start();
      }
    }
  }

  /**
   * Handles a message from the page. "hello" starts mirroring and resends all state; "nt.set"
   * applies a value as a remote publish. Other types are ignored.
   */
  public static void handleMessage(String type, String dataJson) {
    NtInstance ii;
    synchronized (LOCK) {
      ii = instance;
    }
    if (ii == null) {
      return;
    }
    if (type.equals("hello")) {
      ii.bridgeStart();
    } else if (type.equals("nt.set")) {
      set(ii, dataJson);
    }
  }

  private static void set(NtInstance ii, String dataJson) {
    Object data;
    try {
      data = NtJson.parse(dataJson);
    } catch (NtJson.ParseException e) {
      return;
    }
    if (data instanceof Map<?, ?> m && m.get("name") instanceof String name && m.get("type") instanceof String type) {
      Object value = m.get("value");
      ii.bridgeSet(name, type, value == null ? NtJson.NULL : value);
    }
  }

  /** Sends all queued messages to the sink now. */
  public static void flush() {
    Consumer<String> out;
    NtInstance ii;
    synchronized (LOCK) {
      out = sink;
      ii = instance;
    }
    if (out == null || ii == null) {
      return;
    }
    synchronized (FLUSH) {
      for (String m : ii.bridgeDrain()) {
        out.accept(m);
      }
    }
  }

  private static void flushLoop() {
    while (true) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        return;
      }
      flush();
    }
  }

  /** Uses the simulation clock as the NetworkTables clock. */
  public static void useHalClock() {
    NtTime.setSource(WPIUtilJNIImpl::now, WPIUtilJNIImpl::getProgramStartTime);
  }
}
