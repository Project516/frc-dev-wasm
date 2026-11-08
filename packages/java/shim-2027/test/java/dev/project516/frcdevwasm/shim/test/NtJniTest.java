package dev.project516.frcdevwasm.shim.test;

import dev.project516.frcdevwasm.shim.networktables.NetworkTablesJNIImpl;
import org.wpilib.networktables.NetworkTableEvent;
import org.wpilib.networktables.NetworkTableValue;
import org.wpilib.networktables.PubSubOptions;
import org.wpilib.networktables.TimestampedDouble;
import org.wpilib.networktables.TimestampedDoubleArray;
import org.wpilib.networktables.TopicInfo;

/** Drives NetworkTablesJNIImpl directly. */
public final class NtJniTest {
  private static final int T_BOOLEAN = 0x01;
  private static final int T_DOUBLE = 0x02;
  private static final int T_STRING = 0x04;
  private static final int T_RAW = 0x08;
  private static final int T_BOOLEAN_ARRAY = 0x10;
  private static final int T_DOUBLE_ARRAY = 0x20;
  private static final int T_STRING_ARRAY = 0x40;
  private static final int T_INTEGER = 0x100;
  private static final int T_FLOAT = 0x200;
  private static final int T_INTEGER_ARRAY = 0x400;
  private static final int T_FLOAT_ARRAY = 0x800;

  private static final int PUBLISH = 0x08;
  private static final int UNPUBLISH = 0x10;
  private static final int PROPERTIES = 0x20;
  private static final int VALUE_ALL = 0xc0;
  private static final int IMMEDIATE = 0x01;

  private static final PubSubOptions NO_OPTIONS = null;

