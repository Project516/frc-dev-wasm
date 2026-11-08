package dev.project516.frcdevwasm.shim.networktables;

import org.wpilib.networktables.ConnectionInfo;
import org.wpilib.networktables.LogMessage;
import org.wpilib.networktables.NetworkTableEvent;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.NetworkTableValue;
import org.wpilib.networktables.PubSubOptions;
import org.wpilib.networktables.TimeSyncEventData;
import org.wpilib.networktables.TimestampedBoolean;
import org.wpilib.networktables.TimestampedBooleanArray;
import org.wpilib.networktables.TimestampedDouble;
import org.wpilib.networktables.TimestampedDoubleArray;
import org.wpilib.networktables.TimestampedFloat;
import org.wpilib.networktables.TimestampedFloatArray;
import org.wpilib.networktables.TimestampedInteger;
import org.wpilib.networktables.TimestampedIntegerArray;
import org.wpilib.networktables.TimestampedRaw;
import org.wpilib.networktables.TimestampedString;
import org.wpilib.networktables.TimestampedStringArray;
import org.wpilib.networktables.TopicInfo;
import org.wpilib.networktables.ValueEventData;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** Pure-Java NetworkTablesJNI. Argument checks and exceptions follow NetworkTablesJNI.cpp. */
public final class NetworkTablesJNIImpl {
  private NetworkTablesJNIImpl() {}

  private static final Constructor<NetworkTableValue> VALUE_CTOR = valueCtor();

