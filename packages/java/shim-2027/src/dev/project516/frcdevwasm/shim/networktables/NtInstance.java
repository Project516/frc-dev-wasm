package dev.project516.frcdevwasm.shim.networktables;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * One NetworkTables instance: local storage, listener storage and the bridge
 * mirror. Follows ntcore's LocalStorage, ListenerStorage and InstanceImpl. All
 * state is guarded by the instance monitor.
 */
final class NtInstance {
  // handle types, as nt::Handle
  static final int H_LISTENER = 16;
  static final int H_POLLER = 17;
  static final int H_ENTRY = 18;
  static final int H_INSTANCE = 19;
  static final int H_DATALOGGER = 20;
  static final int H_CONN_DATALOGGER = 21;
  static final int H_MULTISUB = 22;
  static final int H_TOPIC = 23;
  static final int H_SUB = 24;
  static final int H_PUB = 25;
  static final int H_MAX = 26;

  // event flags
  static final int EV_IMMEDIATE = 0x01;
  static final int EV_CONNECTED = 0x02;
  static final int EV_DISCONNECTED = 0x04;
  static final int EV_CONNECTION = EV_CONNECTED | EV_DISCONNECTED;
  static final int EV_PUBLISH = 0x08;
  static final int EV_UNPUBLISH = 0x10;
  static final int EV_PROPERTIES = 0x20;
  static final int EV_TOPIC = EV_PUBLISH | EV_UNPUBLISH | EV_PROPERTIES;
  static final int EV_VALUE_REMOTE = 0x40;
  static final int EV_VALUE_LOCAL = 0x80;
  static final int EV_VALUE_ALL = EV_VALUE_REMOTE | EV_VALUE_LOCAL;
  static final int EV_LOGMESSAGE = 0x100;
  static final int EV_TIMESYNC = 0x200;
  private static final int EV_LOG_LEVEL_BITS = 0x1ff0000;

  // topic flags
  static final int FLAG_PERSISTENT = 0x01;
  static final int FLAG_RETAINED = 0x02;
  static final int FLAG_UNCACHED = 0x04;

  // network modes
  static final int MODE_NONE = 0;
  static final int MODE_SERVER = 1;
  static final int MODE_LOCAL = 0x10;

  static final int LOG_ERROR = 40;
  static final int LOG_WARNING = 30;
  static final int LOG_INFO = 20;

  private static final int NUM_INSTANCES = 16;
  private static final int REUSE_THRESHOLD = 16;
  private static final int MAX_ENDPOINTS = 512;

  private static final AtomicReferenceArray<NtInstance> INSTANCES = new AtomicReferenceArray<>(NUM_INSTANCES);
  private static int defaultIndex = -1;

  static int handle(int inst, int index, int type) {
    if (inst < 0 || index < 0) {
      return 0;
    }
    return ((type & 0x7f) << 24) | ((inst & 0xf) << 20) | (index & 0xfffff);
  }

  static int handleType(int h) {
    return (h >> 24) & 0x7f;
  }

  static int handleInst(int h) {
    return (h >> 20) & 0xf;
  }

  static int handleIndex(int h) {
    return h & 0xfffff;
  }

  // instance repository

  static NtInstance get(int inst) {
    return inst < 0 || inst >= NUM_INSTANCES ? null : INSTANCES.get(inst);
  }

  static NtInstance ofHandle(int handle) {
    return get(handleInst(handle));
  }

  static NtInstance typed(int handle, int type) {
    return handleType(handle) == type ? get(handleInst(handle)) : null;
  }

  static synchronized NtInstance getDefault() {
    if (defaultIndex < 0) {
      defaultIndex = alloc();
    }
    return get(defaultIndex);
  }

  static synchronized int defaultHandle() {
    getDefault();
    return handle(defaultIndex, 0, H_INSTANCE);
  }

  static synchronized int alloc() {
    for (int i = 0; i < NUM_INSTANCES; i++) {
      if (INSTANCES.get(i) == null) {
        INSTANCES.set(i, new NtInstance(i));
        return i;
      }
    }
    return -1;
  }

  static synchronized void destroy(int inst) {
    if (inst < 0 || inst >= NUM_INSTANCES) {
      return;
    }
    NtInstance ii = INSTANCES.getAndSet(inst, null);
    if (ii != null) {
      ii.shutdown();
    }
  }

  /** Handle table that reuses freed indexes like wpi::UidVector. */
  static final class Slots<T> {
    private final int inst;
    private final int type;
    private Object[] arr = new Object[8];
    private int size;
    private final ArrayDeque<Integer> free = new ArrayDeque<>();

    Slots(int inst, int type) {
      this.inst = inst;
      this.type = type;
    }

    int alloc() {
      int uid;
      if (free.size() < REUSE_THRESHOLD) {
        uid = size++;
        if (uid == arr.length) {
          arr = Arrays.copyOf(arr, size * 2);
        }
      } else {
        uid = free.poll();
      }
      return uid;
    }

    int handleOf(int index) {
      return handle(inst, index, type);
    }

    void put(int index, T value) {
      arr[index] = value;
    }

    int size() {
      return size;
    }

    @SuppressWarnings("unchecked")
    T at(int index) {
      return (T) arr[index];
    }

    T get(int h) {
      if (handleType(h) != type) {
        return null;
      }
      int i = handleIndex(h);
      return i < size ? at(i) : null;
    }

    T remove(int h) {
      if (handleType(h) != type) {
        return null;
      }
      int i = handleIndex(h);
      if (i >= size) {
        return null;
      }
      T t = at(i);
      if (t != null) {
        free.add(i);
        arr[i] = null;
      }
      return t;
    }

    int count() {
      int n = 0;
      for (int i = 0; i < size; i++) {
        if (arr[i] != null) {
          n++;
        }
      }
      return n;
    }

    void clear() {
      arr = new Object[8];
      size = 0;
      free.clear();
    }
  }

  /** Small int list with set semantics for listener handles. */
  static final class IntVec {
    int[] a = new int[2];
    int n;

    boolean isEmpty() {
      return n == 0;
    }

    void add(int v) {
      for (int i = 0; i < n; i++) {
        if (a[i] == v) {
          return;
        }
      }
      if (n == a.length) {
        a = Arrays.copyOf(a, n * 2);
      }
      a[n++] = v;
    }

    void remove(int v) {
      for (int i = 0; i < n; i++) {
        if (a[i] == v) {
          System.arraycopy(a, i + 1, a, i, n - i - 1);
          n--;
          return;
        }
      }
    }
  }

  /** Publisher or subscriber options plus the requested type. */
  static final class Config {
    int type;
    String typeStr = "";
    int pollStorage;
    double periodic;
    int excludePublisher;
    boolean sendAll;
    boolean topicsOnly;
    boolean keepDuplicates;
    boolean prefixMatch;
    boolean disableRemote;
    boolean disableLocal;
    boolean excludeSelf;
    boolean hidden;

    Config copy() {
      Config c = new Config();
      c.type = type;
      c.typeStr = typeStr;
      c.pollStorage = pollStorage;
      c.periodic = periodic;
      c.excludePublisher = excludePublisher;
      c.sendAll = sendAll;
      c.topicsOnly = topicsOnly;
      c.keepDuplicates = keepDuplicates;
      c.prefixMatch = prefixMatch;
      c.disableRemote = disableRemote;
      c.disableLocal = disableLocal;
      c.excludeSelf = excludeSelf;
      c.hidden = hidden;
      return c;
    }

    /** Applies the PubSubOptionsImpl defaults. */
    Config normalized() {
      Config c = copy();
      if (c.periodic == 0) {
        c.periodic = 0.1;
      }
      if (c.pollStorage == 0) {
        c.pollStorage = c.sendAll ? 20 : 1;
      }
      return c;
    }
  }

  /** Bounded queue of values that drops the oldest on overflow. */
  static final class Ring {
    private final NtValue[] buf;
    private int head;
    private int count;

    Ring(int capacity) {
      buf = new NtValue[Math.max(capacity, 1)];
    }

    void add(NtValue v) {
      if (count == buf.length) {
        buf[head] = v;
        head = (head + 1) % buf.length;
      } else {
        buf[(head + count) % buf.length] = v;
        count++;
      }
    }

