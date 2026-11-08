package dev.project516.frcdevwasm.shim.util.datalog;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.sim.SimTime;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * WPILOG writer for the DataLog natives (DataLogJNI.cpp, wpiutil DataLog.cpp). Records are
 * encoded in memory; file backed logs write them out on flush and close, in memory logs hand them
 * out through copyWriteBuffer.
 */
public final class DataLogJNIImpl {
  private static final class Entry {
    final int id;
    final String name;
    final String type;

    Entry(int id, String name, String type) {
      this.id = id;
      this.name = name;
      this.type = type;
    }
  }

  private static final class Log {
    byte[] buf = new byte[1024];
    int size;
    OutputStream out;
    final boolean memory;
    boolean paused;
    boolean stopped;
    volatile boolean closed;
    int nextId = 1;
    final Map<String, Entry> byName = new HashMap<>();
    final Map<Integer, Entry> byId = new HashMap<>();
    final Set<String> schemas = new HashSet<>();

    Log(boolean memory, String extraHeader) {
      this.memory = memory;
      byte[] extra = extraHeader == null ? new byte[0] : extraHeader.getBytes(StandardCharsets.UTF_8);
      raw("WPILOG".getBytes(StandardCharsets.US_ASCII));
      le(0x0100, 2);
      le(extra.length, 4);
      raw(extra);
    }

    void raw(byte[] b) {
      raw(b, 0, b.length);
    }

