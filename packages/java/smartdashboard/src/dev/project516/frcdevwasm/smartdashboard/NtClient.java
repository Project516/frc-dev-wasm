package dev.project516.frcdevwasm.smartdashboard;

import dev.project516.frcdevwasm.bridge.Json;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The entry table behind SmartDashboard's NetworkTables 3 API, kept in step with the NT4 server
 * in the page. Entries are named values, as in ntcore 2020: a remote topic shows up once it has a
 * value, and a local set publishes it to the server. See "NetworkTables client" in
 * docs/bridge-protocol.md.
 */
public final class NtClient {
  public static final int UNASSIGNED = 0;
  public static final int BOOLEAN = 0x01;
  public static final int DOUBLE = 0x02;
  public static final int STRING = 0x04;
  public static final int RAW = 0x08;
  public static final int BOOLEAN_ARRAY = 0x10;
  public static final int DOUBLE_ARRAY = 0x20;
  public static final int STRING_ARRAY = 0x40;

  public static final int IMMEDIATE = 0x01;
  public static final int LOCAL = 0x02;
  public static final int NEW = 0x04;
  public static final int DELETE = 0x08;
  public static final int UPDATE = 0x10;
  public static final int FLAGS = 0x20;
  private static final int CHANGES = NEW | DELETE | UPDATE | FLAGS;

  public static final int PERSISTENT = 0x01;

  public static final int MODE_NONE = 0;
  public static final int MODE_SERVER = 0x01;
  public static final int MODE_CLIENT = 0x02;
  public static final int MODE_LOCAL = 0x10;

  private static final int HANDLE_SHIFT = 20;
  private static final int MAX_INSTANCES = 16;
  private static final NtClient[] INSTANCES = new NtClient[MAX_INSTANCES];
  private static int defaultIndex = -1;

  /** A named value. A remote topic that has no value yet is not visible to the application. */
  public static final class Entry {
    public final int handle;
    public final String name;
    private int type;
    private Object value;
    private long time;
    private int flags;
    private boolean localSet;
    private boolean published;
    private int remoteId = -1;
    private String remoteType;

    private Entry(int handle, String name) {
      this.handle = handle;
      this.name = name;
    }

    public boolean exists() {
      return value != null;
    }

    public int type() {
      return exists() ? type : UNASSIGNED;
    }

    public Object value() {
      return value;
    }

    public long time() {
      return time;
    }

    public int flags() {
      return flags;
    }

    private int number() {
      return handle & ((1 << HANDLE_SHIFT) - 1);
    }
  }

  public static final class Event {
    public final int listener;
    public final int entry;
    public final String name;
    public final int type;
    public final Object value;
    public final long time;
    public final int flags;

    private Event(int listener, Entry e, Object value, int flags) {
      this.listener = listener;
      this.entry = e.handle;
      this.name = e.name;
      this.type = e.type;
      this.value = value;
      this.time = e.time;
      this.flags = flags;
    }
  }

  public static final class ConnectionEvent {
    public final int listener;
    public final boolean connected;

    private ConnectionEvent(int listener, boolean connected) {
      this.listener = listener;
      this.connected = connected;
    }
  }

  /** A queue of events that a thread polls. */
  private static final class Poller<E> {
    final ArrayDeque<E> queue = new ArrayDeque<>();
    boolean cancelled;
    boolean destroyed;
  }

  private static final class Listener {
    final int handle;
    final Poller<Event> poller;
    final String prefix;
    final int entry;
    final int flags;

    Listener(int handle, Poller<Event> poller, String prefix, int entry, int flags) {
      this.handle = handle;
      this.poller = poller;
      this.prefix = prefix;
      this.entry = entry;
      this.flags = flags;
    }

    boolean matches(Entry e) {
      return prefix != null ? e.name.startsWith(prefix) : e.handle == entry;
    }
  }