    NtValue[] drain() {
      NtValue[] out = new NtValue[count];
      for (int i = 0; i < count; i++) {
        int idx = (head + i) % buf.length;
        out[i] = buf[idx];
        buf[idx] = null;
      }
      head = 0;
      count = 0;
      return out;
    }
  }

  static final class Topic {
    final int handle;
    final String name;
    final boolean special;
    NtValue lastValue = NtValue.NONE;
    NtValue lastValueNetwork = NtValue.NONE;
    int type;
    String typeStr = "";
    Map<String, Object> properties = new TreeMap<>();
    String propertiesStr = "{}";
    int flags;
    boolean onNetwork;
    boolean lastValueFromNetwork;
    Entry entry;
    final ArrayList<Publisher> publishers = new ArrayList<>(1);
    final ArrayList<Subscriber> subscribers = new ArrayList<>(1);
    final ArrayList<MultiSubscriber> multiSubscribers = new ArrayList<>(0);
    final ArrayList<Entry> entries = new ArrayList<>(1);
    final IntVec listeners = new IntVec();
    boolean bridgeDirty;
    NtValue bridgeValue = NtValue.NONE;

    Topic(int handle, String name) {
      this.handle = handle;
      this.name = name;
      this.special = !name.isEmpty() && name.charAt(0) == '$';
    }

    boolean exists() {
      return onNetwork || !publishers.isEmpty();
    }

    boolean isCached() {
      return (flags & FLAG_UNCACHED) == 0;
    }

    void clearValues() {
      lastValue = NtValue.NONE;
      lastValueNetwork = NtValue.NONE;
      lastValueFromNetwork = false;
    }

    void refreshProperties(boolean updateFlags) {
      if (updateFlags) {
        refreshFlags();
      }
      propertiesStr = NtJson.dump(properties);
    }

    private void refreshFlags() {
      flagFromProperty("persistent", FLAG_PERSISTENT, false);
      flagFromProperty("retained", FLAG_RETAINED, false);
      flagFromProperty("cached", FLAG_UNCACHED, true);
      if ((flags & FLAG_UNCACHED) != 0) {
        clearValues();
      }
    }

    private void flagFromProperty(String key, int bit, boolean inverted) {
      if (properties.get(key) instanceof Boolean b) {
        if (b != inverted) {
          flags |= bit;
        } else {
          flags &= ~bit;
        }
      }
    }

    void setFlags(int newFlags) {
      setBool("persistent", (newFlags & FLAG_PERSISTENT) != 0, true);
      setBool("retained", (newFlags & FLAG_RETAINED) != 0, true);
      if ((newFlags & FLAG_UNCACHED) != 0) {
        properties.put("cached", Boolean.FALSE);
        clearValues();
      } else {
        properties.remove("cached");
      }
      flags = newFlags;
    }

    void setBool(String key, boolean value, boolean removeWhenFalse) {
      if (value) {
        properties.put(key, Boolean.TRUE);
      } else if (removeWhenFalse) {
        properties.remove(key);
      }
    }

    void setPersistent(boolean value) {
      flags = value ? flags | FLAG_PERSISTENT : flags & ~FLAG_PERSISTENT;
      setBool("persistent", value, true);
    }

    void setRetained(boolean value) {
      flags = value ? flags | FLAG_RETAINED : flags & ~FLAG_RETAINED;
      setBool("retained", value, true);
    }

    void setCached(boolean value) {
      if (value) {
        flags &= ~FLAG_UNCACHED;
        properties.remove("cached");
      } else {
        flags |= FLAG_UNCACHED;
        properties.put("cached", Boolean.FALSE);
      }
    }

    void setProperty(String key, Object value) {
      if (value == NtJson.NULL) {
        properties.remove(key);
      } else {
        properties.put(key, value);
      }
    }

    void resetIfDoesNotExist() {
      if (exists()) {
        return;
      }
      clearValues();
      type = NtValue.UNASSIGNED;
      typeStr = "";
      flags = 0;
      properties = new TreeMap<>();
      propertiesStr = "{}";
    }
  }

  static final class Publisher {
    final int handle;
    final Topic topic;
    final Config config;
    boolean active;

    Publisher(int handle, Topic topic, Config config) {
      this.handle = handle;
      this.topic = topic;
      this.config = config;
    }

    void updateActive() {
      active = config.type == topic.type && config.typeStr.equals(topic.typeStr);
    }
  }

  static final class Subscriber {
    final int handle;
    final Topic topic;
    final Config config;
    boolean active;
    final Ring pollStorage;
    final IntVec valueListeners = new IntVec();

    Subscriber(int handle, Topic topic, Config config) {
      this.handle = handle;
      this.topic = topic;
      this.config = config;
      this.pollStorage = new Ring(config.pollStorage);
    }

    void updateActive() {
      active = config.type == NtValue.UNASSIGNED
          || (config.type == topic.type && config.typeStr.equals(topic.typeStr))
          || NtValue.isNumericCompatible(config.type, topic.type);
    }
  }

  static final class Entry {
    final int handle;
    final Topic topic;
    final Subscriber subscriber;
    Publisher publisher;

    Entry(int handle, Subscriber subscriber) {
      this.handle = handle;
      this.topic = subscriber.topic;
      this.subscriber = subscriber;
    }
  }

  static final class MultiSubscriber {
    final int handle;
    final String[] prefixes;
    final Config options;
    final IntVec valueListeners = new IntVec();

    MultiSubscriber(int handle, String[] prefixes, Config options) {
      this.handle = handle;
      this.prefixes = prefixes;
      this.options = options;
    }

    boolean matches(String name, boolean special) {
      for (String p : prefixes) {
        if (prefixMatch(name, p, special)) {
          return true;
        }
      }
      return false;
    }
  }

  static boolean prefixMatch(String name, String prefix, boolean special) {
    return (!special || !prefix.isEmpty()) && name.startsWith(prefix);
  }

  /** A topic or value listener's link into local storage. */
  static final class LocalListener {
    final int handle;
    final int eventMask;
    Subscriber subscriber;
    MultiSubscriber multiSubscriber;
    final boolean subscriberOwned;

    LocalListener(int handle, int eventMask, Subscriber s, MultiSubscriber m, boolean owned) {
      this.handle = handle;
      this.eventMask = eventMask;
      this.subscriber = s;
      this.multiSubscriber = m;
      this.subscriberOwned = owned;
    }
  }

  // listener storage

  static final int EVENT_CONNECTION = 0;
  static final int EVENT_TOPIC = 1;
  static final int EVENT_VALUE = 2;
  static final int EVENT_LOG = 3;
  static final int EVENT_TIMESYNC = 4;

  /** Queued event; converted to the Java event class when read. */
  static final class Event {
    int listener;
    int flags;
    final int kind;
    int topicHandle;
    String name;
    int type;
    String typeStr;
    int subentry;
    NtValue value;
    int level;
    String filename;
    int line;
    String message;
    long offset;
    long rtt2;
    boolean valid;

    Event(int listener, int flags, int kind) {
      this.listener = listener;
      this.flags = flags;
      this.kind = kind;
    }
  }

  static final class Poller {
    final int handle;
    ArrayList<Event> queue = new ArrayList<>();

    Poller(int handle) {
      this.handle = handle;
    }
  }

  /** What a listener's activation adds to its events. */
  static final class Source {
    final int mask;
    final int subentry; // value events: subentry handle to report
    final boolean log; // log events: flags reported as kLogMessage

    Source(int mask, int subentry, boolean log) {
      this.mask = mask;
      this.subentry = subentry;
      this.log = log;
    }
  }

  static final class ListenerData {
    final int handle;
    final Poller poller;
    int eventMask;
    final ArrayList<Source> sources = new ArrayList<>(2);

    ListenerData(int handle, Poller poller) {
      this.handle = handle;
      this.poller = poller;
    }
  }

  private static final class LogLevels {
    final int listener;
    final int min;
    final int max;

    LogLevels(int listener, int min, int max) {
      this.listener = listener;
      this.min = min;
      this.max = max;
    }
  }

  // state