  private static Constructor<NetworkTableValue> valueCtor() {
    try {
      Constructor<NetworkTableValue> c =
          NetworkTableValue.class.getDeclaredConstructor(int.class, Object.class, long.class, long.class);
      c.setAccessible(true);
      return c;
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  // helpers

  private static NtInstance inst(int handle) {
    return NtInstance.ofHandle(handle);
  }

  private static NtValue peek(int h) {
    NtInstance ii = inst(h);
    return ii == null ? null : ii.peek(h);
  }

  private static NtValue entryValue(int h) {
    NtInstance ii = inst(h);
    return ii == null ? NtValue.NONE : ii.getEntryValue(h);
  }

  private static NtValue[] readQueue(int h) {
    NtInstance ii = inst(h);
    return ii == null ? new NtValue[0] : ii.readQueue(h);
  }

  private static NtInstance.Config config(int type, String typeStr, PubSubOptions o) {
    NtInstance.Config c = new NtInstance.Config();
    c.type = type;
    c.typeStr = typeStr == null ? "" : typeStr;
    if (o != null) {
      c.pollStorage = o.pollStorage;
      c.periodic = o.periodic;
      c.excludePublisher = o.excludePublisher;
      c.sendAll = o.sendAll;
      c.topicsOnly = o.topicsOnly;
      c.keepDuplicates = o.keepDuplicates;
      c.prefixMatch = o.prefixMatch;
      c.disableRemote = o.disableRemote;
      c.disableLocal = o.disableLocal;
      c.excludeSelf = o.excludeSelf;
      c.hidden = o.hidden;
    }
    c.prefixMatch = false;
    return c.normalized();
  }

  private static NtInstance.Config multiConfig(PubSubOptions o) {
    NtInstance.Config c = config(0, "", o);
    c.prefixMatch = true;
    return c;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseObject(String json, String what) {
    Object j;
    try {
      j = NtJson.parse(json);
    } catch (NtJson.ParseException e) {
      throw new IllegalArgumentException("could not parse " + what + " JSON: " + e.getMessage());
    }
    if (!(j instanceof Map)) {
      throw new IllegalArgumentException("properties is not a JSON object");
    }
    return (Map<String, Object>) j;
  }

  private static String[] checkedStrings(String[] a, String nullArray, String nullElem) {
    if (a == null) {
      throw new NullPointerException(nullArray);
    }
    for (String s : a) {
      if (s == null) {
        throw new NullPointerException(nullElem);
      }
    }
    return a;
  }

  private static Object valueObject(NtValue v) {
    return switch (v.type) {
      case NtValue.BOOLEAN -> Boolean.valueOf(v.getBoolean());
      case NtValue.INTEGER -> Long.valueOf(v.l);
      case NtValue.FLOAT -> Float.valueOf((float) v.d);
      case NtValue.DOUBLE -> Double.valueOf(v.d);
      case NtValue.STRING -> v.getString();
      case NtValue.RAW -> v.getRaw().clone();
      case NtValue.BOOLEAN_ARRAY -> v.getBooleanArray().clone();
      case NtValue.INTEGER_ARRAY -> v.getIntegerArray().clone();
      case NtValue.FLOAT_ARRAY -> v.getFloatArray().clone();
      case NtValue.DOUBLE_ARRAY -> v.getDoubleArray().clone();
      case NtValue.STRING_ARRAY -> v.getStringArray().clone();
      default -> null;
    };
  }

  private static NetworkTableValue valueOf(NtValue v) {
    try {
      if (!v.isValid()) {
        return VALUE_CTOR.newInstance(NtValue.UNASSIGNED, null, 0L, 0L);
      }
      return VALUE_CTOR.newInstance(v.type, valueObject(v), v.time, v.serverTime);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static TopicInfo topicInfo(NetworkTableInstance instObject, NtInstance.Topic t) {
    return new TopicInfo(instObject, t.handle, t.name, t.type, t.typeStr);
  }

  private static TopicInfo[] topicInfos(NetworkTableInstance instObject, int inst, String prefix, int types, String[] typeStrs) {
    if (prefix == null) {
      throw new NullPointerException("prefix cannot be null");
    }
    NtInstance ii = NtInstance.typed(inst, NtInstance.H_INSTANCE);
    if (ii == null) {
      return new TopicInfo[0];
    }
    List<NtInstance.Topic> topics = ii.getTopicInfos(prefix, types, typeStrs);
    TopicInfo[] out = new TopicInfo[topics.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = topicInfo(instObject, topics.get(i));
    }
    return out;
  }

  // instances

  public static int getDefaultInstance() {
    return NtInstance.defaultHandle();
  }

  public static int createInstance() {
    return NtInstance.handle(NtInstance.alloc(), 0, NtInstance.H_INSTANCE);
  }

  public static void destroyInstance(int inst) {
    if (NtInstance.handleType(inst) == NtInstance.H_INSTANCE) {
      NtInstance.destroy(NtInstance.handleInst(inst));
    }
  }

  public static int getInstanceFromHandle(int handle) {
    int type = NtInstance.handleType(handle);
    if (type >= NtInstance.H_LISTENER && type < NtInstance.H_MAX) {
      return NtInstance.handle(NtInstance.handleInst(handle), 0, NtInstance.H_INSTANCE);
    }
    return 0;
  }

  // entries and topics

  public static int getEntryImpl(int topic, int type, String typeStr, PubSubOptions options) {
    NtInstance ii = inst(topic);
    return ii == null ? 0 : ii.getEntry(topic, config(type, typeStr, options));
  }

  public static int getEntry(int inst, String key) {
    if (key == null) {
      throw new NullPointerException("key cannot be null");
    }
    NtInstance ii = NtInstance.typed(inst, NtInstance.H_INSTANCE);
    return ii == null ? 0 : ii.getEntryByName(key);
  }

  public static String getEntryName(int entry) {
    NtInstance ii = inst(entry);
    return ii == null ? "" : ii.getEntryName(entry);
  }

  public static long getEntryLastChange(int entry) {
    NtInstance ii = inst(entry);
    return ii == null ? 0 : ii.getEntryLastChange(entry);
  }

  public static int getType(int entry) {
    NtInstance ii = inst(entry);
    return ii == null ? 0 : ii.getEntryType(entry);
  }

  public static int[] getTopics(int inst, String prefix, int types) {
    if (prefix == null) {
      throw new NullPointerException("prefix cannot be null");
    }
    NtInstance ii = NtInstance.typed(inst, NtInstance.H_INSTANCE);
    return ii == null ? new int[0] : ii.getTopics(prefix, types);
  }

  public static int[] getTopicsStr(int inst, String prefix, String[] types) {
    if (prefix == null) {
      throw new NullPointerException("prefix cannot be null");
    }
    checkedStrings(types, "types cannot be null", "null string in types");
    NtInstance ii = NtInstance.typed(inst, NtInstance.H_INSTANCE);
    return ii == null ? new int[0] : ii.getTopicsStr(prefix, types);
  }

  public static TopicInfo[] getTopicInfos(NetworkTableInstance instObject, int inst, String prefix, int types) {
    return topicInfos(instObject, inst, prefix, types, null);
  }

  public static TopicInfo[] getTopicInfosStr(NetworkTableInstance instObject, int inst, String prefix, String[] types) {
    checkedStrings(types, "types cannot be null", "null string in types");
    return topicInfos(instObject, inst, prefix, 0, types);
  }

  public static int getTopic(int inst, String name) {
    NtInstance ii = NtInstance.typed(inst, NtInstance.H_INSTANCE);
    return ii == null ? 0 : ii.getTopicHandle(name);
  }

  public static String getTopicName(int topic) {
    NtInstance ii = inst(topic);
    return ii == null ? "" : ii.getTopicName(topic);
  }

  public static int getTopicType(int topic) {
    NtInstance ii = inst(topic);
    return ii == null ? 0 : ii.getTopicType(topic);
  }

  public static void setTopicPersistent(int topic, boolean value) {
    NtInstance ii = inst(topic);
    if (ii != null) {
      ii.setTopicPersistent(topic, value);
    }
  }

  public static boolean getTopicPersistent(int topic) {
    NtInstance ii = inst(topic);
    return ii != null && ii.getTopicFlag(topic, NtInstance.FLAG_PERSISTENT);
  }

  public static void setTopicRetained(int topic, boolean value) {
    NtInstance ii = inst(topic);
    if (ii != null) {
      ii.setTopicRetained(topic, value);
    }
  }

  public static boolean getTopicRetained(int topic) {
    NtInstance ii = inst(topic);
    return ii != null && ii.getTopicFlag(topic, NtInstance.FLAG_RETAINED);
  }

  public static void setTopicCached(int topic, boolean value) {
    NtInstance ii = inst(topic);
    if (ii != null) {
      ii.setTopicCached(topic, value);
    }
  }

  public static boolean getTopicCached(int topic) {
    NtInstance ii = inst(topic);
    return ii != null && !ii.getTopicFlag(topic, NtInstance.FLAG_UNCACHED);
  }

  public static String getTopicTypeString(int topic) {
    NtInstance ii = inst(topic);
    return ii == null ? "" : ii.getTopicTypeString(topic);
  }

  public static boolean getTopicExists(int topic) {
    NtInstance ii = inst(topic);
    return ii != null && ii.getTopicExists(topic);
  }

  public static String getTopicProperty(int topic, String name) {
    NtInstance ii = inst(topic);
    return ii == null ? "null" : ii.getTopicProperty(topic, name);
  }

  public static void setTopicProperty(int topic, String name, String value) {
    Object j;
    try {
      j = NtJson.parse(value);
    } catch (NtJson.ParseException e) {
      throw new IllegalArgumentException("could not parse value JSON: " + e.getMessage());
    }
    NtInstance ii = inst(topic);
    if (ii != null) {
      ii.setTopicProperty(topic, name, j);
    }
  }

  public static void deleteTopicProperty(int topic, String name) {
    NtInstance ii = inst(topic);
    if (ii != null) {
      ii.deleteTopicProperty(topic, name);
    }
  }

  public static String getTopicProperties(int topic) {
    NtInstance ii = inst(topic);
    return ii == null ? "{}" : ii.getTopicProperties(topic);
  }

  public static void setTopicProperties(int topic, String properties) {
    Map<String, Object> update = parseObject(properties, "properties");
    NtInstance ii = inst(topic);
    if (ii != null) {
      ii.setTopicProperties(topic, update);
    }
  }

  public static int subscribe(int topic, int type, String typeStr, PubSubOptions options) {
    NtInstance ii = inst(topic);
    return ii == null ? 0 : ii.subscribe(topic, config(type, typeStr, options));
  }

  public static void unsubscribe(int sub) {
    NtInstance ii = inst(sub);
    if (ii != null) {
      ii.removeSubEntry(sub);
    }
  }

  public static int publish(int topic, int type, String typeStr, PubSubOptions options) {
    NtInstance ii = inst(topic);
    return ii == null ? 0 : ii.publish(topic, config(type, typeStr, options), null);
  }

  public static int publishEx(int topic, int type, String typeStr, String properties, PubSubOptions options) {
    Map<String, Object> props = parseObject(properties, "properties");
    NtInstance ii = inst(topic);
    return ii == null ? 0 : ii.publish(topic, config(type, typeStr, options), props);
  }

  public static void unpublish(int pubentry) {
    NtInstance ii = inst(pubentry);
    if (ii != null) {
      ii.unpublish(pubentry);
    }
  }

  public static void releaseEntry(int entry) {
    NtInstance ii = inst(entry);
    if (ii != null) {
      ii.removeSubEntry(entry);
    }
  }

  public static void release(int pubsubentry) {
    NtInstance ii = inst(pubsubentry);
    if (ii != null) {
      ii.release(pubsubentry);
    }
  }

  public static int getTopicFromHandle(int pubsubentry) {
    NtInstance ii = inst(pubsubentry);
    return ii == null ? 0 : ii.getTopicFromHandle(pubsubentry);
  }

  public static int subscribeMultiple(int inst, String[] prefixes, PubSubOptions options) {
    checkedStrings(prefixes, "prefixes cannot be null", "null string in prefixes");
    NtInstance ii = NtInstance.typed(inst, NtInstance.H_INSTANCE);
    return ii == null ? 0 : ii.subscribeMultiple(prefixes, multiConfig(options));
  }

  public static void unsubscribeMultiple(int sub) {
    NtInstance ii = NtInstance.typed(sub, NtInstance.H_MULTISUB);
    if (ii != null) {
      ii.unsubscribeMultiple(sub);
    }
  }

  // untyped values

  public static NetworkTableValue[] readQueueValue(int subentry) {
    NtValue[] q = readQueue(subentry);
    NetworkTableValue[] out = new NetworkTableValue[q.length];
    for (int i = 0; i < q.length; i++) {
      out[i] = valueOf(q[i]);
    }
    return out;
  }

  public static NetworkTableValue getValue(int entry) {
    return valueOf(entryValue(entry));
  }

  public static void setEntryFlags(int entry, int flags) {
    NtInstance ii = inst(entry);
    if (ii != null) {
      ii.setEntryFlags(entry, flags);
    }
  }

  public static int getEntryFlags(int entry) {
    NtInstance ii = inst(entry);
    return ii == null ? 0 : ii.getEntryFlags(entry);
  }

  public static TopicInfo getTopicInfo(NetworkTableInstance inst, int topic) {
    NtInstance ii = inst(topic);
    NtInstance.Topic t = ii == null ? null : ii.topic(topic);
    return t == null ? new TopicInfo(inst, 0, "", 0, "") : topicInfo(inst, t);
  }

  // raw values with ranges

  private static void checkRange(Object value, int start, int len, int length) {
    if (value == null) {
      throw new NullPointerException("value is null");
    }
    if (start < 0) {
      throw new IndexOutOfBoundsException("start must be >= 0");
    }
    if (len < 0) {
      throw new IndexOutOfBoundsException("len must be >= 0");
    }
    if (start + len > length) {
      throw new IndexOutOfBoundsException("start + len must be smaller than array length");
    }
  }

  private static byte[] bufferBytes(ByteBuffer value, int start, int len) {
    checkRange(value, start, len, value.capacity());
    byte[] b = new byte[len];
    ByteBuffer d = value.duplicate();
    d.clear();
    d.position(start);
    d.get(b);
    return b;
  }

  public static boolean setRaw(int entry, long time, byte[] value, int start, int len) {
    checkRange(value, start, len, value == null ? 0 : value.length);
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofRaw(value, start, len, time));
  }

  public static boolean setRawBuffer(int entry, long time, ByteBuffer value, int start, int len) {
    byte[] b = bufferBytes(value, start, len);
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofRaw(b, 0, b.length, time));
  }

  public static boolean setDefaultRaw(int entry, long time, byte[] defaultValue, int start, int len) {
    checkRange(defaultValue, start, len, defaultValue == null ? 0 : defaultValue.length);
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofRaw(defaultValue, start, len, 1));
  }

  public static boolean setDefaultRawBuffer(int entry, long time, ByteBuffer defaultValue, int start, int len) {
    byte[] b = bufferBytes(defaultValue, start, len);
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofRaw(b, 0, b.length, 1));
  }

  // typed values

  public static TimestampedBoolean getAtomicBoolean(int subentry, boolean defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (v.type == NtValue.BOOLEAN)) {
      return new TimestampedBoolean(v.time, v.serverTime, v.getBoolean());
    }
    return new TimestampedBoolean(0, 0, defaultValue);
  }