  private final int index;
  private final ArrayList<Entry> entries = new ArrayList<>();
  private final HashMap<String, Entry> byName = new HashMap<>();
  private final HashMap<Integer, Entry> byRemoteId = new HashMap<>();
  private final HashMap<Integer, Poller<Event>> entryPollers = new HashMap<>();
  private final HashMap<Integer, Listener> listeners = new HashMap<>();
  private final HashMap<Integer, Poller<ConnectionEvent>> connectionPollers = new HashMap<>();
  private final HashMap<Integer, Poller<ConnectionEvent>> connectionListeners = new HashMap<>();
  private final HashMap<Integer, Poller<Object>> idlePollers = new HashMap<>();
  private final ArrayList<String> outgoing = new ArrayList<>();
  private int counter;
  private int mode = MODE_NONE;
  private boolean connected;
  private String identity = "";
  private NtLink link;

  private NtClient(int index) {
    this.index = index;
  }

  // instances

  public static synchronized NtClient getDefault() {
    if (defaultIndex < 0 || INSTANCES[defaultIndex] == null) {
      defaultIndex = create().index;
    }
    return INSTANCES[defaultIndex];
  }

  public static synchronized NtClient create() {
    for (int i = 0; i < MAX_INSTANCES; i++) {
      if (INSTANCES[i] == null) {
        INSTANCES[i] = new NtClient(i);
        return INSTANCES[i];
      }
    }
    throw new IllegalStateException("too many NetworkTables instances");
  }

  /** The instance a handle of any kind belongs to, or null. */
  public static synchronized NtClient of(int handle) {
    int i = (handle >>> HANDLE_SHIFT) - 1;
    return i >= 0 && i < MAX_INSTANCES ? INSTANCES[i] : null;
  }

  public static synchronized void destroy(int instanceHandle) {
    NtClient c = of(instanceHandle);
    if (c == null) {
      return;
    }
    c.stopClient();
    INSTANCES[c.index] = null;
    if (defaultIndex == c.index) {
      defaultIndex = -1;
    }
  }

  public int handle() {
    return (index + 1) << HANDLE_SHIFT;
  }

  private int newHandle() {
    return handle() | ++counter;
  }

  // entries

  public synchronized int getEntry(String name) {
    return entryByName(name).handle;
  }

  private Entry entryByName(String name) {
    Entry e = byName.get(name);
    if (e == null) {
      e = new Entry(handle() | (entries.size() + 1), name);
      entries.add(e);
      byName.put(name, e);
    }
    return e;
  }

  /** The entry for a handle, or null. */
  public synchronized Entry entry(int handle) {
    int n = handle & ((1 << HANDLE_SHIFT) - 1);
    Entry e = n >= 1 && n <= entries.size() ? entries.get(n - 1) : null;
    return e != null && e.handle == handle ? e : null;
  }

  /** Entries with a value whose name starts with the prefix. A zero type mask matches every type. */
  public synchronized List<Entry> getEntries(String prefix, int types) {
    List<Entry> out = new ArrayList<>();
    for (Entry e : entries) {
      if (e.exists() && e.name.startsWith(prefix) && (types == 0 || (types & e.type) != 0)) {
        out.add(e);
      }
    }
    return out;
  }

  /**
   * Sets a value. Returns false when the entry already holds another type, unless force is set
   * and the server does not know the entry.
   */
  public synchronized boolean set(int handle, int type, Object value, boolean force) {
    Entry e = entry(handle);
    if (e == null) {
      return false;
    }
    boolean typed = e.exists() || e.remoteId >= 0;
    if (typed && e.type != type) {
      if (!force || e.remoteId >= 0) {
        return false;
      }
      deleteLocked(e, LOCAL);
    }
    boolean isNew = !e.exists();
    if (!isNew && equalValues(type, e.value, value)) {
      return true;
    }
    e.type = type;
    e.value = value;
    e.time = now();
    e.localSet = true;
    notifyEntry(e, (isNew ? NEW : UPDATE) | LOCAL, value);
    publish(e);
    return true;
  }