  final int index;
  private final Slots<Topic> topics;
  private final Slots<Publisher> publishers;
  private final Slots<Subscriber> subscribers;
  private final Slots<Entry> entries;
  private final Slots<MultiSubscriber> multiSubscribers;
  private final Slots<Object> dataloggers;
  private final java.util.HashMap<String, Topic> nameTopics = new java.util.HashMap<>();
  private final java.util.HashMap<Integer, LocalListener> localListeners = new java.util.HashMap<>();
  private final ArrayList<LocalListener> topicPrefixListeners = new ArrayList<>();

  private final Slots<Poller> pollers;
  private final Slots<ListenerData> listeners;
  private final ArrayList<ListenerData> connListeners = new ArrayList<>();
  private final ArrayList<ListenerData> logListeners = new ArrayList<>();
  private final ArrayList<ListenerData> timeSyncListeners = new ArrayList<>();
  private final ArrayList<LogLevels> logLevels = new ArrayList<>();
  private int minLogLevel = LOG_INFO;

  private int networkMode = MODE_NONE;
  private boolean serverTimeValid;

  // bridge mirror
  private boolean bridgeActive;
  private boolean bridgeSetting;
  private final ArrayList<String> bridgeMessages = new ArrayList<>();
  private ArrayList<Topic> bridgeDirty = new ArrayList<>();

  private NtInstance(int index) {
    this.index = index;
    topics = new Slots<>(index, H_TOPIC);
    publishers = new Slots<>(index, H_PUB);
    subscribers = new Slots<>(index, H_SUB);
    entries = new Slots<>(index, H_ENTRY);
    multiSubscribers = new Slots<>(index, H_MULTISUB);
    dataloggers = new Slots<>(index, H_DATALOGGER);
    pollers = new Slots<>(index, H_POLLER);
    listeners = new Slots<>(index, H_LISTENER);
  }

  private synchronized void shutdown() {
    for (int i = 0; i < pollers.size(); i++) {
      Poller p = pollers.at(i);
      if (p != null) {
        NtSignals.destroy(p.handle);
      }
    }
    bridgeActive = false;
  }

  int instanceHandle() {
    return handle(index, 0, H_INSTANCE);
  }

  // topics

  private Topic getOrCreateTopic(String name) {
    Topic t = nameTopics.get(name);
    if (t == null) {
      int idx = topics.alloc();
      t = new Topic(topics.handleOf(idx), name);
      topics.put(idx, t);
      nameTopics.put(name, t);
      for (int i = 0; i < multiSubscribers.size(); i++) {
        MultiSubscriber m = multiSubscribers.at(i);
        if (m != null && m.matches(name, t.special)) {
          t.multiSubscribers.add(m);
        }
      }
    }
    return t;
  }

  synchronized int getTopicHandle(String name) {
    if (name.isEmpty()) {
      return 0;
    }
    return getOrCreateTopic(name).handle;
  }

  /** Resolves topic, entry, subscriber and publisher handles to their topic. */
  Topic topicOf(int h) {
    switch (handleType(h)) {
      case H_ENTRY: {
        Entry e = entries.get(h);
        return e == null ? null : e.topic;
      }
      case H_SUB: {
        Subscriber s = subscribers.get(h);
        return s == null ? null : s.topic;
      }
      case H_PUB: {
        Publisher p = publishers.get(h);
        return p == null ? null : p.topic;
      }
      case H_TOPIC:
        return topics.get(h);
      default:
        return null;
    }
  }

  private Subscriber subEntry(int h) {
    int type = handleType(h);
    if (type == H_SUB) {
      return subscribers.get(h);
    } else if (type == H_ENTRY) {
      Entry e = entries.get(h);
      return e == null ? null : e.subscriber;
    }
    return null;
  }

  synchronized int[] getTopics(String prefix, int types) {
    return topicList(prefix, types, null, null);
  }

  synchronized int[] getTopicsStr(String prefix, String[] types) {
    return topicList(prefix, 0, types, null);
  }

  synchronized List<Topic> getTopicInfos(String prefix, int types, String[] typeStrs) {
    List<Topic> out = new ArrayList<>();
    topicList(prefix, types, typeStrs, out);
    return out;
  }

  private int[] topicList(String prefix, int types, String[] typeStrs, List<Topic> outTopics) {
    int[] out = new int[topics.size()];
    int n = 0;
    for (int i = 0; i < topics.size(); i++) {
      Topic t = topics.at(i);
      if (t == null || !t.exists() || !t.name.startsWith(prefix)) {
        continue;
      }
      if (typeStrs != null) {
        if (typeStrs.length != 0 && !Arrays.asList(typeStrs).contains(t.typeStr)) {
          continue;
        }
      } else if (types != 0 && (types & t.type) == 0) {
        continue;
      }
      if (outTopics != null) {
        outTopics.add(t);
      } else {
        out[n++] = t.handle;
      }
    }
    return Arrays.copyOf(out, n);
  }

  synchronized Topic topic(int h) {
    return topics.get(h);
  }

  synchronized String getTopicName(int h) {
    Topic t = topics.get(h);
    return t == null ? "" : t.name;
  }

  synchronized int getTopicType(int h) {
    Topic t = topics.get(h);
    return t == null ? 0 : t.type;
  }

  synchronized String getTopicTypeString(int h) {
    Topic t = topics.get(h);
    return t == null ? "" : t.typeStr;
  }

  synchronized boolean getTopicExists(int h) {
    Topic t = topicOf(h);
    return t != null && t.exists();
  }

  synchronized boolean getTopicFlag(int h, int flag) {
    Topic t = topics.get(h);
    return t != null && (t.flags & flag) != 0;
  }

  synchronized void setTopicPersistent(int h, boolean value) {
    Topic t = topics.get(h);
    if (t != null) {
      t.setPersistent(value);
      propertiesUpdated(t, EV_PROPERTIES, false);
    }
  }

  synchronized void setTopicRetained(int h, boolean value) {
    Topic t = topics.get(h);
    if (t != null) {
      t.setRetained(value);
      propertiesUpdated(t, EV_PROPERTIES, false);
    }
  }

  synchronized void setTopicCached(int h, boolean value) {
    Topic t = topics.get(h);
    if (t != null) {
      t.setCached(value);
      propertiesUpdated(t, EV_PROPERTIES, false);
    }
  }

  synchronized String getTopicProperty(int h, String name) {
    Topic t = topics.get(h);
    Object v = t == null ? null : t.properties.get(name);
    return NtJson.dump(v == null ? NtJson.NULL : v);
  }

  synchronized void setTopicProperty(int h, String name, Object value) {
    Topic t = topics.get(h);
    if (t != null) {
      t.setProperty(name, value);
      propertiesUpdated(t, 0, true);
    }
  }

  synchronized void deleteTopicProperty(int h, String name) {
    Topic t = topics.get(h);
    if (t != null) {
      t.properties.remove(name);
      propertiesUpdated(t, 0, true);
    }
  }

  synchronized String getTopicProperties(int h) {
    Topic t = topics.get(h);
    return t == null ? "{}" : NtJson.dump(t.properties);
  }

  synchronized void setTopicProperties(int h, Map<String, Object> update) {
    Topic t = topics.get(h);
    if (t != null) {
      applyProperties(t, update);
      propertiesUpdated(t, 0, true);
    }
  }

  private static void applyProperties(Topic t, Map<String, Object> update) {
    for (Map.Entry<String, Object> e : update.entrySet()) {
      t.setProperty(e.getKey(), e.getValue());
    }
  }

  synchronized void setEntryFlags(int entryHandle, int flags) {
    Entry e = entries.get(entryHandle);
    if (e == null) {
      return;
    }
    Topic t = e.subscriber.topic;
    t.setFlags(flags);
    if ((flags & FLAG_UNCACHED) != 0 && (flags & FLAG_PERSISTENT) != 0) {
      log(LOG_WARNING, "topic " + t.name + ": disabling cached property disables persistent storage");
    }
    propertiesUpdated(t, EV_PROPERTIES, false);
  }

  synchronized int getEntryFlags(int entryHandle) {
    Entry e = entries.get(entryHandle);
    return e == null ? 0 : e.subscriber.topic.flags;
  }

  // entries, publishers, subscribers