    void raw(byte[] b, int off, int len) {
      if (size + len > buf.length) {
        buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + len));
      }
      System.arraycopy(b, off, buf, size, len);
      size += len;
    }

    void le(long v, int bytes) {
      byte[] b = new byte[bytes];
      for (int i = 0; i < bytes; i++) {
        b[i] = (byte) (v >>> (8 * i));
      }
      raw(b);
    }

    static int bytesFor(long v) {
      int n = 1;
      while (n < 8 && (v >>> (8 * n)) != 0) {
        n++;
      }
      return n;
    }

    /** Writes one record: header bitfield, entry id, payload size, timestamp, payload. */
    synchronized void record(int entry, long timestamp, byte[] payload, int off, int len) {
      if (stopped || paused || (!memory && out == null)) {
        return;
      }
      if (timestamp == 0) {
        timestamp = SimTime.fpgaTime();
      }
      int idLen = bytesFor(entry & 0xffffffffL);
      int sizeLen = bytesFor(len);
      int tsLen = Math.max(bytesFor(timestamp), 1);
      raw(new byte[] {(byte) ((idLen - 1) | ((sizeLen - 1) << 2) | ((tsLen - 1) << 4))});
      le(entry, idLen);
      le(len, sizeLen);
      le(timestamp, tsLen);
      raw(payload, off, len);
    }

    void record(int entry, long timestamp, byte[] payload) {
      record(entry, timestamp, payload, 0, payload.length);
    }

    // Without a file (it could not be opened or a write failed) records are dropped, so memory stays bounded.
    synchronized void flushToFile() {
      if (memory) {
        return;
      }
      if (out != null) {
        try {
          out.write(buf, 0, size);
          out.flush();
        } catch (IOException e) {
          out = null;
        }
      }
      size = 0;
    }
  }

  private static final Map<Long, Log> LOGS = new HashMap<>();
  private static long nextHandle = 1;

  private DataLogJNIImpl() {}

  private static synchronized long add(Log log) {
    LOGS.put(nextHandle, log);
    return nextHandle++;
  }

  private static synchronized Log log(long impl) {
    Log l = LOGS.get(impl);
    if (l == null) {
      throw new NullPointerException("impl is null");
    }
    return l;
  }

  public static long bgCreate(String dir, String filename, double period, String extraHeader) {
    Log l = new Log(false, extraHeader);
    String name =
        filename == null || filename.isEmpty() ? "FRC_" + System.currentTimeMillis() + ".wpilog" : filename;
    try {
      File d = dir == null || dir.isEmpty() ? new File(".") : new File(dir);
      d.mkdirs();
      File file = new File(d, name);
      l.out = new FileOutputStream(file);
      if (BridgeLoop.isStarted()) {
        BridgeLoop.send("datalog.file", "", Map.of("path", file.getAbsolutePath()));
      }
      flushEvery(l, period);
    } catch (IOException e) {
      l.out = null;
    }
    return add(l);
  }

  /** The native writer flushes on its own thread, so a log survives the program being killed. */
  private static void flushEvery(Log l, double periodSeconds) {
    long millis = Math.max(100, (long) (periodSeconds * 1000));
    Thread t =
        new Thread(
            () -> {
              while (!l.closed) {
                try {
                  Thread.sleep(millis);
                } catch (InterruptedException e) {
                  return;
                }
                l.flushToFile();
              }
            },
            "frcdevwasm-datalog");
    t.setDaemon(true);
    t.start();
  }

  public static void bgSetFilename(long impl, String filename) {}

  public static long fgCreate(String filename, String extraHeader) throws IOException {
    Log l = new Log(false, extraHeader);
    l.out = new FileOutputStream(filename);
    return add(l);
  }

  public static long fgCreateMemory(String extraHeader) {
    return add(new Log(true, extraHeader));
  }

  public static void flush(long impl) {
    log(impl).flushToFile();
  }

  /** Copies pending bytes starting at pos into buf. Returns 0 and clears when pos is at the end. */
  public static int copyWriteBuffer(long impl, byte[] buf, int pos) {
    Log l = log(impl);
    synchronized (l) {
      if (pos < 0 || pos >= l.size) {
        l.size = 0;
        return 0;
      }
      int qty = Math.min(buf.length, l.size - pos);
      System.arraycopy(l.buf, pos, buf, 0, qty);
      return qty;
    }
  }

  public static void pause(long impl) {
    log(impl).paused = true;
  }

  public static void resume(long impl) {
    log(impl).paused = false;
  }

  public static void stop(long impl) {
    Log l = log(impl);
    l.flushToFile();
    l.stopped = true;
  }

  public static void close(long impl) {
    Log l;
    synchronized (DataLogJNIImpl.class) {
      l = LOGS.remove(impl);
    }
    if (l != null) {
      l.closed = true;
      l.flushToFile();
      try {
        if (l.out != null) {
          l.out.close();
        }
      } catch (IOException e) {
        // closing is best effort
      }
    }
  }

  private static byte[] str(String s) {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[4 + b.length];
    for (int i = 0; i < 4; i++) {
      out[i] = (byte) (b.length >>> (8 * i));
    }
    System.arraycopy(b, 0, out, 4, b.length);
    return out;
  }

  private static byte[] concat(byte[]... parts) {
    int n = 0;
    for (byte[] p : parts) {
      n += p.length;
    }
    byte[] out = new byte[n];
    int at = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, at, p.length);
      at += p.length;
    }
    return out;
  }

  private static byte[] u32(int v) {
    return new byte[] {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
  }

  public static void addSchema(long impl, String name, String type, byte[] schema, long timestamp) {
    Log l = log(impl);
    String key = "/.schema/" + name;
    synchronized (l) {
      if (!l.schemas.add(key)) {
        return;
      }
    }
    int id = start(impl, key, type, "", timestamp);
    l.record(id, timestamp, schema);
  }

  public static void addSchemaString(
      long impl, String name, String type, String schema, long timestamp) {
    addSchema(impl, name, type, schema.getBytes(StandardCharsets.UTF_8), timestamp);
  }

  public static int start(long impl, String name, String type, String metadata, long timestamp) {
    Log l = log(impl);
    synchronized (l) {
      Entry e = l.byName.get(name);
      if (e != null) {
        return e.id;
      }
      e = new Entry(l.nextId++, name, type);
      l.byName.put(name, e);
      l.byId.put(e.id, e);
      l.record(
          0,
          timestamp,
          concat(new byte[] {0}, u32(e.id), str(name), str(type), str(metadata == null ? "" : metadata)));
      return e.id;
    }
  }

  public static void finish(long impl, int entry, long timestamp) {
    Log l = log(impl);
    synchronized (l) {
      Entry e = l.byId.remove(entry);
      if (e != null) {
        l.byName.remove(e.name);
        l.record(0, timestamp, concat(new byte[] {1}, u32(entry)));
      }
    }
  }

  public static void setMetadata(long impl, int entry, String metadata, long timestamp) {
    log(impl).record(0, timestamp, concat(new byte[] {2}, u32(entry), str(metadata)));
  }

  public static void appendRaw(long impl, int entry, byte[] data, int start, int len, long timestamp) {
    log(impl).record(entry, timestamp, data, start, len);
  }

  public static void appendRawBuffer(
      long impl, int entry, ByteBuffer data, int start, int len, long timestamp) {
    byte[] b = new byte[len];
    ByteBuffer d = data.duplicate();
    d.position(start);
    d.get(b);
    log(impl).record(entry, timestamp, b);
  }

  public static void appendBoolean(long impl, int entry, boolean value, long timestamp) {
    log(impl).record(entry, timestamp, new byte[] {(byte) (value ? 1 : 0)});
  }

  public static void appendInteger(long impl, int entry, long value, long timestamp) {
    log(impl).record(entry, timestamp, ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(value).array());
  }

  public static void appendFloat(long impl, int entry, float value, long timestamp) {
    log(impl).record(entry, timestamp, ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(value).array());
  }

  public static void appendDouble(long impl, int entry, double value, long timestamp) {
    log(impl).record(entry, timestamp, ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(value).array());
  }

  public static void appendString(long impl, int entry, String value, long timestamp) {
    log(impl).record(entry, timestamp, value.getBytes(StandardCharsets.UTF_8));
  }

  public static void appendBooleanArray(long impl, int entry, boolean[] value, long timestamp) {
    byte[] b = new byte[value.length];
    for (int i = 0; i < b.length; i++) {
      b[i] = (byte) (value[i] ? 1 : 0);
    }
    log(impl).record(entry, timestamp, b);
  }

  public static void appendIntegerArray(long impl, int entry, long[] value, long timestamp) {
    ByteBuffer b = ByteBuffer.allocate(value.length * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    for (long v : value) {
      b.putLong(v);
    }
    log(impl).record(entry, timestamp, b.array());
  }

  public static void appendFloatArray(long impl, int entry, float[] value, long timestamp) {
    ByteBuffer b = ByteBuffer.allocate(value.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    for (float v : value) {
      b.putFloat(v);
    }
    log(impl).record(entry, timestamp, b.array());
  }

  public static void appendDoubleArray(long impl, int entry, double[] value, long timestamp) {
    ByteBuffer b = ByteBuffer.allocate(value.length * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    for (double v : value) {
      b.putDouble(v);
    }
    log(impl).record(entry, timestamp, b.array());
  }

  public static void appendStringArray(long impl, int entry, String[] value, long timestamp) {
    byte[][] parts = new byte[value.length + 1][];
    parts[0] = u32(value.length);
    for (int i = 0; i < value.length; i++) {
      parts[i + 1] = str(value[i]);
    }
    log(impl).record(entry, timestamp, concat(parts));
  }
}