  /** Sets the value only if the entry has none. Returns false when it holds another type. */
  public synchronized boolean setDefault(int handle, int type, Object value) {
    Entry e = entry(handle);
    if (e == null) {
      return false;
    }
    if (e.exists()) {
      return e.type == type;
    }
    return set(handle, type, value, false);
  }

  public synchronized void setFlags(int handle, int flags) {
    Entry e = entry(handle);
    if (e == null || e.flags == flags) {
      return;
    }
    e.flags = flags;
    if (e.exists()) {
      notifyEntry(e, FLAGS | LOCAL, e.value);
    }
    if (e.published) {
      send("nt.properties", Map.of("name", e.name, "properties", Map.of("persistent", (flags & PERSISTENT) != 0)));
    }
  }

  public synchronized void delete(int handle) {
    Entry e = entry(handle);
    if (e != null) {
      deleteLocked(e, LOCAL);
    }
  }

  public synchronized void deleteAll() {
    for (Entry e : entries) {
      deleteLocked(e, LOCAL);
    }
  }

  private void deleteLocked(Entry e, int origin) {
    Object old = e.value;
    if (e.published) {
      send("nt.unannounce", Map.of("id", e.number(), "name", e.name));
      e.published = false;
    }
    e.localSet = false;
    if (old == null) {
      return;
    }
    e.value = null;
    notifyEntry(e, DELETE | origin, old);
  }

  // listeners

  public synchronized int createEntryPoller() {
    int h = newHandle();
    entryPollers.put(h, new Poller<>());
    return h;
  }

  public synchronized void destroyEntryPoller(int poller) {
    Poller<Event> p = entryPollers.remove(poller);
    if (p != null) {
      p.destroyed = true;
      listeners.values().removeIf(l -> l.poller == p);
      notifyAll();
    }
  }

  public synchronized int addEntryListener(int poller, String prefix, int flags) {
    return addListener(poller, prefix, 0, flags);
  }

  public synchronized int addEntryListener(int poller, int entry, int flags) {
    return addListener(poller, null, entry, flags);
  }

  private int addListener(int poller, String prefix, int entry, int flags) {
    Poller<Event> p = entryPollers.get(poller);
    if (p == null) {
      return 0;
    }
    Listener l = new Listener(newHandle(), p, prefix, entry, flags);
    listeners.put(l.handle, l);
    if ((flags & IMMEDIATE) != 0 && (flags & NEW) != 0) {
      for (Entry e : entries) {
        if (e.exists() && l.matches(e)) {
          p.queue.add(new Event(l.handle, e, e.value, IMMEDIATE | NEW));
        }
      }
      notifyAll();
    }
    return l.handle;
  }

  public synchronized void removeEntryListener(int listener) {
    listeners.remove(listener);
  }

  /** Blocks until events arrive, the poll is cancelled or the timeout passes. A negative timeout waits for good. */
  public Event[] pollEntries(int poller, long timeoutMs) throws InterruptedException {
    synchronized (this) {
      return drain(entryPollers.get(poller), timeoutMs, new Event[0]);
    }
  }

  public synchronized void cancelEntryPoll(int poller) {
    cancel(entryPollers.get(poller));
  }

  public synchronized int createConnectionPoller() {
    int h = newHandle();
    connectionPollers.put(h, new Poller<>());
    return h;
  }

  public synchronized void destroyConnectionPoller(int poller) {
    Poller<ConnectionEvent> p = connectionPollers.remove(poller);
    if (p != null) {
      p.destroyed = true;
      connectionListeners.values().removeIf(q -> q == p);
      notifyAll();
    }
  }

  public synchronized int addConnectionListener(int poller, boolean immediate) {
    Poller<ConnectionEvent> p = connectionPollers.get(poller);
    if (p == null) {
      return 0;
    }
    int h = newHandle();
    connectionListeners.put(h, p);
    if (immediate && connected) {
      p.queue.add(new ConnectionEvent(h, true));
      notifyAll();
    }
    return h;
  }