  /** Name-based entry, one per topic, as the NT3 API. */
  synchronized int getEntryByName(String name) {
    if (name.isEmpty()) {
      return 0;
    }
    Topic t = getOrCreateTopic(name);
    if (t.entry == null) {
      if (t.subscribers.size() >= MAX_ENDPOINTS) {
        log(LOG_ERROR, "reached maximum number of subscribers to '" + t.name + "', not creating entry");
        return 0;
      }
      t.entry = addEntry(addSubscriber(t, new Config().normalized()));
    }
    return t.entry.handle;
  }

  synchronized int getEntry(int topicHandle, Config cfg) {
    Topic t = topics.get(topicHandle);
    if (t == null) {
      return 0;
    }
    if (t.subscribers.size() >= MAX_ENDPOINTS) {
      log(LOG_ERROR, "reached maximum number of subscribers to '" + t.name + "', not creating entry");
      return 0;
    }
    return addEntry(addSubscriber(t, cfg)).handle;
  }

  private Entry addEntry(Subscriber s) {
    int idx = entries.alloc();
    Entry e = new Entry(entries.handleOf(idx), s);
    entries.put(idx, e);
    s.topic.entries.add(e);
    return e;
  }

  private Entry removeEntry(int h) {
    Entry e = entries.remove(h);
    if (e != null) {
      e.topic.entries.remove(e);
    }
    return e;
  }

  synchronized String getEntryName(int h) {
    Subscriber s = subEntry(h);
    return s == null ? "" : s.topic.name;
  }

  synchronized int getEntryType(int h) {
    Subscriber s = subEntry(h);
    return s == null ? 0 : s.topic.type;
  }

  synchronized long getEntryLastChange(int h) {
    Subscriber s = subEntry(h);
    return s == null ? 0 : s.topic.lastValue.time;
  }

  synchronized int subscribe(int topicHandle, Config cfg) {
    Topic t = topics.get(topicHandle);
    if (t == null) {
      return 0;
    }
    if (t.subscribers.size() >= MAX_ENDPOINTS) {
      log(LOG_ERROR, "reached maximum number of subscribers to '" + t.name + "', not subscribing");
      return 0;
    }
    return addSubscriber(t, cfg).handle;
  }

  synchronized int publish(int topicHandle, Config cfg, Map<String, Object> properties) {
    Topic t = topics.get(topicHandle);
    if (t == null) {
      log(LOG_ERROR, "trying to publish invalid topic handle (" + topicHandle + ")");
      return 0;
    }
    if (cfg.type == NtValue.UNASSIGNED || cfg.typeStr.isEmpty()) {
      log(LOG_ERROR, "cannot publish '" + t.name + "' with an unassigned type or empty type string");
      return 0;
    }
    if (t.publishers.size() >= MAX_ENDPOINTS) {
      log(LOG_ERROR, "reached maximum number of publishers to '" + t.name + "', not publishing");
      return 0;
    }
    return addPublisher(t, properties, cfg).handle;
  }

  /** Releases an entry, publisher, subscriber or multi-subscriber. */
  synchronized void release(int h) {
    switch (handleType(h)) {
      case H_ENTRY -> removeSubEntry(h);
      case H_PUB -> unpublish(h);
      case H_SUB -> removeSubEntry(h);
      case H_MULTISUB -> removeMultiSubscriber(h);
      default -> { }
    }
  }

  synchronized void removeSubEntry(int h) {
    int type = handleType(h);
    if (type == H_SUB) {
      removeSubscriber(h);
    } else if (type == H_MULTISUB) {
      removeMultiSubscriber(h);
    } else if (type == H_ENTRY) {
      Entry e = removeEntry(h);
      if (e != null) {
        removeSubscriber(e.subscriber.handle);
        if (e.publisher != null) {
          removePublisher(e.publisher.handle);
        }
      }
    }
  }

  synchronized void unpublish(int h) {
    if (handleType(h) == H_PUB) {
      removePublisher(h);
      return;
    }
    Entry e = entries.get(h);
    if (e != null && e.publisher != null) {
      removePublisher(e.publisher.handle);
      e.publisher = null;
    }
  }

  synchronized int getTopicFromHandle(int h) {
    Topic t = topicOf(h);
    return t == null ? 0 : t.handle;
  }

  private Publisher addPublisher(Topic t, Map<String, Object> properties, Config cfg) {
    boolean didExist = t.exists();
    int idx = publishers.alloc();
    Publisher p = new Publisher(publishers.handleOf(idx), t, cfg);
    publishers.put(idx, p);
    t.publishers.add(p);
    if (!didExist) {
      t.type = cfg.type;
      t.typeStr = cfg.typeStr;
      refreshPubSubActive(t, true);
      t.properties = properties == null ? new TreeMap<>() : new TreeMap<>(properties);
      if (t.properties.isEmpty()) {
        notifyTopic(t, EV_PUBLISH);
      } else {
        propertiesUpdated(t, EV_PUBLISH, true);
      }
    } else {
      p.updateActive();
      if (!p.active) {
        log(LOG_INFO, "local publish to '" + t.name + "' disabled due to type mismatch (wanted '"
            + cfg.typeStr + "', currently '" + t.typeStr + "')");
      }
    }
    return p;
  }

  private Publisher removePublisher(int h) {
    Publisher p = publishers.remove(h);
    if (p == null) {
      return null;
    }
    Topic t = p.topic;
    boolean didExist = t.exists();
    t.publishers.remove(p);
    if (didExist && !t.exists()) {
      t.resetIfDoesNotExist();
      notifyTopic(t, EV_UNPUBLISH);
    }
    if (p.active && !t.publishers.isEmpty()) {
      Publisher next = t.publishers.get(0);
      if (next.config.type != t.type || !next.config.typeStr.equals(t.typeStr)) {
        t.type = next.config.type;
        t.typeStr = next.config.typeStr;
        refreshPubSubActive(t, false);
      }
    }
    return p;
  }

  private Subscriber addSubscriber(Topic t, Config cfg) {
    int idx = subscribers.alloc();
    Subscriber s = new Subscriber(subscribers.handleOf(idx), t, cfg);
    subscribers.put(idx, s);
    t.subscribers.add(s);
    s.updateActive();
    if (t.exists() && !s.active) {
      log(LOG_INFO, "local subscribe to '" + t.name + "' disabled due to type mismatch (wanted '"
          + cfg.typeStr + "', published as '" + t.typeStr + "')");
    }
    if (s.active) {
      if (!t.lastValueFromNetwork && !cfg.disableLocal) {
        s.pollStorage.add(t.lastValue);
      } else if (t.lastValueFromNetwork && !cfg.disableRemote) {
        s.pollStorage.add(t.lastValueNetwork);
      }
    }
    return s;
  }

  private Subscriber removeSubscriber(int h) {
    Subscriber s = subscribers.remove(h);
    if (s != null) {
      s.topic.subscribers.remove(s);
      for (LocalListener l : localListeners.values()) {
        if (l.subscriber == s) {
          l.subscriber = null;
        }
      }
    }
    return s;
  }

  private void refreshPubSubActive(Topic t, boolean warnOnSubMismatch) {
    for (Publisher p : t.publishers) {
      p.updateActive();
    }
    for (Subscriber s : t.subscribers) {
      s.updateActive();
      if (warnOnSubMismatch && t.exists() && !s.active) {
        log(LOG_INFO, "local subscribe to '" + t.name + "' disabled due to type mismatch (wanted '"
            + s.config.typeStr + "', published as '" + t.typeStr + "')");
      }
    }
  }

  private Publisher publishEntry(Entry e, int type) {
    if (e.publisher != null) {
      return e.publisher;
    }
    Config cfg = e.subscriber.config;
    if (cfg.type == NtValue.UNASSIGNED) {
      cfg.type = type;
      cfg.typeStr = NtValue.typeToString(type);
    } else if (cfg.type != type && !NtValue.isNumericCompatible(type, cfg.type)) {
      log(LOG_ERROR, "cannot publish entry " + e.topic.name + " as type " + NtValue.typeToString(type)
          + ", previously subscribed as " + cfg.typeStr);
      return null;
    }
    e.publisher = addPublisher(e.topic, new TreeMap<>(), cfg.copy());
    if (cfg.excludeSelf) {
      cfg.excludePublisher = e.publisher.handle;
    }
    return e.publisher;
  }

  // multi-subscribers

