package dev.project516.frcdevwasm.shim.test;

import dev.project516.frcdevwasm.shim.networktables.NetworkTablesJNIImpl;
import dev.project516.frcdevwasm.shim.networktables.NtBridge;
import java.util.ArrayList;
import java.util.List;

/** Bridge mirroring: nt.* messages out, nt.set in. */
public final class NtBridgeTest {
  private static final int T_DOUBLE = 0x02;

  private static List<String> attach(int inst) {
    List<String> out = new ArrayList<>();
    NtBridge.bind(inst);
    NtBridge.setSink(m -> {
      synchronized (out) {
        out.add(m);
      }
    });
    return out;
  }

  private static List<String> take(List<String> out) {
    NtBridge.flush();
    synchronized (out) {
      List<String> copy = new ArrayList<>(out);
      out.clear();
      return copy;
    }
  }

  private static int id(int topicHandle) {
    return topicHandle & 0xfffff;
  }

  public static void testNothingBeforeHello() {
    int inst = NetworkTablesJNIImpl.createInstance();
    List<String> out = attach(inst);
    int e = NetworkTablesJNIImpl.getEntry(inst, "/x");
    NetworkTablesJNIImpl.setDouble(e, 0, 1);
    T.eq(0, take(out).size(), "silent until hello");
    NtBridge.setSink(null);
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testHelloResendsState() {
    int inst = NetworkTablesJNIImpl.createInstance();
    int e = NetworkTablesJNIImpl.getEntry(inst, "/SmartDashboard/x");
    NetworkTablesJNIImpl.setDouble(e, 0, 1.5);
    NetworkTablesJNIImpl.setTopicPersistent(NetworkTablesJNIImpl.getTopic(inst, "/SmartDashboard/x"), true);
    List<String> out = attach(inst);
    NtBridge.handleMessage("hello", "{}");
    List<String> m = take(out);
    int id = id(NetworkTablesJNIImpl.getTopic(inst, "/SmartDashboard/x"));
    T.eq(2, m.size(), "announce and value: " + m);
    T.eq("{\"type\":\"nt.announce\",\"device\":\"\",\"data\":{\"id\":" + id
        + ",\"name\":\"/SmartDashboard/x\",\"type\":\"double\",\"properties\":{\"persistent\":true}}}", m.get(0));
    T.check(m.get(1).startsWith("{\"type\":\"nt.value\",\"device\":\"\",\"data\":{\"id\":" + id + ",\"ts\":"), m.get(1));
    T.check(m.get(1).endsWith(",\"value\":1.5}}"), m.get(1));
    NtBridge.handleMessage("hello", "{}");
    T.eq(2, take(out).size(), "full resend on every hello");
    NtBridge.setSink(null);
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testLiveUpdates() {
    int inst = NetworkTablesJNIImpl.createInstance();
    List<String> out = attach(inst);
    NtBridge.handleMessage("hello", "{}");
    take(out);
    int topic = NetworkTablesJNIImpl.getTopic(inst, "/live");
    int pub = NetworkTablesJNIImpl.publish(topic, T_DOUBLE, "double", null);
    NetworkTablesJNIImpl.setDouble(pub, 0, 1);
    NetworkTablesJNIImpl.setDouble(pub, 0, 1);
    NetworkTablesJNIImpl.setDouble(pub, 0, 2);
    List<String> m = take(out);
    T.eq(2, m.size(), "announce plus one coalesced value: " + m);
    T.check(m.get(0).contains("\"nt.announce\"") && m.get(0).contains("\"name\":\"/live\""), m.get(0));
    T.check(m.get(1).endsWith(",\"value\":2.0}}"), "latest value only: " + m.get(1));
    NetworkTablesJNIImpl.setDouble(pub, 0, 2);
    T.eq(0, take(out).size(), "duplicates not sent");
    NetworkTablesJNIImpl.setTopicProperty(topic, "k", "\"v\"");
    m = take(out);
    T.eq(1, m.size());
    T.eq("{\"type\":\"nt.properties\",\"device\":\"\",\"data\":{\"name\":\"/live\",\"properties\":{\"k\":\"v\"}}}", m.get(0));
    NetworkTablesJNIImpl.unpublish(pub);
    m = take(out);
    T.eq(1, m.size());
    T.eq("{\"type\":\"nt.unannounce\",\"device\":\"\",\"data\":{\"id\":" + id(topic) + ",\"name\":\"/live\"}}", m.get(0));
    NtBridge.setSink(null);
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testValueTypesOnTheWire() {
    int inst = NetworkTablesJNIImpl.createInstance();
    List<String> out = attach(inst);
    NtBridge.handleMessage("hello", "{}");
    NetworkTablesJNIImpl.setString(NetworkTablesJNIImpl.getEntry(inst, "/s"), 0, "a\"b");
    NetworkTablesJNIImpl.setBoolean(NetworkTablesJNIImpl.getEntry(inst, "/b"), 0, true);
    NetworkTablesJNIImpl.setInteger(NetworkTablesJNIImpl.getEntry(inst, "/i"), 0, -5);
    NetworkTablesJNIImpl.setDoubleArray(NetworkTablesJNIImpl.getEntry(inst, "/da"), 0, new double[] {1, 2.5});
    NetworkTablesJNIImpl.setStringArray(NetworkTablesJNIImpl.getEntry(inst, "/sa"), 0, new String[] {"x", "y"});
    NetworkTablesJNIImpl.setRaw(NetworkTablesJNIImpl.getEntry(inst, "/raw"), 0, new byte[] {1, 2, 3}, 0, 3);
    NetworkTablesJNIImpl.setDouble(NetworkTablesJNIImpl.getEntry(inst, "/nan"), 0, Double.NaN);
    String all = String.join("\n", take(out));
    T.check(all.contains("\"value\":\"a\\\"b\"}"), all);
    T.check(all.contains("\"value\":true}"), all);
    T.check(all.contains("\"value\":-5}"), all);
    T.check(all.contains("\"value\":[1.0,2.5]}"), all);
    T.check(all.contains("\"value\":[\"x\",\"y\"]}"), all);
    T.check(all.contains("\"value\":\"AQID\"}"), "raw is base64: " + all);
    T.check(all.contains("\"value\":null}"), "NaN is null: " + all);
    NtBridge.setSink(null);
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testNtSetFromPage() {
    int inst = NetworkTablesJNIImpl.createInstance();
    List<String> out = attach(inst);
    NtBridge.handleMessage("hello", "{}");
    int e = NetworkTablesJNIImpl.getEntry(inst, "/SmartDashboard/gain");
    NetworkTablesJNIImpl.setDouble(e, 0, 1.0);
    take(out);

    NtBridge.handleMessage("nt.set", "{\"name\":\"/SmartDashboard/gain\",\"type\":\"double\",\"value\":2.0}");
    T.eq(2.0, NetworkTablesJNIImpl.getDouble(e, 0), "page value overwrote the local one");
    T.eq(0, take(out).size(), "page writes are not echoed");
    NetworkTablesJNIImpl.setDouble(e, 0, 3.0);
    T.eq(3.0, NetworkTablesJNIImpl.getDouble(e, 0), "local write wins afterwards");
    T.eq(1, take(out).size(), "local write mirrored");

    NtBridge.handleMessage("nt.set", "{\"name\":\"/fromPage/s\",\"type\":\"string\",\"value\":\"hi\"}");
    int s = NetworkTablesJNIImpl.getEntry(inst, "/fromPage/s");
    T.eq("hi", NetworkTablesJNIImpl.getString(s, ""), "new topic created by the page");
    T.check(NetworkTablesJNIImpl.getTopicExists(NetworkTablesJNIImpl.getTopic(inst, "/fromPage/s")), "topic exists");
    List<String> m = take(out);
    T.check(m.size() == 1 && m.get(0).contains("\"nt.announce\"") && m.get(0).contains("\"type\":\"string\""), "announce for new topic: " + m);

    NtBridge.handleMessage("nt.set", "{\"name\":\"/fromPage/arr\",\"type\":\"double[]\",\"value\":[1,2,3]}");
    T.eq(new double[] {1, 2, 3}, NetworkTablesJNIImpl.getDoubleArray(NetworkTablesJNIImpl.getEntry(inst, "/fromPage/arr"), null));
    NtBridge.handleMessage("nt.set", "{\"name\":\"/fromPage/raw\",\"type\":\"struct:Pose2d\",\"value\":\"AQID\"}");
    T.eq(new byte[] {1, 2, 3}, NetworkTablesJNIImpl.getRaw(NetworkTablesJNIImpl.getEntry(inst, "/fromPage/raw"), null));
    T.eq("struct:Pose2d", NetworkTablesJNIImpl.getTopicTypeString(NetworkTablesJNIImpl.getTopic(inst, "/fromPage/raw")));
    take(out);

    // wrong value type and malformed json are ignored
    NtBridge.handleMessage("nt.set", "{\"name\":\"/fromPage/s\",\"type\":\"double\",\"value\":\"oops\"}");
    NtBridge.handleMessage("nt.set", "not json");
    T.eq("hi", NetworkTablesJNIImpl.getString(s, ""));
    NtBridge.setSink(null);
    NetworkTablesJNIImpl.destroyInstance(inst);
  }

  public static void testPageSetReachesListeners() {
    int inst = NetworkTablesJNIImpl.createInstance();
    NtBridge.bind(inst);
    int poller = NetworkTablesJNIImpl.createListenerPoller(inst);
    NetworkTablesJNIImpl.addListener(poller, new String[] {"/"}, 0x40 | 0x80);
    NtBridge.handleMessage("nt.set", "{\"name\":\"/p\",\"type\":\"double\",\"value\":4}");
    var ev = NetworkTablesJNIImpl.readListenerQueue(null, poller);
    T.eq(1, ev.length);
    T.eq(4.0, ev[0].valueData.value.getValue());
    T.check(ev[0].valueData.value.getTime() > 0, "timestamped");
    NetworkTablesJNIImpl.destroyInstance(inst);
  }
}