  public synchronized void removeConnectionListener(int listener) {
    connectionListeners.remove(listener);
  }

  public ConnectionEvent[] pollConnections(int poller, long timeoutMs) throws InterruptedException {
    synchronized (this) {
      return drain(connectionPollers.get(poller), timeoutMs, new ConnectionEvent[0]);
    }
  }

  public synchronized void cancelConnectionPoll(int poller) {
    cancel(connectionPollers.get(poller));
  }

  /** A poller that never gets events, for the parts of the API that have none here (RPC, logging). */
  public synchronized int createIdlePoller() {
    int h = newHandle();
    idlePollers.put(h, new Poller<>());
    return h;
  }

  public synchronized void destroyIdlePoller(int poller) {
    Poller<Object> p = idlePollers.remove(poller);
    if (p != null) {
      p.destroyed = true;
      notifyAll();
    }
  }

  public void pollIdle(int poller, long timeoutMs) throws InterruptedException {
    synchronized (this) {
      drain(idlePollers.get(poller), timeoutMs, new Object[0]);
    }
  }

  public synchronized void cancelIdlePoll(int poller) {
    cancel(idlePollers.get(poller));
  }

  private void cancel(Poller<?> p) {
    if (p != null) {
      p.cancelled = true;
      notifyAll();
    }
  }

  private <E> E[] drain(Poller<E> p, long timeoutMs, E[] type) throws InterruptedException {
    if (p == null) {
      return type;
    }
    long end = timeoutMs < 0 ? Long.MAX_VALUE : System.nanoTime() / 1_000_000 + timeoutMs;
    while (p.queue.isEmpty() && !p.cancelled && !p.destroyed) {
      long left = timeoutMs < 0 ? 0 : end - System.nanoTime() / 1_000_000;
      if (timeoutMs >= 0 && left <= 0) {
        break;
      }
      wait(left);
    }
    p.cancelled = false;
    E[] out = p.queue.toArray(type);
    p.queue.clear();
    return out;
  }

  private void notifyEntry(Entry e, int flags, Object value) {
    for (Listener l : listeners.values()) {
      if (l.matches(e) && (l.flags & flags & CHANGES) != 0 && ((flags & LOCAL) == 0 || (l.flags & LOCAL) != 0)) {
        l.poller.queue.add(new Event(l.handle, e, value, flags));
      }
    }
    notifyAll();
  }

  private void notifyConnection(boolean up) {
    for (Map.Entry<Integer, Poller<ConnectionEvent>> l : connectionListeners.entrySet()) {
      l.getValue().queue.add(new ConnectionEvent(l.getKey(), up));
    }
    notifyAll();
  }

  // network

  public synchronized int mode() {
    return mode;
  }

  public synchronized void startLocal() {
    if (mode == MODE_NONE) {
      mode = MODE_LOCAL;
    }
  }

  public synchronized void stopLocal() {
    if (mode == MODE_LOCAL) {
      mode = MODE_NONE;
    }
  }

  public synchronized void startServer() {
    if (mode == MODE_NONE) {
      mode = MODE_SERVER;
    }
  }

  public synchronized void stopServer() {
    if (mode == MODE_SERVER) {
      mode = MODE_NONE;
    }
  }

  public synchronized void setIdentity(String identity) {
    this.identity = identity;
  }

  /** Connects to the page's server through the bridge, when this JVM has one. */
  public synchronized void startClient() {
    if (mode == MODE_CLIENT) {
      return;
    }
    mode = MODE_CLIENT;
    if (link == null) {
      link = NtLink.open(this);
    }
    if (link != null) {
      send("nt.hello", Map.of("identity", identity));
    }
  }

  public synchronized void stopClient() {
    if (mode != MODE_CLIENT) {
      return;
    }
    mode = MODE_NONE;
    if (link != null) {
      send("nt.bye", Map.of());
    }
    disconnected();
  }