  synchronized int subscribeMultiple(String[] prefixes, Config options) {
    if (multiSubscribers.count() >= MAX_ENDPOINTS) {
      log(LOG_ERROR, "reached maximum number of multi-subscribers, not subscribing");
      return 0;
    }
    return addMultiSubscriber(prefixes, options).handle;
  }

  private MultiSubscriber addMultiSubscriber(String[] prefixes, Config options) {
    Config cfg = options.copy();
    cfg.prefixMatch = true;
    int idx = multiSubscribers.alloc();
    MultiSubscriber m = new MultiSubscriber(multiSubscribers.handleOf(idx), prefixes.clone(), cfg);
    multiSubscribers.put(idx, m);
    for (int i = 0; i < topics.size(); i++) {
      Topic t = topics.at(i);
      if (t == null) {
        continue;
      }
      for (String p : prefixes) {
        if (prefixMatch(t.name, p, t.special)) {
          t.multiSubscribers.add(m);
          break;
        }
      }
    }
    return m;
  }

  synchronized void unsubscribeMultiple(int h) {
    removeMultiSubscriber(h);
  }

  private MultiSubscriber removeMultiSubscriber(int h) {
    MultiSubscriber m = multiSubscribers.remove(h);
    if (m != null) {
      for (int i = 0; i < topics.size(); i++) {
        Topic t = topics.at(i);
        if (t != null) {
          t.multiSubscribers.remove(m);
        }
      }
      for (LocalListener l : localListeners.values()) {
        if (l.multiSubscriber == m) {
          l.multiSubscriber = null;
        }
      }
    }
    return m;
  }

  // values

  /** Latest value of the entry's or subscriber's topic, or null for a bad handle. */
  synchronized NtValue peek(int h) {
    Subscriber s = subEntry(h);
    return s == null ? null : s.topic.lastValue;
  }

  /** Latest value converted to the subscriber's type, or NONE. */
  synchronized NtValue getEntryValue(int h) {
    Subscriber s = subEntry(h);
    if (s == null) {
      return NtValue.NONE;
    }
    NtValue last = s.topic.lastValue;
    if (s.config.type == NtValue.UNASSIGNED || !last.isValid() || s.config.type == last.type) {
      return last;
    }
    if (NtValue.isNumericCompatible(s.config.type, last.type)) {
      return last.convertNumeric(s.config.type);
    }
    return NtValue.NONE;
  }

  /** Drains the subscriber's queue; empty for a bad handle. */
  synchronized NtValue[] readQueue(int h) {
    Subscriber s = subEntry(h);
    return s == null ? new NtValue[0] : s.pollStorage.drain();
  }

  synchronized boolean setEntryValue(int h, NtValue value) {
    if (!value.isValid()) {
      return false;
    }
    Publisher p = publishers.get(h);
    if (p == null) {
      Entry e = entries.get(h);
      if (e != null) {
        p = publishEntry(e, value.type);
      }
      if (p == null) {
        return false;
      }
    }
    return publishLocalValue(p, value, false);
  }

  synchronized boolean setDefaultEntryValue(int h, NtValue value) {
    if (!value.isValid()) {
      return false;
    }
    Topic t = topicOf(h);
    if (t == null) {
      return false;
    }
    if (!t.isCached()) {
      log(LOG_WARNING, "ignoring default value on non-cached topic '" + t.name + "'");
      return false;
    }
    if (!t.lastValue.isValid()
        && (t.type == NtValue.UNASSIGNED || t.type == value.type || NtValue.isNumericCompatible(t.type, value.type))) {
      Publisher p = publishers.get(h);
      if (p == null) {
        Entry e = entries.get(h);
        if (e != null) {
          p = publishEntry(e, value.type);
        }
      }
      if (t.type == NtValue.UNASSIGNED) {
        t.type = value.type;
      }
      NtValue nv;
      if (t.type == value.type) {
        nv = value;
      } else if (NtValue.isNumericCompatible(t.type, value.type)) {
        nv = value.convertNumeric(t.type);
      } else {
        return true;
      }
      nv = nv.withTimes(0, 0);
      if (p != null) {
        publishLocalValue(p, nv, true);
      } else {
        t.lastValue = nv;
      }
      return true;
    }
    return false;
  }

  private boolean publishLocalValue(Publisher p, NtValue value, boolean force) {
    Topic t = p.topic;
    if (t.type != NtValue.UNASSIGNED && t.type != value.type) {
      if (NtValue.isNumericCompatible(t.type, value.type)) {
        return publishLocalValue(p, value.convertNumeric(t.type), false);
      }
      return false;
    }
    if (!p.active) {
      return false;
    }
    boolean suppress = !(force || p.config.keepDuplicates);
    return setValue(t, value, EV_VALUE_LOCAL, suppress, p);
  }

  private boolean setValue(Topic t, NtValue value, int eventFlags, boolean suppressIfDuplicate, Publisher publisher) {
    boolean isDuplicate = t.isCached() && t.lastValue.sameData(value);
    if (t.type != NtValue.UNASSIGNED && t.type != value.type) {
      return false;
    }
    NtValue last = t.lastValue;
    if (!last.isValid() || last.time == 0 || value.time >= last.time) {
      if (!(suppressIfDuplicate && isDuplicate)) {
        t.type = value.type;
        if (t.isCached()) {
          t.lastValue = value;
          t.lastValueFromNetwork = false;
        }
        notifyValue(t, value, eventFlags, isDuplicate, publisher);
      }
    }
    return true;
  }

  private void notifyValue(Topic t, NtValue value, int eventFlags, boolean isDuplicate, Publisher publisher) {
    boolean isNetwork = (eventFlags & EV_VALUE_REMOTE) != 0;
    ArrayList<Subscriber> subs = t.subscribers;
    for (int i = 0, n = subs.size(); i < n; i++) {
      Subscriber s = subs.get(i);
      if (s.active
          && (s.config.keepDuplicates || !isDuplicate)
          && ((isNetwork && !s.config.disableRemote) || (!isNetwork && !s.config.disableLocal))
          && (publisher == null || s.config.excludePublisher != publisher.handle)) {
        s.pollStorage.add(value);
        if (!s.valueListeners.isEmpty()) {
          notifyValueListeners(s.valueListeners, eventFlags, t.handle, value);
        }
      }
    }
    for (int i = 0, n = t.multiSubscribers.size(); i < n; i++) {
      MultiSubscriber m = t.multiSubscribers.get(i);
      if (m.options.keepDuplicates || !isDuplicate) {
        if (!m.valueListeners.isEmpty()) {
          notifyValueListeners(m.valueListeners, eventFlags, t.handle, value);
        }
      }
    }
    if (bridgeActive && !isDuplicate && !bridgeSetting) {
      t.bridgeValue = value;
      if (!t.bridgeDirty) {
        t.bridgeDirty = true;
        bridgeDirty.add(t);
      }
    }
  }

  // property and topic notification

  private void propertiesUpdated(Topic t, int eventFlags, boolean updateFlags) {
    t.refreshProperties(updateFlags);
    if (updateFlags && (t.flags & FLAG_UNCACHED) != 0 && (t.flags & FLAG_PERSISTENT) != 0) {
      log(LOG_WARNING, "topic " + t.name + ": disabling cached property disables persistent storage");
    }
    notifyTopic(t, eventFlags | EV_PROPERTIES);
  }

  private void notifyTopic(Topic t, int eventFlags) {
    if (bridgeActive) {
      bridgeTopicEvent(t, eventFlags);
    }
    if (!t.listeners.isEmpty()) {
      notifyTopicListeners(t.listeners, eventFlags, t);
    }
    if (!topicPrefixListeners.isEmpty()) {
      IntVec hs = new IntVec();
      for (LocalListener l : topicPrefixListeners) {
        if (l.multiSubscriber != null && l.multiSubscriber.matches(t.name, t.special)) {
          hs.add(l.handle);
        }
      }
      if (!hs.isEmpty()) {
        notifyTopicListeners(hs, eventFlags, t);
      }
    }
  }

  // listeners

  synchronized int createListenerPoller() {
    int idx = pollers.alloc();
    Poller p = new Poller(pollers.handleOf(idx));
    pollers.put(idx, p);
    NtSignals.create(p.handle);
    return p.handle;
  }