  public static TimestampedBoolean[] readQueueBoolean(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedBoolean[] out = new TimestampedBoolean[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (v.type == NtValue.BOOLEAN) {
        out[n++] = new TimestampedBoolean(v.time, v.serverTime, v.getBoolean());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean[] readQueueValuesBoolean(int subentry) {
    NtValue[] q = readQueue(subentry);
    boolean[] out = new boolean[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (v.type == NtValue.BOOLEAN) {
        out[n++] = v.getBoolean();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean setBoolean(int entry, long time, boolean value) {
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofBoolean(value, time));
  }

  public static boolean setDefaultBoolean(int entry, long time, boolean defaultValue) {
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofBoolean(defaultValue, 1));
  }

  public static boolean getBoolean(int entry, boolean defaultValue) {
    NtValue v = peek(entry);
    if (v != null && (v.type == NtValue.BOOLEAN)) {
      return v.getBoolean();
    }
    return defaultValue;
  }

  public static TimestampedInteger getAtomicInteger(int subentry, long defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (NtValue.isNumeric(v.type))) {
      return new TimestampedInteger(v.time, v.serverTime, v.asLong());
    }
    return new TimestampedInteger(0, 0, defaultValue);
  }

  public static TimestampedInteger[] readQueueInteger(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedInteger[] out = new TimestampedInteger[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumeric(v.type)) {
        out[n++] = new TimestampedInteger(v.time, v.serverTime, v.asLong());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static long[] readQueueValuesInteger(int subentry) {
    NtValue[] q = readQueue(subentry);
    long[] out = new long[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumeric(v.type)) {
        out[n++] = v.asLong();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean setInteger(int entry, long time, long value) {
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofInteger(value, time));
  }

  public static boolean setDefaultInteger(int entry, long time, long defaultValue) {
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofInteger(defaultValue, 1));
  }

  public static long getInteger(int entry, long defaultValue) {
    NtValue v = peek(entry);
    if (v != null && (NtValue.isNumeric(v.type))) {
      return v.asLong();
    }
    return defaultValue;
  }

  public static TimestampedFloat getAtomicFloat(int subentry, float defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (NtValue.isNumeric(v.type))) {
      return new TimestampedFloat(v.time, v.serverTime, v.asFloat());
    }
    return new TimestampedFloat(0, 0, defaultValue);
  }

  public static TimestampedFloat[] readQueueFloat(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedFloat[] out = new TimestampedFloat[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumeric(v.type)) {
        out[n++] = new TimestampedFloat(v.time, v.serverTime, v.asFloat());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static float[] readQueueValuesFloat(int subentry) {
    NtValue[] q = readQueue(subentry);
    float[] out = new float[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumeric(v.type)) {
        out[n++] = v.asFloat();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean setFloat(int entry, long time, float value) {
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofFloat(value, time));
  }

  public static boolean setDefaultFloat(int entry, long time, float defaultValue) {
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofFloat(defaultValue, 1));
  }

  public static float getFloat(int entry, float defaultValue) {
    NtValue v = peek(entry);
    if (v != null && (NtValue.isNumeric(v.type))) {
      return v.asFloat();
    }
    return defaultValue;
  }

  public static TimestampedDouble getAtomicDouble(int subentry, double defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (NtValue.isNumeric(v.type))) {
      return new TimestampedDouble(v.time, v.serverTime, v.asDouble());
    }
    return new TimestampedDouble(0, 0, defaultValue);
  }

  public static TimestampedDouble[] readQueueDouble(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedDouble[] out = new TimestampedDouble[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumeric(v.type)) {
        out[n++] = new TimestampedDouble(v.time, v.serverTime, v.asDouble());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static double[] readQueueValuesDouble(int subentry) {
    NtValue[] q = readQueue(subentry);
    double[] out = new double[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumeric(v.type)) {
        out[n++] = v.asDouble();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean setDouble(int entry, long time, double value) {
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofDouble(value, time));
  }

  public static boolean setDefaultDouble(int entry, long time, double defaultValue) {
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofDouble(defaultValue, 1));
  }

  public static double getDouble(int entry, double defaultValue) {
    NtValue v = peek(entry);
    if (v != null && (NtValue.isNumeric(v.type))) {
      return v.asDouble();
    }
    return defaultValue;
  }

  public static TimestampedString getAtomicString(int subentry, String defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (v.type == NtValue.STRING)) {
      return new TimestampedString(v.time, v.serverTime, v.getString());
    }
    return new TimestampedString(0, 0, defaultValue);
  }

  public static TimestampedString[] readQueueString(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedString[] out = new TimestampedString[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (v.type == NtValue.STRING) {
        out[n++] = new TimestampedString(v.time, v.serverTime, v.getString());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static String[] readQueueValuesString(int subentry) {
    NtValue[] q = readQueue(subentry);
    String[] out = new String[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (v.type == NtValue.STRING) {
        out[n++] = v.getString();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean setString(int entry, long time, String value) {
    if (value == null) {
      throw new NullPointerException("value cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofString(value, time));
  }

  public static boolean setDefaultString(int entry, long time, String defaultValue) {
    if (defaultValue == null) {
      throw new NullPointerException("defaultValue cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofString(defaultValue, 1));
  }

  public static String getString(int entry, String defaultValue) {
    NtValue v = entryValue(entry);
    if (v.type != NtValue.STRING) {
      return defaultValue;
    }
    return v.getString();
  }

  public static TimestampedRaw getAtomicRaw(int subentry, byte[] defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (v.type == NtValue.RAW)) {
      return new TimestampedRaw(v.time, v.serverTime, v.getRaw().clone());
    }
    return new TimestampedRaw(0, 0, defaultValue);
  }

  public static TimestampedRaw[] readQueueRaw(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedRaw[] out = new TimestampedRaw[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (v.type == NtValue.RAW) {
        out[n++] = new TimestampedRaw(v.time, v.serverTime, v.getRaw().clone());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static byte[][] readQueueValuesRaw(int subentry) {
    NtValue[] q = readQueue(subentry);
    byte[][] out = new byte[q.length][];
    int n = 0;
    for (NtValue v : q) {
      if (v.type == NtValue.RAW) {
        out[n++] = v.getRaw().clone();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static byte[] getRaw(int entry, byte[] defaultValue) {
    NtValue v = entryValue(entry);
    if (v.type != NtValue.RAW) {
      return defaultValue;
    }
    return v.getRaw().clone();
  }

  public static TimestampedBooleanArray getAtomicBooleanArray(int subentry, boolean[] defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (v.type == NtValue.BOOLEAN_ARRAY)) {
      return new TimestampedBooleanArray(v.time, v.serverTime, v.getBooleanArray().clone());
    }
    return new TimestampedBooleanArray(0, 0, defaultValue);
  }

  public static TimestampedBooleanArray[] readQueueBooleanArray(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedBooleanArray[] out = new TimestampedBooleanArray[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (v.type == NtValue.BOOLEAN_ARRAY) {
        out[n++] = new TimestampedBooleanArray(v.time, v.serverTime, v.getBooleanArray().clone());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean[][] readQueueValuesBooleanArray(int subentry) {
    NtValue[] q = readQueue(subentry);
    boolean[][] out = new boolean[q.length][];
    int n = 0;
    for (NtValue v : q) {
      if (v.type == NtValue.BOOLEAN_ARRAY) {
        out[n++] = v.getBooleanArray().clone();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean setBooleanArray(int entry, long time, boolean[] value) {
    if (value == null) {
      throw new NullPointerException("value cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofBooleanArray(value, time));
  }

  public static boolean setDefaultBooleanArray(int entry, long time, boolean[] defaultValue) {
    if (defaultValue == null) {
      throw new NullPointerException("defaultValue cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofBooleanArray(defaultValue, 1));
  }

  public static boolean[] getBooleanArray(int entry, boolean[] defaultValue) {
    NtValue v = entryValue(entry);
    if (v.type != NtValue.BOOLEAN_ARRAY) {
      return defaultValue;
    }
    return v.getBooleanArray().clone();
  }

  public static TimestampedIntegerArray getAtomicIntegerArray(int subentry, long[] defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (NtValue.isNumericArray(v.type))) {
      return new TimestampedIntegerArray(v.time, v.serverTime, v.asLongArray());
    }
    return new TimestampedIntegerArray(0, 0, defaultValue);
  }

  public static TimestampedIntegerArray[] readQueueIntegerArray(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedIntegerArray[] out = new TimestampedIntegerArray[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumericArray(v.type)) {
        out[n++] = new TimestampedIntegerArray(v.time, v.serverTime, v.asLongArray());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static long[][] readQueueValuesIntegerArray(int subentry) {
    NtValue[] q = readQueue(subentry);
    long[][] out = new long[q.length][];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumericArray(v.type)) {
        out[n++] = v.asLongArray();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean setIntegerArray(int entry, long time, long[] value) {
    if (value == null) {
      throw new NullPointerException("value cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofIntegerArray(value, time));
  }

  public static boolean setDefaultIntegerArray(int entry, long time, long[] defaultValue) {
    if (defaultValue == null) {
      throw new NullPointerException("defaultValue cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofIntegerArray(defaultValue, 1));
  }

  public static long[] getIntegerArray(int entry, long[] defaultValue) {
    NtValue v = entryValue(entry);
    if (v.type != NtValue.INTEGER_ARRAY) {
      return defaultValue;
    }
    return v.asLongArray();
  }

  public static TimestampedFloatArray getAtomicFloatArray(int subentry, float[] defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (NtValue.isNumericArray(v.type))) {
      return new TimestampedFloatArray(v.time, v.serverTime, v.asFloatArray());
    }
    return new TimestampedFloatArray(0, 0, defaultValue);
  }

  public static TimestampedFloatArray[] readQueueFloatArray(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedFloatArray[] out = new TimestampedFloatArray[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumericArray(v.type)) {
        out[n++] = new TimestampedFloatArray(v.time, v.serverTime, v.asFloatArray());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static float[][] readQueueValuesFloatArray(int subentry) {
    NtValue[] q = readQueue(subentry);
    float[][] out = new float[q.length][];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumericArray(v.type)) {
        out[n++] = v.asFloatArray();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean setFloatArray(int entry, long time, float[] value) {
    if (value == null) {
      throw new NullPointerException("value cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofFloatArray(value, time));
  }

  public static boolean setDefaultFloatArray(int entry, long time, float[] defaultValue) {
    if (defaultValue == null) {
      throw new NullPointerException("defaultValue cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofFloatArray(defaultValue, 1));
  }

  public static float[] getFloatArray(int entry, float[] defaultValue) {
    NtValue v = entryValue(entry);
    if (v.type != NtValue.FLOAT_ARRAY) {
      return defaultValue;
    }
    return v.asFloatArray();
  }

  public static TimestampedDoubleArray getAtomicDoubleArray(int subentry, double[] defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (NtValue.isNumericArray(v.type))) {
      return new TimestampedDoubleArray(v.time, v.serverTime, v.asDoubleArray());
    }
    return new TimestampedDoubleArray(0, 0, defaultValue);
  }

  public static TimestampedDoubleArray[] readQueueDoubleArray(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedDoubleArray[] out = new TimestampedDoubleArray[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumericArray(v.type)) {
        out[n++] = new TimestampedDoubleArray(v.time, v.serverTime, v.asDoubleArray());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static double[][] readQueueValuesDoubleArray(int subentry) {
    NtValue[] q = readQueue(subentry);
    double[][] out = new double[q.length][];
    int n = 0;
    for (NtValue v : q) {
      if (NtValue.isNumericArray(v.type)) {
        out[n++] = v.asDoubleArray();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean setDoubleArray(int entry, long time, double[] value) {
    if (value == null) {
      throw new NullPointerException("value cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofDoubleArray(value, time));
  }

  public static boolean setDefaultDoubleArray(int entry, long time, double[] defaultValue) {
    if (defaultValue == null) {
      throw new NullPointerException("defaultValue cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofDoubleArray(defaultValue, 1));
  }

  public static double[] getDoubleArray(int entry, double[] defaultValue) {
    NtValue v = entryValue(entry);
    if (v.type != NtValue.DOUBLE_ARRAY) {
      return defaultValue;
    }
    return v.asDoubleArray();
  }

  public static TimestampedStringArray getAtomicStringArray(int subentry, String[] defaultValue) {
    NtValue v = peek(subentry);
    if (v != null && (v.type == NtValue.STRING_ARRAY)) {
      return new TimestampedStringArray(v.time, v.serverTime, v.getStringArray().clone());
    }
    return new TimestampedStringArray(0, 0, defaultValue);
  }

  public static TimestampedStringArray[] readQueueStringArray(int subentry) {
    NtValue[] q = readQueue(subentry);
    TimestampedStringArray[] out = new TimestampedStringArray[q.length];
    int n = 0;
    for (NtValue v : q) {
      if (v.type == NtValue.STRING_ARRAY) {
        out[n++] = new TimestampedStringArray(v.time, v.serverTime, v.getStringArray().clone());
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static String[][] readQueueValuesStringArray(int subentry) {
    NtValue[] q = readQueue(subentry);
    String[][] out = new String[q.length][];
    int n = 0;
    for (NtValue v : q) {
      if (v.type == NtValue.STRING_ARRAY) {
        out[n++] = v.getStringArray().clone();
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  public static boolean setStringArray(int entry, long time, String[] value) {
    if (value == null) {
      throw new NullPointerException("value cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setEntryValue(entry, NtValue.ofStringArray(value, time));
  }

  public static boolean setDefaultStringArray(int entry, long time, String[] defaultValue) {
    if (defaultValue == null) {
      throw new NullPointerException("defaultValue cannot be null");
    }
    NtInstance ii = inst(entry);
    return ii != null && ii.setDefaultEntryValue(entry, NtValue.ofStringArray(defaultValue, 1));
  }

  public static String[] getStringArray(int entry, String[] defaultValue) {
    NtValue v = entryValue(entry);
    if (v.type != NtValue.STRING_ARRAY) {
      return defaultValue;
    }
    return v.getStringArray().clone();
  }

  // listeners

  public static int createListenerPoller(int inst) {
    NtInstance ii = NtInstance.typed(inst, NtInstance.H_INSTANCE);
    return ii == null ? 0 : ii.createListenerPoller();
  }

  public static void destroyListenerPoller(int poller) {
    NtInstance ii = NtInstance.typed(poller, NtInstance.H_POLLER);
    if (ii != null) {
      ii.destroyListenerPoller(poller);
    }
  }

  public static int addListener(int poller, String[] prefixes, int mask) {
    checkedStrings(prefixes, "prefixes cannot be null", "prefixes cannot contain null");
    NtInstance ii = NtInstance.typed(poller, NtInstance.H_POLLER);
    return ii == null ? 0 : ii.addListener(poller, prefixes, mask);
  }

  public static int addListener(int poller, int handle, int mask) {
    NtInstance ii = NtInstance.typed(poller, NtInstance.H_POLLER);
    return ii == null ? 0 : ii.addListener(poller, handle, mask);
  }

  public static NetworkTableEvent[] readListenerQueue(NetworkTableInstance inst, int poller) {
    NtInstance ii = NtInstance.typed(poller, NtInstance.H_POLLER);
    if (ii == null) {
      return new NetworkTableEvent[0];
    }
    List<NtInstance.Event> q = ii.readListenerQueue(poller);
    NetworkTableEvent[] out = new NetworkTableEvent[q.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = event(inst, q.get(i));
    }
    return out;
  }

  private static NetworkTableEvent event(NetworkTableInstance inst, NtInstance.Event e) {
    ConnectionInfo conn = null;
    TopicInfo topic = null;
    ValueEventData value = null;
    LogMessage log = null;
    TimeSyncEventData sync = null;
    switch (e.kind) {
      case NtInstance.EVENT_TOPIC -> topic = new TopicInfo(inst, e.topicHandle, e.name, e.type, e.typeStr);
      case NtInstance.EVENT_VALUE -> value = new ValueEventData(inst, e.topicHandle, e.subentry, valueOf(e.value));
      case NtInstance.EVENT_LOG -> log = new LogMessage(e.level, e.filename, e.line, e.message);
      case NtInstance.EVENT_TIMESYNC -> sync = new TimeSyncEventData(e.offset, e.rtt2, e.valid);
      default -> { }
    }
    return new NetworkTableEvent(inst, e.listener, e.flags, conn, topic, value, log, sync);
  }

  public static void removeListener(int listener) {
    NtInstance ii = NtInstance.typed(listener, NtInstance.H_LISTENER);
    if (ii != null) {
      ii.removeListener(listener);
    }
  }

  public static int addLogger(int poller, int minLevel, int maxLevel) {
    NtInstance ii = NtInstance.typed(poller, NtInstance.H_POLLER);
    return ii == null ? 0 : ii.addLogger(poller, minLevel, maxLevel);
  }

  // network: all local, startServer and startClient enter server mode

  private static NtInstance instance(int inst) {
    return NtInstance.typed(inst, NtInstance.H_INSTANCE);
  }

  public static int getNetworkMode(int inst) {
    NtInstance ii = instance(inst);
    return ii == null ? 0 : ii.getNetworkMode();
  }

  public static void startLocal(int inst) {
    NtInstance ii = instance(inst);
    if (ii != null) {
      ii.startLocal();
    }
  }

  public static void stopLocal(int inst) {
    NtInstance ii = instance(inst);
    if (ii != null) {
      ii.stopLocal();
    }
  }

  public static void startServer(
      int inst, String persistFilename, String listenAddress, String mdnsService, int port) {
    if (persistFilename == null) {
      throw new NullPointerException("persistFilename cannot be null");
    }
    if (listenAddress == null) {
      throw new NullPointerException("listenAddress cannot be null");
    }
    if (mdnsService == null) {
      throw new NullPointerException("mdnsService cannot be null");
    }
    NtInstance ii = instance(inst);
    if (ii != null) {
      ii.startServer();
    }
  }

  public static void stopServer(int inst) {
    NtInstance ii = instance(inst);
    if (ii != null) {
      ii.stopNetwork();
    }
  }

  public static void startClient(int inst, String identity) {
    if (identity == null) {
      throw new NullPointerException("identity cannot be null");
    }
    NtInstance ii = instance(inst);
    if (ii != null) {
      ii.startServer();
    }
  }

  public static void stopClient(int inst) {
    stopServer(inst);
  }

  public static void setServer(int inst, String serverName, int port) {
    if (serverName == null) {
      throw new NullPointerException("serverName cannot be null");
    }
  }

  public static void setServer(int inst, String[] serverNames, int[] ports) {
    if (serverNames == null) {
      throw new NullPointerException("serverNames cannot be null");
    }
    if (ports == null) {
      throw new NullPointerException("ports cannot be null");
    }
    if (serverNames.length != ports.length) {
      throw new IllegalArgumentException("serverNames and ports arrays must be the same size");
    }
    checkedStrings(serverNames, "serverNames cannot be null", "null string in serverNames");
  }

  public static void setServerMdns(int inst, String serviceName) {
    if (serviceName == null) {
      throw new NullPointerException("serviceName cannot be null");
    }
  }

  public static void setServerMdns(int inst, String serviceName, String serverName, int port) {
    if (serviceName == null) {
      throw new NullPointerException("serviceName cannot be null");
    }
    if (serverName == null) {
      throw new NullPointerException("serverName cannot be null");
    }
  }

  public static void setServerMdns(int inst, String serviceName, String[] serverNames, int[] ports) {
    checkedServers(serviceName, serverNames, ports);
  }

  public static void setServerMdns(
      int inst, String serviceName, int mdnsPort, String[] serverNames, int[] ports) {
    checkedServers(serviceName, serverNames, ports);
  }

  private static void checkedServers(String serviceName, String[] serverNames, int[] ports) {
    if (serviceName == null) {
      throw new NullPointerException("serviceName cannot be null");
    }
    if (serverNames == null) {
      throw new NullPointerException("serverNames cannot be null");
    }
    if (ports == null) {
      throw new NullPointerException("ports cannot be null");
    }
    if (serverNames.length != ports.length) {
      throw new IllegalArgumentException("serverNames and ports arrays must be the same size");
    }
    checkedStrings(serverNames, "serverNames cannot be null", "null string in serverNames");
  }

  public static void setServerTeam(int inst, String team, int port) {
    if (team == null) {
      throw new NullPointerException("team cannot be null");
    }
  }

  public static void setServerFixed(int inst, String team, int port) {
    if (team == null) {
      throw new NullPointerException("team cannot be null");
    }
  }

  public static void disconnect(int inst) {}

  public static void startDSClient(int inst, int port) {}

  public static void stopDSClient(int inst) {}

  public static void flushLocal(int inst) {}

  public static void flush(int inst) {}

  public static ConnectionInfo[] getConnections(int inst) {
    return new ConnectionInfo[0];
  }

  public static boolean isConnected(int inst) {
    NtInstance ii = instance(inst);
    return ii != null && ii.isConnected();
  }

  public static OptionalLong getServerTimeOffset(int inst) {
    NtInstance ii = instance(inst);
    return ii != null && ii.hasServerTimeOffset() ? OptionalLong.of(0) : OptionalLong.empty();
  }

  public static long now() {
    return NtTime.now();
  }

  // data logs are accepted and ignored

  public static int startEntryDataLog(int inst, long log, String prefix, String logPrefix) {
    NtInstance ii = instance(inst);
    return ii == null ? 0 : ii.startDataLog();
  }

  public static void stopEntryDataLog(int logger) {
    NtInstance ii = NtInstance.typed(logger, NtInstance.H_DATALOGGER);
    if (ii != null) {
      ii.stopDataLog(logger);
    }
  }

  public static int startConnectionDataLog(int inst, long log, String name) {
    NtInstance ii = instance(inst);
    return ii == null ? 0 : NtInstance.handle(ii.index, 0, NtInstance.H_CONN_DATALOGGER);
  }

  public static void stopConnectionDataLog(int logger) {}
}