  public synchronized boolean isConnected() {
    return connected;
  }

  /** Runs one exchange with the page now, instead of waiting for the link's thread. */
  public void pump() {
    NtLink l;
    synchronized (this) {
      l = link;
    }
    if (l != null) {
      l.step();
    }
  }

  /** Server time cannot differ from this clock, since both are on the page. */
  public static long now() {
    return System.nanoTime() / 1000;
  }

  // bridge messages

  /** Takes the messages queued for the page, each a JSON object. */
  synchronized List<String> drainOutgoing() {
    List<String> out = new ArrayList<>(outgoing);
    outgoing.clear();
    return out;
  }

  private void send(String type, Map<String, Object> data) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    m.put("device", "");
    m.put("data", data);
    outgoing.add(Json.write(m));
  }

  /** Handles a message from the page. */
  @SuppressWarnings("unchecked")
  synchronized void receive(String type, Map<String, Object> data) {
    switch (type) {
      case "nt.connected" -> connected();
      case "nt.disconnected" -> disconnected();
      case "nt.announce" -> announced(Json.toLong(data.get("id")), (String) data.get("name"), (String) data.get("type"),
          data.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of());
      case "nt.unannounce" -> unannounced(Json.toLong(data.get("id")));
      case "nt.properties" -> {
        Entry e = byName.get((String) data.get("name"));
        if (e != null && data.get("properties") instanceof Map<?, ?> p) {
          applyProperties(e, (Map<String, Object>) p);
        }
      }
      case "nt.value" -> valueReceived(Json.toLong(data.get("id")), data.get("value"));
      default -> { }
    }
  }

  private void connected() {
    if (mode != MODE_CLIENT || connected) {
      return;
    }
    connected = true;
    notifyConnection(true);
    for (Entry e : entries) {
      if (e.localSet && e.exists()) {
        publish(e);
      }
    }
  }

  private void disconnected() {
    boolean was = connected;
    connected = false;
    byRemoteId.clear();
    for (Entry e : entries) {
      e.published = false;
      e.remoteId = -1;
    }
    if (was) {
      notifyConnection(false);
    }
  }

  private void announced(long id, String name, String typeStr, Map<String, Object> properties) {
    if (name == null || typeStr == null) {
      return;
    }
    Entry e = entryByName(name);
    int type = typeFromString(typeStr);
    if (e.remoteId >= 0 && e.remoteId != id) {
      byRemoteId.remove(e.remoteId);
    }
    e.remoteId = (int) id;
    e.remoteType = typeStr;
    byRemoteId.put((int) id, e);
    if (e.exists() && e.type != type) {
      // The server's type wins over a local value of another type.
      Object old = e.value;
      e.value = null;
      e.localSet = false;
      notifyEntry(e, DELETE, old);
    }
    e.type = type;
    applyProperties(e, properties);
  }

  private void unannounced(long id) {
    Entry e = byRemoteId.remove((int) id);
    if (e == null) {
      return;
    }
    e.remoteId = -1;
    e.remoteType = null;
    if (!e.published) {
      deleteLocked(e, 0);
    }
  }

  private void applyProperties(Entry e, Map<String, Object> properties) {
    if (!properties.containsKey("persistent")) {
      return;
    }
    int flags = Boolean.TRUE.equals(properties.get("persistent")) ? PERSISTENT : 0;
    if (flags != e.flags) {
      e.flags = flags;
      if (e.exists()) {
        notifyEntry(e, FLAGS, e.value);
      }
    }
  }

  private void valueReceived(long id, Object json) {
    Entry e = byRemoteId.get((int) id);
    if (e == null) {
      return;
    }
    int type = typeFromString(e.remoteType);
    Object value = fromJson(type, json);
    if (value == null) {
      return;
    }
    boolean isNew = !e.exists();
    if (!isNew && e.type == type && equalValues(type, e.value, value)) {
      return;
    }
    e.type = type;
    e.value = value;
    e.time = now();
    notifyEntry(e, isNew ? NEW : UPDATE, value);
  }

  private void publish(Entry e) {
    if (!connected) {
      return;
    }
    if (!e.published) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("id", e.number());
      data.put("name", e.name);
      data.put("type", e.remoteType != null ? e.remoteType : typeString(e.type));
      data.put("properties", persistentProperty(e.flags));
      send("nt.announce", data);
      e.published = true;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", e.number());
    data.put("ts", 0);
    data.put("value", toJson(e.type, e.value));
    send("nt.value", data);
  }

  private static Map<String, Object> persistentProperty(int flags) {
    return (flags & PERSISTENT) != 0 ? Map.of("persistent", true) : Map.of();
  }

  // types and values

  static String typeString(int type) {
    return switch (type) {
      case BOOLEAN -> "boolean";
      case DOUBLE -> "double";
      case STRING -> "string";
      case BOOLEAN_ARRAY -> "boolean[]";
      case DOUBLE_ARRAY -> "double[]";
      case STRING_ARRAY -> "string[]";
      default -> "raw";
    };
  }

  /** NT4 types that NT3 has no name for read as the nearest NT3 type. */
  static int typeFromString(String typeStr) {
    return switch (typeStr) {
      case "boolean" -> BOOLEAN;
      case "double", "int", "float" -> DOUBLE;
      case "string", "json" -> STRING;
      case "boolean[]" -> BOOLEAN_ARRAY;
      case "double[]", "int[]", "float[]" -> DOUBLE_ARRAY;
      case "string[]" -> STRING_ARRAY;
      default -> RAW;
    };
  }

  static Object toJson(int type, Object value) {
    return type == RAW ? Base64.getEncoder().encodeToString((byte[]) value) : value;
  }

  /** The Java value of a JSON value for an NT3 type, or null when it does not fit. */
  static Object fromJson(int type, Object j) {
    try {
      switch (type) {
        case BOOLEAN:
          return j instanceof Boolean ? j : null;
        case DOUBLE:
          return j instanceof Number n ? (Object) n.doubleValue() : null;
        case STRING:
          return j instanceof String ? j : null;
        case RAW:
          return j instanceof String s ? Base64.getDecoder().decode(s) : null;
        case BOOLEAN_ARRAY: {
          List<?> l = (List<?>) j;
          boolean[] a = new boolean[l.size()];
          for (int i = 0; i < a.length; i++) {
            a[i] = (Boolean) l.get(i);
          }
          return a;
        }
        case DOUBLE_ARRAY: {
          List<?> l = (List<?>) j;
          double[] a = new double[l.size()];
          for (int i = 0; i < a.length; i++) {
            a[i] = ((Number) l.get(i)).doubleValue();
          }
          return a;
        }
        case STRING_ARRAY: {
          List<?> l = (List<?>) j;
          String[] a = new String[l.size()];
          for (int i = 0; i < a.length; i++) {
            a[i] = (String) l.get(i);
          }
          return a;
        }
        default:
          return null;
      }
    } catch (ClassCastException | IllegalArgumentException e) {
      return null;
    }
  }

  static boolean equalValues(int type, Object a, Object b) {
    return switch (type) {
      case RAW -> Arrays.equals((byte[]) a, (byte[]) b);
      case BOOLEAN_ARRAY -> Arrays.equals((boolean[]) a, (boolean[]) b);
      case DOUBLE_ARRAY -> sameDoubles((double[]) a, (double[]) b);
      case STRING_ARRAY -> Arrays.equals((String[]) a, (String[]) b);
      default -> a.equals(b);
    };
  }

  // CheerpJ's Arrays.equals(double[], double[]) is wrong when the first elements match
  private static boolean sameDoubles(double[] a, double[] b) {
    if (a.length != b.length) {
      return false;
    }
    for (int i = 0; i < a.length; i++) {
      if (a[i] != b[i]) {
        return false;
      }
    }
    return true;
  }
}