  /** Returns (listener, eventMask) pairs of the listeners removed with the poller. */
  synchronized void destroyListenerPoller(int pollerHandle) {
    Poller p = pollers.remove(pollerHandle);
    if (p == null) {
      return;
    }
    NtSignals.destroy(p.handle);
    List<Integer> toRemove = new ArrayList<>();
    for (int i = 0; i < listeners.size(); i++) {
      ListenerData l = listeners.at(i);
      if (l != null && l.poller == p) {
        toRemove.add(l.handle);
      }
    }
    for (int h : toRemove) {
      removeListener(h);
    }
  }

  private ListenerData newListener(Poller p) {
    int idx = listeners.alloc();
    ListenerData l = new ListenerData(listeners.handleOf(idx), p);
    listeners.put(idx, l);
    return l;
  }

  synchronized int addListener(int pollerHandle, String[] prefixes, int mask) {
    Poller p = pollers.get(pollerHandle);
    if (p == null || (mask & (EV_TOPIC | EV_VALUE_ALL)) == 0) {
      return 0;
    }
    ListenerData l = newListener(p);
    addPrefixListener(l.handle, prefixes, mask);
    return l.handle;
  }

  synchronized int addListener(int pollerHandle, int handle, int mask) {
    Poller p = pollers.get(pollerHandle);
    if (p == null) {
      return 0;
    }
    if (handleInst(handle) != handleInst(pollerHandle)) {
      log(LOG_ERROR, "AddPolledListener(): trying to listen to handle " + handle
          + " with poller " + pollerHandle + ", ignored due to different instance");
      return 0;
    }
    ListenerData l = newListener(p);
    if (handleType(handle) == H_INSTANCE) {
      if ((mask & EV_CONNECTION) != 0) {
        activate(l, mask & (EV_CONNECTION | EV_IMMEDIATE), 0, false);
      }
      if ((mask & EV_LOGMESSAGE) != 0) {
        addLogListener(l.handle, LOG_INFO, Integer.MAX_VALUE);
      }
      if ((mask & EV_TIMESYNC) != 0) {
        int m = mask & (EV_TIMESYNC | EV_IMMEDIATE);
        activate(l, m, 0, false);
        if ((m & (EV_TIMESYNC | EV_IMMEDIATE)) == (EV_TIMESYNC | EV_IMMEDIATE) && serverTimeValid) {
          notifyTimeSyncTo(l, EV_TIMESYNC | EV_IMMEDIATE, 0, 0, true);
        }
      }
    } else if ((mask & (EV_TOPIC | EV_VALUE_ALL)) != 0) {
      addLocalListener(l.handle, handle, mask & (EV_TOPIC | EV_VALUE_ALL | EV_IMMEDIATE));
    }
    return l.handle;
  }

  synchronized int addLogger(int pollerHandle, int minLevel, int maxLevel) {
    Poller p = pollers.get(pollerHandle);
    if (p == null) {
      return 0;
    }
    if (minLevel < minLogLevel) {
      minLogLevel = minLevel;
    }
    ListenerData l = newListener(p);
    addLogListener(l.handle, minLevel, maxLevel);
    return l.handle;
  }

  private void addLogListener(int listenerHandle, int minLevel, int maxLevel) {
    ListenerData l = listeners.get(listenerHandle);
    logLevels.add(new LogLevels(listenerHandle, minLevel, maxLevel));
    activate(l, levelsToEventMask(minLevel, maxLevel), 0, true);
  }

  private static int levelsToEventMask(int min, int max) {
    int[] levels = {50, 40, 30, 20, 10, 9, 8, 7, 6};
    int mask = 0;
    for (int i = 0; i < levels.length; i++) {
      if (min <= levels[i] && max >= levels[i]) {
        mask |= 1 << (16 + i);
      }
    }
    return mask == 0 ? EV_LOGMESSAGE : mask;
  }

  private static int levelToFlag(int level) {
    int[] levels = {50, 40, 30, 20, 10, 9, 8, 7, 6};
    for (int i = 0; i < levels.length; i++) {
      if (level >= levels[i]) {
        return EV_LOGMESSAGE | (1 << (16 + i));
      }
    }
    return EV_LOGMESSAGE;
  }

  synchronized void removeListener(int listenerHandle) {
    ListenerData l = listeners.remove(listenerHandle);
    if (l == null) {
      return;
    }
    connListeners.remove(l);
    logListeners.remove(l);
    timeSyncListeners.remove(l);
    int mask = l.eventMask;
    if ((mask & (EV_TOPIC | EV_VALUE_ALL)) != 0) {
      removeLocalListener(listenerHandle);
    }
    if ((mask & (EV_LOGMESSAGE | EV_LOG_LEVEL_BITS)) != 0) {
      logLevels.removeIf(x -> x.listener == listenerHandle);
      int min = LOG_INFO;
      for (LogLevels x : logLevels) {
        min = Math.min(min, x.min);
      }
      minLogLevel = min;
    }
  }

  synchronized List<Event> readListenerQueue(int pollerHandle) {
    Poller p = pollers.get(pollerHandle);
    if (p == null) {
      return List.of();
    }
    ArrayList<Event> q = p.queue;
    p.queue = new ArrayList<>();
    return q;
  }

  private void activate(ListenerData l, int mask, int subentry, boolean log) {
    l.sources.add(new Source(mask, subentry, log));
    int delta = mask & ~l.eventMask;
    l.eventMask |= mask;
    if ((delta & EV_CONNECTION) != 0) {
      connListeners.add(l);
    }
    if ((delta & EV_LOGMESSAGE) != 0 || (delta & EV_LOG_LEVEL_BITS) != 0) {
      logListeners.add(l);
    }
    if ((delta & EV_TIMESYNC) != 0) {
      timeSyncListeners.add(l);
    }
  }

  private void enqueue(ListenerData l, Event e) {
    l.poller.queue.add(e);
  }

  private void signal(ListenerData l) {
    NtSignals.set(l.poller.handle);
  }

  private void notifyTopicListeners(IntVec handles, int flags, Topic t) {
    if (flags == 0) {
      return;
    }
    for (int i = 0; i < handles.n; i++) {
      ListenerData l = listeners.get(handles.a[i]);
      if (l != null) {
        deliverTopic(l, flags, t);
      }
    }
  }

  private void deliverTopic(ListenerData l, int flags, Topic t) {
    if ((flags & l.eventMask) == 0) {
      return;
    }
    boolean any = false;
    for (Source s : l.sources) {
      if ((flags & s.mask) != 0) {
        Event e = new Event(l.handle, flags, EVENT_TOPIC);
        e.topicHandle = t.handle;
        e.name = t.name;
        e.type = t.type;
        e.typeStr = t.typeStr;
        enqueue(l, e);
        any = true;
      }
    }
    if (any) {
      signal(l);
    }
  }

  private void notifyValueListeners(IntVec handles, int flags, int topicHandle, NtValue value) {
    for (int i = 0; i < handles.n; i++) {
      ListenerData l = listeners.get(handles.a[i]);
      if (l != null) {
        deliverValue(l, flags, topicHandle, value);
      }
    }
  }

  private void deliverValue(ListenerData l, int flags, int topicHandle, NtValue value) {
    if ((flags & l.eventMask) == 0) {
      return;
    }
    boolean any = false;
    for (Source s : l.sources) {
      if ((flags & s.mask) != 0) {
        Event e = new Event(l.handle, flags, EVENT_VALUE);
        e.topicHandle = topicHandle;
        e.subentry = s.subentry;
        e.value = value;
        enqueue(l, e);
        any = true;
      }
    }
    if (any) {
      signal(l);
    }
  }

  private void notifyTimeSyncTo(ListenerData l, int flags, long offset, long rtt2, boolean valid) {
    if ((flags & l.eventMask) == 0) {
      return;
    }
    for (Source s : l.sources) {
      if ((flags & s.mask) != 0) {
        Event e = new Event(l.handle, flags, EVENT_TIMESYNC);
        e.offset = offset;
        e.rtt2 = rtt2;
        e.valid = valid;
        enqueue(l, e);
      }
    }
    signal(l);
  }

  private void notifyTimeSync(boolean valid) {
    for (ListenerData l : new ArrayList<>(timeSyncListeners)) {
      notifyTimeSyncTo(l, EV_TIMESYNC, 0, 0, valid);
    }
  }