  private static int flags(NetworkTableEvent e) {
    try {
      java.lang.reflect.Field f = NetworkTableEvent.class.getDeclaredField("m_flags");
      f.setAccessible(true);
      return f.getInt(e);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }

  private static int newInst() {
    return NetworkTablesJNIImpl.createInstance();
  }

  private static PubSubOptions keepDuplicates(int pollStorage) {
    PubSubOptions o = new PubSubOptions();
    o.keepDuplicates = true;
    o.pollStorage = pollStorage;
    return o;
  }

  public static void testHandleLayout() {
    int inst = newInst();
    T.eq(19, (inst >> 24) & 0x7f, "instance handle type");
    int topic = NetworkTablesJNIImpl.getTopic(inst, "/a");
    T.eq(23, (topic >> 24) & 0x7f, "topic handle type");
    T.eq((inst >> 20) & 0xf, (topic >> 20) & 0xf, "instance index in topic handle");
    T.eq(0, topic & 0xfffff, "first topic index");
    int topic2 = NetworkTablesJNIImpl.getTopic(inst, "/b");
    T.eq(1, topic2 & 0xfffff, "second topic index");
    T.eq(topic, NetworkTablesJNIImpl.getTopic(inst, "/a"), "same name same handle");
    T.eq(inst, NetworkTablesJNIImpl.getInstanceFromHandle(topic), "instance from topic");
    T.eq(0, NetworkTablesJNIImpl.getTopic(inst, ""), "empty name");
    int pub = NetworkTablesJNIImpl.publish(topic, T_DOUBLE, "double", NO_OPTIONS);
    T.eq(25, (pub >> 24) & 0x7f, "publisher handle type");
    int sub = NetworkTablesJNIImpl.subscribe(topic, T_DOUBLE, "double", NO_OPTIONS);
    T.eq(24, (sub >> 24) & 0x7f, "subscriber handle type");
    int entry = NetworkTablesJNIImpl.getEntry(inst, "/c");
    T.eq(18, (entry >> 24) & 0x7f, "entry handle type");
    T.eq(entry, NetworkTablesJNIImpl.getEntry(inst, "/c"), "name entry is cached");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testDoubleRoundTrip() {
    int inst = newInst();
    int topic = NetworkTablesJNIImpl.getTopic(inst, "/x");
    int pub = NetworkTablesJNIImpl.publish(topic, T_DOUBLE, "double", NO_OPTIONS);
    int sub = NetworkTablesJNIImpl.subscribe(topic, T_DOUBLE, "double", NO_OPTIONS);
    T.eq(7.5, NetworkTablesJNIImpl.getDouble(sub, 7.5), "default before set");
    T.check(NetworkTablesJNIImpl.setDouble(pub, 0, 1.25), "set accepted");
    T.eq(1.25, NetworkTablesJNIImpl.getDouble(sub, 0));
    TimestampedDouble ts = NetworkTablesJNIImpl.getAtomicDouble(sub, 0);
    T.eq(1.25, ts.value);
    T.check(ts.timestamp > 0, "timestamp assigned");
    T.eq(1L, ts.serverTime, "local server time is 1");
    T.eq("double", NetworkTablesJNIImpl.getTopicTypeString(topic));
    T.eq(T_DOUBLE, NetworkTablesJNIImpl.getTopicType(topic));
    T.check(NetworkTablesJNIImpl.getTopicExists(topic), "exists");
    NetworkTablesJNIImpl.unpublish(pub);
    T.check(!NetworkTablesJNIImpl.getTopicExists(topic), "gone after unpublish");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testEntryApi() {
    int inst = newInst();
    int e = NetworkTablesJNIImpl.getEntry(inst, "/entry");
    T.eq(-1.0, NetworkTablesJNIImpl.getDouble(e, -1.0));
    T.check(NetworkTablesJNIImpl.setDouble(e, 0, 3.5), "set creates publisher");
    T.eq(3.5, NetworkTablesJNIImpl.getDouble(e, -1.0));
    T.eq(T_DOUBLE, NetworkTablesJNIImpl.getType(e));
    T.eq("/entry", NetworkTablesJNIImpl.getEntryName(e));
    T.check(!NetworkTablesJNIImpl.setString(e, 0, "nope"), "type change rejected");
    T.check(NetworkTablesJNIImpl.setInteger(e, 0, 4), "numeric types convert");
    T.eq(4.0, NetworkTablesJNIImpl.getDouble(e, 0));
    NetworkTableValue v = NetworkTablesJNIImpl.getValue(e);
    T.eq(4.0, v.getValue());
    T.check(NetworkTablesJNIImpl.getEntryLastChange(e) > 0, "last change");
    NetworkTablesJNIImpl.setEntryFlags(e, 1);
    T.eq(1, NetworkTablesJNIImpl.getEntryFlags(e));
    T.check(NetworkTablesJNIImpl.getTopicPersistent(NetworkTablesJNIImpl.getTopic(inst, "/entry")), "persistent flag");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testDefaults() {
    int inst = newInst();
    int e = NetworkTablesJNIImpl.getEntry(inst, "/d");
    T.check(NetworkTablesJNIImpl.setDefaultDouble(e, 0, 9.0), "default set");
    T.eq(9.0, NetworkTablesJNIImpl.getDouble(e, 0));
    T.eq(0L, NetworkTablesJNIImpl.getEntryLastChange(e), "default time is 0");
    T.check(!NetworkTablesJNIImpl.setDefaultDouble(e, 0, 1.0), "second default ignored");
    T.eq(9.0, NetworkTablesJNIImpl.getDouble(e, 0));
    NetworkTablesJNIImpl.setDouble(e, 0, 2.0);
    T.eq(2.0, NetworkTablesJNIImpl.getDouble(e, 0));
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testAllTypes() {
    int inst = newInst();
    int e;
    e = NetworkTablesJNIImpl.getEntry(inst, "/bool");
    NetworkTablesJNIImpl.setBoolean(e, 0, true);
    T.eq(true, NetworkTablesJNIImpl.getBoolean(e, false));
    e = NetworkTablesJNIImpl.getEntry(inst, "/int");
    NetworkTablesJNIImpl.setInteger(e, 0, 1L << 40);
    T.eq(1L << 40, NetworkTablesJNIImpl.getInteger(e, 0));
    e = NetworkTablesJNIImpl.getEntry(inst, "/float");
    NetworkTablesJNIImpl.setFloat(e, 0, 1.5f);
    T.eq(1.5f, NetworkTablesJNIImpl.getFloat(e, 0));
    e = NetworkTablesJNIImpl.getEntry(inst, "/string");
    NetworkTablesJNIImpl.setString(e, 0, "héllo");
    T.eq("héllo", NetworkTablesJNIImpl.getString(e, ""));
    e = NetworkTablesJNIImpl.getEntry(inst, "/raw");
    byte[] raw = {1, 2, 3, 4, 5};
    NetworkTablesJNIImpl.setRaw(e, 0, raw, 1, 3);
    T.eq(new byte[] {2, 3, 4}, NetworkTablesJNIImpl.getRaw(e, null));
    raw[2] = 99;
    T.eq(new byte[] {2, 3, 4}, NetworkTablesJNIImpl.getRaw(e, null), "value copied on set");
    e = NetworkTablesJNIImpl.getEntry(inst, "/ba");
    NetworkTablesJNIImpl.setBooleanArray(e, 0, new boolean[] {true, false});
    T.eq(new boolean[] {true, false}, NetworkTablesJNIImpl.getBooleanArray(e, null));
    e = NetworkTablesJNIImpl.getEntry(inst, "/ia");
    NetworkTablesJNIImpl.setIntegerArray(e, 0, new long[] {1, 2});
    T.eq(new long[] {1, 2}, NetworkTablesJNIImpl.getIntegerArray(e, null));
    e = NetworkTablesJNIImpl.getEntry(inst, "/fa");
    NetworkTablesJNIImpl.setFloatArray(e, 0, new float[] {1f, 2.5f});
    T.eq(new float[] {1f, 2.5f}, NetworkTablesJNIImpl.getFloatArray(e, null));
    e = NetworkTablesJNIImpl.getEntry(inst, "/da");
    NetworkTablesJNIImpl.setDoubleArray(e, 0, new double[] {1, 2.5});
    T.eq(new double[] {1, 2.5}, NetworkTablesJNIImpl.getDoubleArray(e, null));
    e = NetworkTablesJNIImpl.getEntry(inst, "/sa");
    NetworkTablesJNIImpl.setStringArray(e, 0, new String[] {"a", "b"});
    T.eq(new String[] {"a", "b"}, NetworkTablesJNIImpl.getStringArray(e, null));

    int[] expected = {T_BOOLEAN, T_INTEGER, T_FLOAT, T_STRING, T_RAW, T_BOOLEAN_ARRAY, T_INTEGER_ARRAY, T_FLOAT_ARRAY, T_DOUBLE_ARRAY, T_STRING_ARRAY};
    String[] names = {"/bool", "/int", "/float", "/string", "/raw", "/ba", "/ia", "/fa", "/da", "/sa"};
    for (int i = 0; i < names.length; i++) {
      T.eq(expected[i], NetworkTablesJNIImpl.getTopicType(NetworkTablesJNIImpl.getTopic(inst, names[i])), names[i]);
    }
    T.eq("int", NetworkTablesJNIImpl.getTopicTypeString(NetworkTablesJNIImpl.getTopic(inst, "/int")));
    T.eq("float[]", NetworkTablesJNIImpl.getTopicTypeString(NetworkTablesJNIImpl.getTopic(inst, "/fa")));
    T.eq(10, NetworkTablesJNIImpl.getTopics(inst, "/", 0).length, "topic count");
    T.eq(2, NetworkTablesJNIImpl.getTopics(inst, "/", T_BOOLEAN | T_STRING).length, "type filter");
    T.eq(1, NetworkTablesJNIImpl.getTopicsStr(inst, "/", new String[] {"raw"}).length, "type string filter");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testNumericConversion() {
    int inst = newInst();
    int topic = NetworkTablesJNIImpl.getTopic(inst, "/n");
    int pub = NetworkTablesJNIImpl.publish(topic, T_INTEGER, "int", NO_OPTIONS);
    int doubleSub = NetworkTablesJNIImpl.subscribe(topic, T_DOUBLE, "double", NO_OPTIONS);
    int stringSub = NetworkTablesJNIImpl.subscribe(topic, T_STRING, "string", NO_OPTIONS);
    NetworkTablesJNIImpl.setInteger(pub, 0, 42);
    T.eq(42.0, NetworkTablesJNIImpl.getDouble(doubleSub, 0), "int read as double");
    T.eq("none", NetworkTablesJNIImpl.getString(stringSub, "none"), "string subscriber inactive");
    int intSub = NetworkTablesJNIImpl.subscribe(topic, T_INTEGER, "int", NO_OPTIONS);
    T.check(NetworkTablesJNIImpl.setDouble(pub, 0, 1.5), "double converted into int topic");
    T.eq(1L, NetworkTablesJNIImpl.getInteger(intSub, 0), "truncated to int");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testKeepDuplicatesAndQueue() {
    int inst = newInst();
    int topic = NetworkTablesJNIImpl.getTopic(inst, "/q");
    int pub = NetworkTablesJNIImpl.publish(topic, T_DOUBLE, "double", NO_OPTIONS);
    int plain = NetworkTablesJNIImpl.subscribe(topic, T_DOUBLE, "double", NO_OPTIONS);
    int keep = NetworkTablesJNIImpl.subscribe(topic, T_DOUBLE, "double", keepDuplicates(10));
    NetworkTablesJNIImpl.setDouble(pub, 0, 1);
    NetworkTablesJNIImpl.setDouble(pub, 0, 1);
    NetworkTablesJNIImpl.setDouble(pub, 0, 2);
    T.eq(new double[] {1, 2}, NetworkTablesJNIImpl.readQueueValuesDouble(keep), "queue");
    T.eq(0, NetworkTablesJNIImpl.readQueueValuesDouble(keep).length, "queue drained");
    T.eq(2.0, NetworkTablesJNIImpl.getDouble(plain, 0));
    // pollStorage 1 keeps only the last value
    TimestampedDouble[] q = NetworkTablesJNIImpl.readQueueDouble(plain);
    T.eq(1, q.length, "depth 1 queue");
    T.eq(2.0, q[0].value);
    // keepDuplicates publisher delivers duplicates
    int dupPub = NetworkTablesJNIImpl.publish(NetworkTablesJNIImpl.getTopic(inst, "/q2"), T_DOUBLE, "double", keepDuplicates(0));
    int dupSub = NetworkTablesJNIImpl.subscribe(NetworkTablesJNIImpl.getTopic(inst, "/q2"), T_DOUBLE, "double", keepDuplicates(10));
    NetworkTablesJNIImpl.readQueueValuesDouble(dupSub);
    NetworkTablesJNIImpl.setDouble(dupPub, 0, 5);
    NetworkTablesJNIImpl.setDouble(dupPub, 0, 5);
    T.eq(2, NetworkTablesJNIImpl.readQueueValuesDouble(dupSub).length, "duplicates kept");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testQueueOverflowAndInitialValue() {
    int inst = newInst();
    int topic = NetworkTablesJNIImpl.getTopic(inst, "/o");
    int pub = NetworkTablesJNIImpl.publish(topic, T_DOUBLE, "double", NO_OPTIONS);
    NetworkTablesJNIImpl.setDouble(pub, 0, 100);
    int sub = NetworkTablesJNIImpl.subscribe(topic, T_DOUBLE, "double", keepDuplicates(3));
    T.eq(new double[] {100}, NetworkTablesJNIImpl.readQueueValuesDouble(sub), "current value queued on subscribe");
    for (int i = 1; i <= 5; i++) {
      NetworkTablesJNIImpl.setDouble(pub, 0, i);
    }
    T.eq(new double[] {3, 4, 5}, NetworkTablesJNIImpl.readQueueValuesDouble(sub), "oldest dropped");
    NetworkTableValue[] none = NetworkTablesJNIImpl.readQueueValue(sub);
    T.eq(0, none.length);
    // an unassigned initial element appears in the untyped queue
    int e = NetworkTablesJNIImpl.getEntry(inst, "/fresh");
    NetworkTableValue[] initial = NetworkTablesJNIImpl.readQueueValue(e);
    T.eq(1, initial.length, "untyped queue has the initial invalid value");
    T.eq(0, initial[0].getType().getValue(), "unassigned");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testExcludeSelf() {
    int inst = newInst();
    int topic = NetworkTablesJNIImpl.getTopic(inst, "/ex");
    PubSubOptions o = new PubSubOptions();
    o.excludeSelf = true;
    o.keepDuplicates = true;
    o.pollStorage = 5;
    int e = NetworkTablesJNIImpl.getEntryImpl(topic, T_DOUBLE, "double", o);
    NetworkTablesJNIImpl.readQueueValuesDouble(e);
    int other = NetworkTablesJNIImpl.publish(topic, T_DOUBLE, "double", NO_OPTIONS);
    NetworkTablesJNIImpl.setDouble(e, 0, 1);
    T.eq(0, NetworkTablesJNIImpl.readQueueValuesDouble(e).length, "own writes excluded");
    NetworkTablesJNIImpl.setDouble(other, 0, 2);
    T.eq(new double[] {2}, NetworkTablesJNIImpl.readQueueValuesDouble(e), "other writers delivered");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testProperties() {
    int inst = newInst();
    int topic = NetworkTablesJNIImpl.getTopic(inst, "/p");
    int pub = NetworkTablesJNIImpl.publishEx(topic, T_DOUBLE, "double", "{\"b\":2,\"a\":\"x\"}", NO_OPTIONS);
    T.eq("{\"a\":\"x\",\"b\":2}", NetworkTablesJNIImpl.getTopicProperties(topic), "sorted compact json");
    T.eq("\"x\"", NetworkTablesJNIImpl.getTopicProperty(topic, "a"));
    T.eq("null", NetworkTablesJNIImpl.getTopicProperty(topic, "missing"));
    NetworkTablesJNIImpl.setTopicProperty(topic, "c", "[1,2.5,true]");
    T.eq("[1,2.5,true]", NetworkTablesJNIImpl.getTopicProperty(topic, "c"));
    NetworkTablesJNIImpl.deleteTopicProperty(topic, "c");
    T.eq("null", NetworkTablesJNIImpl.getTopicProperty(topic, "c"));
    NetworkTablesJNIImpl.setTopicProperty(topic, "a", "null");
    T.eq("{\"b\":2}", NetworkTablesJNIImpl.getTopicProperties(topic), "null deletes");
    NetworkTablesJNIImpl.setTopicPersistent(topic, true);
    NetworkTablesJNIImpl.setTopicRetained(topic, true);
    T.check(NetworkTablesJNIImpl.getTopicPersistent(topic), "persistent");
    T.check(NetworkTablesJNIImpl.getTopicRetained(topic), "retained");
    T.check(NetworkTablesJNIImpl.getTopicCached(topic), "cached by default");
    T.eq("{\"b\":2,\"persistent\":true,\"retained\":true}", NetworkTablesJNIImpl.getTopicProperties(topic));
    NetworkTablesJNIImpl.setTopicProperties(topic, "{\"persistent\":false,\"z\":1}");
    T.check(!NetworkTablesJNIImpl.getTopicPersistent(topic), "persistent cleared through properties");
    NetworkTablesJNIImpl.setTopicCached(topic, false);
    T.check(!NetworkTablesJNIImpl.getTopicCached(topic), "uncached");
    NetworkTablesJNIImpl.setDouble(pub, 0, 1);
    T.eq(0.0, NetworkTablesJNIImpl.getDouble(NetworkTablesJNIImpl.getEntry(inst, "/p"), 0), "uncached topics keep no value");
    T.throwsA(IllegalArgumentException.class, () -> NetworkTablesJNIImpl.setTopicProperty(topic, "x", "{bad"));
    T.throwsA(IllegalArgumentException.class, () -> NetworkTablesJNIImpl.setTopicProperties(topic, "[1]"));
    T.throwsA(IllegalArgumentException.class, () -> NetworkTablesJNIImpl.publishEx(topic, T_DOUBLE, "double", "5", NO_OPTIONS));
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testArgumentChecks() {
    int inst = newInst();
    int e = NetworkTablesJNIImpl.getEntry(inst, "/chk");
    T.throwsA(NullPointerException.class, () -> NetworkTablesJNIImpl.getEntry(inst, null));
    T.throwsA(NullPointerException.class, () -> NetworkTablesJNIImpl.setString(e, 0, null));
    T.throwsA(NullPointerException.class, () -> NetworkTablesJNIImpl.setDoubleArray(e, 0, null));
    T.throwsA(NullPointerException.class, () -> NetworkTablesJNIImpl.setDefaultString(e, 0, null));
    T.throwsA(IndexOutOfBoundsException.class, () -> NetworkTablesJNIImpl.setRaw(e, 0, new byte[2], 1, 3));
    T.throwsA(IndexOutOfBoundsException.class, () -> NetworkTablesJNIImpl.setRaw(e, 0, new byte[2], -1, 1));
    T.throwsA(NullPointerException.class, () -> NetworkTablesJNIImpl.getTopics(inst, null, 0));
    T.eq(0, NetworkTablesJNIImpl.getTopics(inst, "/zzz", 0).length);
    T.check(!NetworkTablesJNIImpl.setDouble(0, 0, 1.0), "bad handle");
    T.eq(3.0, NetworkTablesJNIImpl.getDouble(0, 3.0), "bad handle default");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testTopicListenersImmediateAndLive() {
    int inst = newInst();
    int pubTopic = NetworkTablesJNIImpl.getTopic(inst, "/l/a");
    int pub = NetworkTablesJNIImpl.publish(pubTopic, T_DOUBLE, "double", NO_OPTIONS);
    NetworkTablesJNIImpl.setDouble(pub, 0, 1);
    int poller = NetworkTablesJNIImpl.createListenerPoller(inst);
    int listener = NetworkTablesJNIImpl.addListener(poller, new String[] {"/l/"}, PUBLISH | UNPUBLISH | PROPERTIES | VALUE_ALL | IMMEDIATE);
    T.check(listener != 0, "listener created");
    T.eq(16, (listener >> 24) & 0x7f, "listener handle type");
    NetworkTableEvent[] ev = NetworkTablesJNIImpl.readListenerQueue(null, poller);
    // the immediate bit matches both of the listener's sources, so the publish arrives twice as in ntcore
    T.eq(3, ev.length, "immediate publish (twice) and value");
    T.eq(PUBLISH | IMMEDIATE, flags(ev[0]), "immediate publish flags");
    T.eq("/l/a", ev[0].topicInfo.name);
    T.eq(VALUE_ALL | IMMEDIATE, flags(ev[2]), "immediate value flags");
    T.eq(1.0, ev[2].valueData.value.getValue());
    NetworkTablesJNIImpl.setDouble(pub, 0, 2);
    ev = NetworkTablesJNIImpl.readListenerQueue(null, poller);
    T.eq(1, ev.length);
    T.eq(0x80, flags(ev[0]), "local value flag");
    T.eq(2.0, ev[0].valueData.value.getValue());
    int newPub = NetworkTablesJNIImpl.publish(NetworkTablesJNIImpl.getTopic(inst, "/l/b"), T_STRING, "string", NO_OPTIONS);
    NetworkTablesJNIImpl.setTopicProperty(NetworkTablesJNIImpl.getTopic(inst, "/l/b"), "k", "1");
    NetworkTablesJNIImpl.unpublish(newPub);
    ev = NetworkTablesJNIImpl.readListenerQueue(null, poller);
    T.eq(3, ev.length, "publish, properties, unpublish");
    T.eq(PUBLISH, flags(ev[0]));
    T.eq(PROPERTIES, flags(ev[1]));
    T.eq(UNPUBLISH, flags(ev[2]));
    NetworkTablesJNIImpl.setDouble(NetworkTablesJNIImpl.publish(NetworkTablesJNIImpl.getTopic(inst, "/other"), T_DOUBLE, "double", NO_OPTIONS), 0, 1);
    T.eq(0, NetworkTablesJNIImpl.readListenerQueue(null, poller).length, "prefix filtered");
    NetworkTablesJNIImpl.removeListener(listener);
    NetworkTablesJNIImpl.setDouble(pub, 0, 3);
    T.eq(0, NetworkTablesJNIImpl.readListenerQueue(null, poller).length, "removed listener is silent");
    NetworkTablesJNIImpl.destroyListenerPoller(poller);
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testHandleListeners() {
    int inst = newInst();
    int e = NetworkTablesJNIImpl.getEntry(inst, "/h");
    int poller = NetworkTablesJNIImpl.createListenerPoller(inst);
    int listener = NetworkTablesJNIImpl.addListener(poller, e, VALUE_ALL);
    NetworkTablesJNIImpl.setDouble(e, 0, 5);
    NetworkTableEvent[] ev = NetworkTablesJNIImpl.readListenerQueue(null, poller);
    T.eq(1, ev.length);
    T.eq(e, ev[0].valueData.subentry, "entry handle reported as subentry");
    T.eq(NetworkTablesJNIImpl.getTopic(inst, "/h"), ev[0].valueData.topic);
    T.eq(listener, ev[0].listener);
    int topicListener = NetworkTablesJNIImpl.addListener(poller, NetworkTablesJNIImpl.getTopic(inst, "/h"), VALUE_ALL);
    NetworkTablesJNIImpl.setDouble(e, 0, 6);
    ev = NetworkTablesJNIImpl.readListenerQueue(null, poller);
    T.eq(2, ev.length, "entry and topic listeners");
    NetworkTablesJNIImpl.removeListener(topicListener);
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testMultiSubscriber() {
    int inst = newInst();
    int a = NetworkTablesJNIImpl.publish(NetworkTablesJNIImpl.getTopic(inst, "/m/a"), T_DOUBLE, "double", NO_OPTIONS);
    int multi = NetworkTablesJNIImpl.subscribeMultiple(inst, new String[] {"/m/", "/n/"}, NO_OPTIONS);
    T.eq(22, (multi >> 24) & 0x7f, "multi-subscriber handle type");
    int poller = NetworkTablesJNIImpl.createListenerPoller(inst);
    NetworkTablesJNIImpl.addListener(poller, multi, VALUE_ALL);
    int b = NetworkTablesJNIImpl.publish(NetworkTablesJNIImpl.getTopic(inst, "/n/b"), T_DOUBLE, "double", NO_OPTIONS);
    int c = NetworkTablesJNIImpl.publish(NetworkTablesJNIImpl.getTopic(inst, "/x/c"), T_DOUBLE, "double", NO_OPTIONS);
    NetworkTablesJNIImpl.setDouble(a, 0, 1);
    NetworkTablesJNIImpl.setDouble(b, 0, 2);
    NetworkTablesJNIImpl.setDouble(c, 0, 3);
    NetworkTableEvent[] ev = NetworkTablesJNIImpl.readListenerQueue(null, poller);
    T.eq(2, ev.length, "only matching prefixes");
    T.eq(multi, ev[0].valueData.subentry, "multi handle reported as subentry");
    NetworkTablesJNIImpl.unsubscribeMultiple(multi);
    NetworkTablesJNIImpl.setDouble(a, 0, 4);
    T.eq(0, NetworkTablesJNIImpl.readListenerQueue(null, poller).length, "unsubscribed");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testTopicInfoAndLogger() {
    int inst = newInst();
    int e = NetworkTablesJNIImpl.getEntry(inst, "/info");
    NetworkTablesJNIImpl.setDouble(e, 0, 1);
    TopicInfo[] infos = NetworkTablesJNIImpl.getTopicInfos(null, inst, "/", 0);
    T.eq(1, infos.length);
    T.eq("/info", infos[0].name);
    T.eq("double", infos[0].typeStr);
    TopicInfo one = NetworkTablesJNIImpl.getTopicInfo(null, NetworkTablesJNIImpl.getTopic(inst, "/info"));
    T.eq("/info", one.name);
    T.eq("", NetworkTablesJNIImpl.getTopicInfo(null, 0).name, "invalid topic gives empty info");

    int poller = NetworkTablesJNIImpl.createListenerPoller(inst);
    int logger = NetworkTablesJNIImpl.addLogger(poller, 0, 100);
    T.check(logger != 0, "logger");
    NetworkTablesJNIImpl.setString(e, 0, "x");
    // setString on a double entry fails silently; mismatched subscriber logs at INFO
    int pubTopic = NetworkTablesJNIImpl.getTopic(inst, "/info");
    NetworkTablesJNIImpl.subscribe(pubTopic, T_STRING, "string", NO_OPTIONS);
    NetworkTableEvent[] ev = NetworkTablesJNIImpl.readListenerQueue(null, poller);
    T.check(ev.length >= 1, "log message delivered");
    T.check(ev[0].logMessage.message.contains("type mismatch"), "message text: " + ev[0].logMessage.message);
    T.eq(0x100, flags(ev[0]), "log event flags");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testNetworkMode() {
    int inst = newInst();
    T.eq(0, NetworkTablesJNIImpl.getNetworkMode(inst));
    int poller = NetworkTablesJNIImpl.createListenerPoller(inst);
    NetworkTablesJNIImpl.addListener(poller, inst, 0x200);
    NetworkTablesJNIImpl.startServer(inst, "", "", "", 1735);
    T.eq(1, NetworkTablesJNIImpl.getNetworkMode(inst), "server mode");
    T.check(!NetworkTablesJNIImpl.getServerTimeOffset(inst).isEmpty(), "server time offset");
    NetworkTableEvent[] ev = NetworkTablesJNIImpl.readListenerQueue(null, poller);
    T.eq(1, ev.length, "time sync event on start");
    T.check(ev[0].timeSyncData.valid, "time sync valid");
    T.eq(0, NetworkTablesJNIImpl.getConnections(inst).length, "no connections");
    T.check(!NetworkTablesJNIImpl.isConnected(inst), "not connected");
    NetworkTablesJNIImpl.startClient(inst, "me");
    T.eq(1, NetworkTablesJNIImpl.getNetworkMode(inst), "already started");
    NetworkTablesJNIImpl.stopServer(inst);
    T.eq(0, NetworkTablesJNIImpl.getNetworkMode(inst));
    NetworkTablesJNIImpl.startLocal(inst);
    T.eq(0x10, NetworkTablesJNIImpl.getNetworkMode(inst));
    T.check(NetworkTablesJNIImpl.isConnected(inst), "local counts as connected");
    NetworkTablesJNIImpl.flush(inst);
    NetworkTablesJNIImpl.flushLocal(inst);
    T.check(NetworkTablesJNIImpl.now() > 0, "now");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testHandleReuse() {
    int inst = newInst();
    int topic = NetworkTablesJNIImpl.getTopic(inst, "/r");
    int first = NetworkTablesJNIImpl.subscribe(topic, T_DOUBLE, "double", NO_OPTIONS);
    NetworkTablesJNIImpl.unsubscribe(first);
    T.eq(5.0, NetworkTablesJNIImpl.getDouble(first, 5.0), "stale handle reads default");
    int second = NetworkTablesJNIImpl.subscribe(topic, T_DOUBLE, "double", NO_OPTIONS);
    T.check(first != second, "indexes are not reused before 16 are free");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }
}
