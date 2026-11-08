package dev.project516.frcdevwasm.shim.test;

import dev.project516.frcdevwasm.bridge.LocalTransport;
import dev.project516.frcdevwasm.smartdashboard.NtLink;
import edu.wpi.first.networktables.EntryListenerFlags;
import edu.wpi.first.networktables.EntryNotification;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.NetworkTableType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** SmartDashboard's NetworkTables 3 API on top of the bridge client, driven through its own classes. */
public final class SdClientTest {
  private static final int ALL = EntryListenerFlags.kNew | EntryListenerFlags.kUpdate | EntryListenerFlags.kDelete
      | EntryListenerFlags.kFlags | EntryListenerFlags.kLocal | EntryListenerFlags.kImmediate;

  private static final class Page {
    final LocalTransport transport = new LocalTransport();
    final NetworkTableInstance inst;

    Page() {
      NtLink.useTransport(transport);
      inst = NetworkTableInstance.create();
    }

    void connect() {
      inst.startClient();
      pump();
      inject("nt.connected", Map.of());
      pump();
    }

    void pump() {
      dev.project516.frcdevwasm.smartdashboard.NtClient.of(inst.getHandle()).pump();
    }

    void inject(String type, Map<String, Object> data) {
      transport.inject(type, "", data);
      pump();
    }

    void announce(int id, String name, String type) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", id);
      m.put("name", name);
      m.put("type", type);
      m.put("properties", Map.of());
      inject("nt.announce", m);
    }