  // local listener setup, following StorageImpl::AddListenerImpl

  private void addLocalListener(int listenerHandle, int handle, int mask) {
    ListenerData l = listeners.get(listenerHandle);
    Topic t = topics.get(handle);
    if (t != null) {
      if (t.subscribers.size() >= MAX_ENDPOINTS) {
        log(LOG_ERROR, "reached maximum number of subscribers to '" + t.name + "', ignoring listener add");
        return;
      }
      Config cfg = new Config().normalized();
      cfg.topicsOnly = (mask & EV_VALUE_ALL) == 0;
      Subscriber sub = addSubscriber(t, cfg);
      addSubscriberListener(l, sub, mask, sub.handle, true);
      return;
    }
    MultiSubscriber m = multiSubscribers.get(handle);
    if (m != null) {
      addMultiListener(l, m, mask, false);
      return;
    }
    Subscriber s = subscribers.get(handle);
    if (s != null) {
      addSubscriberListener(l, s, mask, s.handle, false);
      return;
    }
    Entry e = entries.get(handle);
    if (e != null) {
      addSubscriberListener(l, e.subscriber, mask, e.handle, false);
    }
  }

  private void addPrefixListener(int listenerHandle, String[] prefixes, int mask) {
    mask &= EV_TOPIC | EV_VALUE_ALL | EV_IMMEDIATE;
    Config cfg = new Config().normalized();
    cfg.topicsOnly = (mask & EV_VALUE_ALL) == 0;
    MultiSubscriber m = addMultiSubscriber(prefixes, cfg);
    addMultiListener(listeners.get(listenerHandle), m, mask, true);
  }

  private void addSubscriberListener(ListenerData l, Subscriber s, int mask, int subentry, boolean owned) {
    LocalListener ll = new LocalListener(l.handle, mask, s, null, owned);
    localListeners.putIfAbsent(l.handle, ll);
    Topic t = s.topic;
    if ((mask & EV_TOPIC) != 0) {
      activate(l, mask & (EV_TOPIC | EV_IMMEDIATE), 0, false);
      t.listeners.add(l.handle);
      if ((mask & (EV_PUBLISH | EV_IMMEDIATE)) == (EV_PUBLISH | EV_IMMEDIATE) && t.exists()) {
        deliverTopic(l, EV_PUBLISH | EV_IMMEDIATE, t);
      }
    }
    if ((mask & EV_VALUE_ALL) != 0) {
      activate(l, mask & (EV_VALUE_ALL | EV_IMMEDIATE), subentry, false);
      s.valueListeners.add(l.handle);
      if ((mask & EV_IMMEDIATE) != 0 && t.lastValue.isValid()) {
        deliverValue(l, EV_IMMEDIATE | EV_VALUE_ALL, t.handle, t.lastValue);
      }
    }
  }

  private void addMultiListener(ListenerData l, MultiSubscriber m, int mask, boolean owned) {
    LocalListener ll = new LocalListener(l.handle, mask, null, m, owned);
    localListeners.putIfAbsent(l.handle, ll);
    ArrayList<Topic> matching = new ArrayList<>();
    if ((mask & EV_IMMEDIATE) != 0 && (mask & (EV_PUBLISH | EV_VALUE_ALL)) != 0) {
      for (int i = 0; i < topics.size(); i++) {
        Topic t = topics.at(i);
        if (t != null && t.exists() && m.matches(t.name, t.special)) {
          matching.add(t);
        }
      }
    }
    if ((mask & EV_TOPIC) != 0) {
      activate(l, mask & (EV_TOPIC | EV_IMMEDIATE), 0, false);
      topicPrefixListeners.add(ll);
      if ((mask & (EV_PUBLISH | EV_IMMEDIATE)) == (EV_PUBLISH | EV_IMMEDIATE)) {
        for (Topic t : matching) {
          deliverTopic(l, EV_PUBLISH | EV_IMMEDIATE, t);
        }
      }
    }
    if ((mask & EV_VALUE_ALL) != 0) {
      activate(l, mask & (EV_VALUE_ALL | EV_IMMEDIATE), m.handle, false);
      m.valueListeners.add(l.handle);
      if ((mask & EV_IMMEDIATE) != 0) {
        for (Topic t : matching) {
          if (t.lastValue.isValid()) {
            deliverValue(l, EV_VALUE_ALL | EV_IMMEDIATE, t.handle, t.lastValue);
          }
        }
      }
    }
  }

  private void removeLocalListener(int listenerHandle) {
    LocalListener ll = localListeners.remove(listenerHandle);
    if (ll == null) {
      return;
    }
    topicPrefixListeners.remove(ll);
    if (ll.subscriber != null) {
      ll.subscriber.valueListeners.remove(listenerHandle);
      ll.subscriber.topic.listeners.remove(listenerHandle);
      if (ll.subscriberOwned) {
        removeSubscriber(ll.subscriber.handle);
      }
    }
    if (ll.multiSubscriber != null) {
      ll.multiSubscriber.valueListeners.remove(listenerHandle);
      if (ll.subscriberOwned) {
        removeMultiSubscriber(ll.multiSubscriber.handle);
      }
    }
  }

  // logging

  private void log(int level, String msg) {
    if (level < minLogLevel) {
      return;
    }
    if (logLevels.isEmpty()) {
      if (level == LOG_INFO) {
        System.err.println("NT: " + msg);
      } else {
        System.err.println("NT: " + (level >= LOG_ERROR ? "ERROR" : "WARNING") + ": " + msg + " (NtInstance.java:0)");
      }
      return;
    }
    int flags = levelToFlag(level);
    for (ListenerData l : new ArrayList<>(logListeners)) {
      if ((flags & l.eventMask) == 0) {
        continue;
      }
      boolean any = false;
      for (Source s : l.sources) {
        if ((flags & s.mask) != 0) {
          Event e = new Event(l.handle, s.log ? EV_LOGMESSAGE : flags, EVENT_LOG);
          e.level = level;
          e.filename = "NtInstance.java";
          e.line = 0;
          e.message = msg;
          enqueue(l, e);
          any = true;
        }
      }
      if (any) {
        signal(l);
      }
    }
  }

  // network mode: everything is local, the server is the page bridge

  synchronized int getNetworkMode() {
    return networkMode;
  }

  synchronized void startLocal() {
    if (networkMode == MODE_NONE) {
      networkMode = MODE_LOCAL;
    }
  }

  synchronized void stopLocal() {
    if ((networkMode & MODE_LOCAL) != 0) {
      networkMode = MODE_NONE;
    }
  }

  /** startServer and startClient both enter server mode. */
  synchronized void startServer() {
    if (networkMode != MODE_NONE) {
      return;
    }
    networkMode = MODE_SERVER;
    serverTimeValid = true;
    notifyTimeSync(true);
  }

  synchronized void stopNetwork() {
    if ((networkMode & MODE_SERVER) == 0) {
      return;
    }
    networkMode = MODE_NONE;
    serverTimeValid = false;
    notifyTimeSync(false);
  }

  synchronized boolean isConnected() {
    return networkMode == MODE_LOCAL;
  }

  synchronized boolean hasServerTimeOffset() {
    return serverTimeValid;
  }

  synchronized int startDataLog() {
    int idx = dataloggers.alloc();
    dataloggers.put(idx, Boolean.TRUE);
    return dataloggers.handleOf(idx);
  }

  synchronized void stopDataLog(int h) {
    dataloggers.remove(h);
  }

  // bridge mirror

  /** Starts mirroring this instance to the page. */
  synchronized void bridgeStart() {
    bridgeActive = true;
    resendAll();
  }

  synchronized void bridgeStop() {
    bridgeActive = false;
    bridgeMessages.clear();
    clearDirty();
  }

  private void clearDirty() {
    for (Topic t : bridgeDirty) {
      t.bridgeDirty = false;
    }
    bridgeDirty.clear();
  }

  private void resendAll() {
    bridgeMessages.clear();
    clearDirty();
    for (int i = 0; i < topics.size(); i++) {
      Topic t = topics.at(i);
      if (t != null && t.exists()) {
        bridgeMessages.add(announceMessage(t));
        if (t.lastValue.isValid()) {
          t.bridgeValue = t.lastValue;
          t.bridgeDirty = true;
          bridgeDirty.add(t);
        }
      }
    }
  }

  private void bridgeTopicEvent(Topic t, int flags) {
    if ((flags & EV_PUBLISH) != 0) {
      bridgeMessages.add(announceMessage(t));
    } else if ((flags & EV_UNPUBLISH) != 0) {
      bridgeMessages.add(message("nt.unannounce", "{\"id\":" + handleIndex(t.handle) + ",\"name\":" + quote(t.name) + "}"));
      if (t.bridgeDirty) {
        t.bridgeDirty = false;
        bridgeDirty.remove(t);
      }
    } else if ((flags & EV_PROPERTIES) != 0 && t.exists()) {
      bridgeMessages.add(message("nt.properties",
          "{\"name\":" + quote(t.name) + ",\"properties\":" + NtJson.dump(t.properties) + "}"));
    }
  }

  private String announceMessage(Topic t) {
    return message("nt.announce", "{\"id\":" + handleIndex(t.handle) + ",\"name\":" + quote(t.name)
        + ",\"type\":" + quote(t.typeStr) + ",\"properties\":" + NtJson.dump(t.properties) + "}");
  }

  private static String message(String type, String data) {
    return "{\"type\":\"" + type + "\",\"device\":\"\",\"data\":" + data + "}";
  }

  private static String quote(String s) {
    StringBuilder sb = new StringBuilder();
    NtJson.quote(s, sb);
    return sb.toString();
  }

  /** Takes the messages queued for the page since the last call. */
  synchronized List<String> bridgeDrain() {
    if (!bridgeActive) {
      return List.of();
    }
    ArrayList<String> out = new ArrayList<>(bridgeMessages);
    bridgeMessages.clear();
    ArrayList<Topic> dirty = bridgeDirty;
    bridgeDirty = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    for (Topic t : dirty) {
      t.bridgeDirty = false;
      NtValue v = t.bridgeValue;
      if (t.exists() && v.isValid()) {
        sb.setLength(0);
        sb.append("{\"id\":").append(handleIndex(t.handle)).append(",\"ts\":").append(NtTime.pageMicros(v.time)).append(",\"value\":");
        valueJson(v, sb);
        sb.append('}');
        out.add(message("nt.value", sb.toString()));
      }
    }
    return out;
  }

  /** Applies a value from the page as a remote publish, last write wins. */
  synchronized void bridgeSet(String name, String typeStr, Object json) {
    if (name.isEmpty()) {
      return;
    }
    NtValue v = valueFromJson(NtValue.stringToType(typeStr), json);
    if (v == null) {
      log(LOG_WARNING, "ignoring nt.set for '" + name + "': value does not match type '" + typeStr + "'");
      return;
    }
    Topic t = getOrCreateTopic(name);
    networkAnnounce(t, typeStr);
    bridgeSetting = true;
    try {
      if (setValue(t, v, EV_VALUE_REMOTE, false, null) && t.isCached()) {
        t.lastValueNetwork = v;
        t.lastValueFromNetwork = true;
      }
    } finally {
      bridgeSetting = false;
    }
  }

  private void networkAnnounce(Topic t, String typeStr) {
    boolean didExist = t.exists();
    t.onNetwork = true;
    int type = NtValue.stringToType(typeStr);
    if (t.type != type || !t.typeStr.equals(typeStr)) {
      if (didExist) {
        log(LOG_INFO, "network announce of '" + t.name + "' overriding local publish (was '"
            + t.typeStr + "', now '" + typeStr + "')");
      }
      t.type = type;
      t.typeStr = typeStr;
      refreshPubSubActive(t, true);
    }
    if (!didExist) {
      notifyTopic(t, EV_PUBLISH);
    }
  }

  private static NtValue valueFromJson(int type, Object j) {
    try {
      switch (type) {
        case NtValue.BOOLEAN:
          return j instanceof Boolean b ? NtValue.ofBoolean(b, 0) : null;
        case NtValue.INTEGER:
          return j instanceof Number n ? NtValue.ofInteger(n.longValue(), 0) : null;
        case NtValue.FLOAT:
          return j instanceof Number n ? NtValue.ofFloat(n.floatValue(), 0) : null;
        case NtValue.DOUBLE:
          return j instanceof Number n ? NtValue.ofDouble(n.doubleValue(), 0) : null;
        case NtValue.STRING:
          return NtValue.ofString(j instanceof String s ? s : NtJson.dump(j), 0);
        case NtValue.RAW: {
          byte[] b = Base64.getDecoder().decode((String) j);
          return NtValue.ofRaw(b, 0, b.length, 0);
        }
        case NtValue.BOOLEAN_ARRAY: {
          List<?> l = (List<?>) j;
          boolean[] a = new boolean[l.size()];
          for (int i = 0; i < a.length; i++) {
            a[i] = (Boolean) l.get(i);
          }
          return NtValue.ofBooleanArray(a, 0);
        }
        case NtValue.INTEGER_ARRAY: {
          List<?> l = (List<?>) j;
          long[] a = new long[l.size()];
          for (int i = 0; i < a.length; i++) {
            a[i] = ((Number) l.get(i)).longValue();
          }
          return NtValue.ofIntegerArray(a, 0);
        }
        case NtValue.FLOAT_ARRAY: {
          List<?> l = (List<?>) j;
          float[] a = new float[l.size()];
          for (int i = 0; i < a.length; i++) {
            a[i] = ((Number) l.get(i)).floatValue();
          }
          return NtValue.ofFloatArray(a, 0);
        }
        case NtValue.DOUBLE_ARRAY: {
          List<?> l = (List<?>) j;
          double[] a = new double[l.size()];
          for (int i = 0; i < a.length; i++) {
            a[i] = ((Number) l.get(i)).doubleValue();
          }
          return NtValue.ofDoubleArray(a, 0);
        }
        case NtValue.STRING_ARRAY: {
          List<?> l = (List<?>) j;
          String[] a = new String[l.size()];
          for (int i = 0; i < a.length; i++) {
            a[i] = (String) l.get(i);
          }
          return NtValue.ofStringArray(a, 0);
        }
        default:
          return null;
      }
    } catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
      return null;
    }
  }

  private static void valueJson(NtValue v, StringBuilder sb) {
    switch (v.type) {
      case NtValue.BOOLEAN -> sb.append(v.l != 0);
      case NtValue.INTEGER -> sb.append(v.l);
      case NtValue.FLOAT, NtValue.DOUBLE -> sb.append(NtJson.number(v.d));
      case NtValue.STRING -> NtJson.quote(v.getString(), sb);
      case NtValue.RAW -> sb.append('"').append(Base64.getEncoder().encodeToString(v.getRaw())).append('"');
      case NtValue.BOOLEAN_ARRAY -> {
        boolean[] a = v.getBooleanArray();
        sb.append('[');
        for (int i = 0; i < a.length; i++) {
          sb.append(i > 0 ? "," : "").append(a[i]);
        }
        sb.append(']');
      }
      case NtValue.INTEGER_ARRAY -> {
        long[] a = v.getIntegerArray();
        sb.append('[');
        for (int i = 0; i < a.length; i++) {
          sb.append(i > 0 ? "," : "").append(a[i]);
        }
        sb.append(']');
      }
      case NtValue.FLOAT_ARRAY -> {
        float[] a = v.getFloatArray();
        sb.append('[');
        for (int i = 0; i < a.length; i++) {
          sb.append(i > 0 ? "," : "").append(NtJson.number(a[i]));
        }
        sb.append(']');
      }
      case NtValue.DOUBLE_ARRAY -> {
        double[] a = v.getDoubleArray();
        sb.append('[');
        for (int i = 0; i < a.length; i++) {
          sb.append(i > 0 ? "," : "").append(NtJson.number(a[i]));
        }
        sb.append(']');
      }
      case NtValue.STRING_ARRAY -> {
        String[] a = v.getStringArray();
        sb.append('[');
        for (int i = 0; i < a.length; i++) {
          if (i > 0) {
            sb.append(',');
          }
          NtJson.quote(a[i], sb);
        }
        sb.append(']');
      }
      default -> sb.append("null");
    }
  }
}