    void value(int id, Object value) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", id);
      m.put("ts", 1000);
      m.put("value", value);
      inject("nt.value", m);
    }

    /** The data of the sent messages of one type. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sent(String type) {
      List<Map<String, Object>> out = new ArrayList<>();
      for (Map<String, Object> m : transport.sent()) {
        if (type.equals(m.get("type"))) {
          out.add((Map<String, Object>) m.get("data"));
        }
      }
      return out;
    }

    void close() {
      inst.close();
      NtLink.useTransport(null);
    }
  }

  public static void testEntryValuesAndTypes() {
    Page p = new Page();
    try {
      NetworkTableEntry e = p.inst.getEntry("/SmartDashboard/x");
      T.check(!e.exists(), "new entry has no value");
      T.check(e.setDouble(1.5), "set");
      T.eq(1.5, e.getDouble(0), "get");
      T.check(!e.setString("a"), "another type is refused");
      T.eq(1.5, e.getDouble(0), "value kept");
      T.check(e.setDefaultDouble(9), "default of the same type");
      T.eq(1.5, e.getDouble(0), "default does not replace");
      NetworkTableEntry s = p.inst.getEntry("/SmartDashboard/s");
      s.setStringArray(new String[] {"a", "b"});
      T.eq(new String[] {"a", "b"}, s.getStringArray(new String[0]), "string array");
      NetworkTableEntry r = p.inst.getEntry("/SmartDashboard/r");
      r.setRaw(new byte[] {1, 2, 3});
      T.eq(new byte[] {1, 2, 3}, r.getRaw(new byte[0]), "raw");
      T.eq(3, p.inst.getEntries("/SmartDashboard/", 0).length, "entries by prefix");
      T.eq(1, p.inst.getEntries("/SmartDashboard/", NetworkTableType.kDouble.getValue()).length, "entries by type");
    } finally {
      p.close();
    }
  }

  public static void testLocalListenerEvents() {
    Page p = new Page();
    try {
      List<EntryNotification> events = new CopyOnWriteArrayList<>();
      p.inst.addEntryListener("/SmartDashboard/", events::add, ALL);
      NetworkTableEntry e = p.inst.getEntry("/SmartDashboard/x");
      e.setDouble(1);
      e.setDouble(1);
      e.setDouble(2);
      e.setFlags(1);
      e.delete();
      T.await(() -> events.size() >= 4, "four events");
      T.eq(4, events.size(), "no event for a repeated value");
      int local = EntryListenerFlags.kLocal;
      T.eq(EntryListenerFlags.kNew | local, events.get(0).flags, "new");
      T.eq(EntryListenerFlags.kUpdate | local, events.get(1).flags, "update");
      T.eq(2.0, events.get(1).value.getDouble(), "updated value");
      T.eq(EntryListenerFlags.kFlags | local, events.get(2).flags, "flags");
      T.eq(EntryListenerFlags.kDelete | local, events.get(3).flags, "delete");
      T.eq("/SmartDashboard/x", events.get(0).name, "name");
    } finally {
      p.close();
    }
  }

  public static void testListenerWithoutLocalFlagIgnoresLocalSets() {
    Page p = new Page();
    try {
      List<EntryNotification> events = new CopyOnWriteArrayList<>();
      p.inst.addEntryListener("/", events::add, EntryListenerFlags.kNew | EntryListenerFlags.kUpdate);
      p.inst.getEntry("/a").setDouble(1);
      p.connect();
      p.announce(1, "/b", "double");
      p.value(1, 5.0);
      T.await(() -> events.size() == 1, "the remote value only");
      T.eq("/b", events.get(0).name, "name");
      T.eq(EntryListenerFlags.kNew, events.get(0).flags, "remote new has no local flag");
    } finally {
      p.close();
    }
  }

  public static void testImmediateNotifiesExistingEntries() {
    Page p = new Page();
    try {
      p.inst.getEntry("/a").setDouble(1);
      List<EntryNotification> events = new CopyOnWriteArrayList<>();
      p.inst.addEntryListener("/", events::add, EntryListenerFlags.kNew | EntryListenerFlags.kImmediate);
      T.await(() -> events.size() == 1, "immediate event");
      T.eq(EntryListenerFlags.kNew | EntryListenerFlags.kImmediate, events.get(0).flags, "flags");
      List<EntryNotification> quiet = new CopyOnWriteArrayList<>();
      p.inst.addEntryListener("/", quiet::add, EntryListenerFlags.kUpdate | EntryListenerFlags.kImmediate);
      Thread.sleep(100);
      T.eq(0, quiet.size(), "immediate needs new");
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    } finally {
      p.close();
    }
  }

  public static void testClientIntroducesItself() {
    Page p = new Page();
    try {
      p.inst.setNetworkIdentity("SmartDashboard");
      p.inst.startClient();
      p.pump();
      T.eq(List.of(Map.of("identity", "SmartDashboard")), p.sent("nt.hello"), "hello");
      T.check(!p.inst.isConnected(), "not connected until the page says so");
      p.inject("nt.connected", Map.of());
      T.check(p.inst.isConnected(), "connected");
      T.eq(1, p.inst.getConnections().length, "one connection");
      p.inst.stopClient();
      p.pump();
      T.eq(1, p.sent("nt.bye").size(), "bye");
      T.check(!p.inst.isConnected(), "disconnected");
    } finally {
      p.close();
    }
  }

  public static void testValuesSetBeforeConnectingArePublishedOnConnect() {
    Page p = new Page();
    try {
      NetworkTableEntry e = p.inst.getEntry("/SmartDashboard/x");
      e.setDouble(3);
      e.setFlags(1);
      p.inst.startClient();
      p.pump();
      T.eq(0, p.sent("nt.announce").size(), "nothing before the page answers");
      p.inject("nt.connected", Map.of());
      p.pump();
      long id = e.getHandle() & 0xfffff;
      T.eq(List.of(Map.of("id", id, "name", "/SmartDashboard/x", "type", "double", "properties", Map.of("persistent", true))),
          p.sent("nt.announce"), "announce");
      T.eq(List.of(Map.of("id", id, "ts", 0L, "value", 3.0)), p.sent("nt.value"), "value");
    } finally {
      p.close();
    }
  }

  public static void testLocalSetsReachThePage() {
    Page p = new Page();
    try {
      p.connect();
      NetworkTableEntry e = p.inst.getEntry("/SmartDashboard/flag");
      e.setBoolean(true);
      e.setBoolean(true);
      e.setBoolean(false);
      p.pump();
      T.eq(1, p.sent("nt.announce").size(), "announced once");
      T.eq(2, p.sent("nt.value").size(), "unchanged values are not sent");
      T.eq(false, p.sent("nt.value").get(1).get("value"), "latest value");
      e.setFlags(1);
      p.pump();
      T.eq(1, p.sent("nt.properties").size(), "flags");
      T.eq(Map.of("name", "/SmartDashboard/flag", "properties", Map.of("persistent", true)), p.sent("nt.properties").get(0), "flags");
      e.delete();
      p.pump();
      T.eq(1, p.sent("nt.unannounce").size(), "unpublished");
    } finally {
      p.close();
    }
  }

  public static void testRemoteTopicsAppearWithTheirFirstValue() {
    Page p = new Page();
    try {
      p.connect();
      List<EntryNotification> events = new CopyOnWriteArrayList<>();
      p.inst.addEntryListener("/SmartDashboard/", events::add, ALL);
      p.announce(7, "/SmartDashboard/speed", "double");
      NetworkTableEntry e = p.inst.getEntry("/SmartDashboard/speed");
      T.check(!e.exists(), "announced without a value is not visible");
      p.value(7, 2.5);
      T.eq(2.5, e.getDouble(0), "value");
      p.value(7, 3.5);
      p.value(7, 3.5);
      T.await(() -> events.size() == 2, "new and update");
      T.eq(EntryListenerFlags.kNew, events.get(0).flags, "new");
      T.eq(EntryListenerFlags.kUpdate, events.get(1).flags, "update");
      T.eq(0, p.sent("nt.announce").size() + p.sent("nt.value").size(), "remote values are not echoed");
      p.inject("nt.unannounce", Map.of("id", 7, "name", "/SmartDashboard/speed"));
      T.check(!e.exists(), "removed");
      T.await(() -> events.size() == 3, "delete");
      T.eq(EntryListenerFlags.kDelete, events.get(2).flags, "delete");
    } finally {
      p.close();
    }
  }

  public static void testRemoteValuesOfEveryType() {
    Page p = new Page();
    try {
      p.connect();
      p.announce(1, "/b", "boolean");
      p.value(1, true);
      p.announce(2, "/n", "int");
      p.value(2, 4);
      p.announce(3, "/f", "float");
      p.value(3, 1.5);
      p.announce(4, "/s", "string");
      p.value(4, "hi");
      p.announce(5, "/j", "json");
      p.value(5, "{}");
      p.announce(6, "/da", "double[]");
      p.value(6, List.of(1, 2.5));
      p.announce(7, "/ia", "int[]");
      p.value(7, List.of(3, 4));
      p.announce(8, "/ba", "boolean[]");
      p.value(8, List.of(true, false));
      p.announce(9, "/sa", "string[]");
      p.value(9, List.of("x", "y"));
      p.announce(10, "/raw", "raw");
      p.value(10, "AQID");
      p.announce(11, "/pose", "struct:Pose2d");
      p.value(11, "AQ==");
      T.eq(true, p.inst.getEntry("/b").getBoolean(false), "boolean");
      T.eq(4.0, p.inst.getEntry("/n").getDouble(0), "int reads as double");
      T.eq(1.5, p.inst.getEntry("/f").getDouble(0), "float reads as double");
      T.eq("hi", p.inst.getEntry("/s").getString(""), "string");
      T.eq("{}", p.inst.getEntry("/j").getString(""), "json reads as string");
      T.eq(new double[] {1, 2.5}, p.inst.getEntry("/da").getDoubleArray(new double[0]), "double array");
      T.eq(new double[] {3, 4}, p.inst.getEntry("/ia").getDoubleArray(new double[0]), "int array reads as double array");
      T.eq(new boolean[] {true, false}, p.inst.getEntry("/ba").getBooleanArray(new boolean[0]), "boolean array");
      T.eq(new String[] {"x", "y"}, p.inst.getEntry("/sa").getStringArray(new String[0]), "string array");
      T.eq(new byte[] {1, 2, 3}, p.inst.getEntry("/raw").getRaw(new byte[0]), "raw");
      T.eq(new byte[] {1}, p.inst.getEntry("/pose").getRaw(new byte[0]), "struct reads as raw");
    } finally {
      p.close();
    }
  }

  public static void testEditingARemoteValueKeepsItsServerType() {
    Page p = new Page();
    try {
      p.connect();
      p.announce(2, "/count", "int");
      p.value(2, 4);
      NetworkTableEntry e = p.inst.getEntry("/count");
      T.check(!e.setString("x"), "another type is refused");
      T.check(e.setDouble(5), "set");
      p.pump();
      T.eq(1, p.sent("nt.announce").size(), "published to the server");
      T.eq("int", p.sent("nt.announce").get(0).get("type"), "the server's type");
      T.eq(5.0, p.sent("nt.value").get(0).get("value"), "value");
    } finally {
      p.close();
    }
  }

  public static void testReconnectPublishesAgain() {
    Page p = new Page();
    try {
      p.connect();
      p.inst.getEntry("/x").setDouble(1);
      p.pump();
      T.eq(1, p.sent("nt.announce").size(), "announced");
      p.inst.stopClient();
      p.pump();
      p.transport.clearSent();
      p.connect();
      T.eq(1, p.sent("nt.announce").size(), "announced again");
      T.eq(1, p.sent("nt.value").size(), "value again");
    } finally {
      p.close();
    }
  }

  public static void testConnectionListener() {
    Page p = new Page();
    try {
      List<Boolean> events = new CopyOnWriteArrayList<>();
      p.inst.addConnectionListener(n -> events.add(n.connected), true);
      p.connect();
      T.await(() -> events.size() == 1, "connected");
      p.inst.stopClient();
      T.await(() -> events.size() == 2, "disconnected");
      T.eq(List.of(true, false), events, "events");
    } finally {
      p.close();
    }
  }

  public static void testTableApiAsSmartDashboardUsesIt() {
    Page p = new Page();
    try {
      p.connect();
      edu.wpi.first.networktables.NetworkTable table = p.inst.getTable("SmartDashboard");
      p.announce(1, "/SmartDashboard/Auto", "string");
      p.value(1, "left");
      T.eq("left", table.getEntry("Auto").getString(""), "read through the table");
      T.check(table.getEntry("Auto").exists(), "exists");
      table.getEntry("Gain").setDouble(0.5);
      p.pump();
      T.eq("/SmartDashboard/Gain", p.sent("nt.announce").get(0).get("name"), "published under the table's path");
    } finally {
      p.close();
    }
  }
}
