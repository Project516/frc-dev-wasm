package dev.project516.frcdevwasm.shim.test;

import dev.project516.frcdevwasm.sim.Sync;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.networktables.BooleanArrayPublisher;
import edu.wpi.first.networktables.DoublePublisher;
import edu.wpi.first.networktables.DoubleSubscriber;
import edu.wpi.first.networktables.DoubleTopic;
import edu.wpi.first.networktables.IntegerEntry;
import edu.wpi.first.networktables.MultiSubscriber;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableEvent;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.NetworkTableType;
import edu.wpi.first.networktables.PubSubOption;
import edu.wpi.first.networktables.StringArrayEntry;
import edu.wpi.first.networktables.StructArrayPublisher;
import edu.wpi.first.networktables.StructArraySubscriber;
import edu.wpi.first.networktables.StructPublisher;
import edu.wpi.first.networktables.StructSubscriber;
import edu.wpi.first.networktables.Topic;
import edu.wpi.first.util.WPIUtilJNI;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Drives the unmodified WPILib NetworkTables Java API on top of the shim. */
public final class NtWpilibTest {
  static {
    WPIUtilJNI.now(); // installs the signal provider before any poller exists
  }

  private static final EnumSet<NetworkTableEvent.Kind> VALUES = EnumSet.of(NetworkTableEvent.Kind.kValueAll);

  public static void testPublishSubscribe() {
    NetworkTableInstance inst = NetworkTableInstance.create();
    DoubleTopic topic = inst.getDoubleTopic("/t/x");
    try (DoublePublisher pub = topic.publish();
        DoubleSubscriber sub = topic.subscribe(-1.0)) {
      T.eq(-1.0, sub.get(), "default");
      pub.set(2.5);
      T.eq(2.5, sub.get());
      T.eq(2.5, sub.getAtomic().value);
      T.check(topic.exists(), "exists");
      T.eq("double", topic.getTypeString());
    }
    inst.close();
  }

  public static void testEntryAndTable() {
    NetworkTableInstance inst = NetworkTableInstance.create();
    NetworkTable table = inst.getTable("SmartDashboard");
    NetworkTableEntry e = table.getEntry("speed");
    T.check(e.setDouble(3.0), "setDouble");
    T.eq(3.0, e.getDouble(0));
    T.eq("/SmartDashboard/speed", e.getName());
    T.check(table.getEntry("name").setString("auto"), "setString");
    T.eq("auto", table.getEntry("name").getString(""));
    T.check(table.getEntry("flag").setBoolean(true), "setBoolean");
    T.eq(true, table.getEntry("flag").getBoolean(false));
    T.eq(NetworkTableType.kString, table.getEntry("name").getType());
    T.eq(3.0, e.getValue().getValue(), "getValue");
    T.check(table.getKeys().contains("speed"), "keys: " + table.getKeys());
    T.check(table.containsKey("flag"), "containsKey");
    e.setPersistent();
    T.check(e.isPersistent(), "persistent");
    T.eq(NetworkTableType.kString, table.getEntry("name").getValue().getType());
    inst.close();
  }

  public static void testIntegerAndArrays() {
    NetworkTableInstance inst = NetworkTableInstance.create();
    IntegerEntry ie = inst.getIntegerTopic("/i").getEntry(0);
    ie.set(7);
    T.eq(7L, ie.get());
    StringArrayEntry se = inst.getStringArrayTopic("/sa").getEntry(new String[0]);
    se.set(new String[] {"a", "b"});
    T.eq(new String[] {"a", "b"}, se.get());
    BooleanArrayPublisher bp = inst.getBooleanArrayTopic("/ba").publish();
    bp.set(new boolean[] {true, false, true});
    T.eq(new boolean[] {true, false, true}, inst.getBooleanArrayTopic("/ba").subscribe(new boolean[0]).get());
    inst.close();
  }

  public static void testStructPublishing() {
    NetworkTableInstance inst = NetworkTableInstance.create();
    StructPublisher<Pose2d> pub = inst.getStructTopic("/pose", Pose2d.struct).publish();
    StructSubscriber<Pose2d> sub = inst.getStructTopic("/pose", Pose2d.struct).subscribe(new Pose2d());
    Pose2d pose = new Pose2d(1.5, -2.0, Rotation2d.fromDegrees(30));
    pub.set(pose);
    Pose2d read = sub.get();
    T.near(1.5, read.getX(), 1e-12, "x");
    T.near(-2.0, read.getY(), 1e-12, "y");
    T.near(30, read.getRotation().getDegrees(), 1e-9, "rotation");
    T.eq("struct:Pose2d", inst.getTopic("/pose").getTypeString());
    T.check(inst.hasSchema("struct:Pose2d"), "schema registered");
    T.check(inst.hasSchema("struct:Translation2d"), "nested schema registered");
    Topic schema = inst.getTopic("/.schema/struct:Pose2d");
    T.check(schema.exists(), "schema topic exists");
    T.eq("structschema", schema.getTypeString());
    T.check(schema.getProperty("retained").equals("true"), "schema retained: " + schema.getProperties());
    byte[] schemaBytes = inst.getRawTopic("/.schema/struct:Pose2d").subscribe("structschema", new byte[0]).get();
    T.check(new String(schemaBytes).contains("Translation2d translation"), "schema text: " + new String(schemaBytes));

    StructArrayPublisher<Pose2d> arrPub = inst.getStructArrayTopic("/poses", Pose2d.struct).publish();
    StructArraySubscriber<Pose2d> arrSub = inst.getStructArrayTopic("/poses", Pose2d.struct).subscribe(new Pose2d[0]);
    arrPub.set(new Pose2d[] {pose, new Pose2d()});
    T.eq(2, arrSub.get().length, "struct array length");
    T.eq("struct:Pose2d[]", inst.getTopic("/poses").getTypeString());
    inst.close();
  }

  public static void testValueListenerThread() throws Exception {
    NetworkTableInstance inst = NetworkTableInstance.create();
    DoubleTopic topic = inst.getDoubleTopic("/listen/x");
    List<Double> seen = new CopyOnWriteArrayList<>();
    int h = inst.addListener(topic, VALUES, e -> seen.add(e.valueData.value.getDouble()));
    DoublePublisher pub = topic.publish();
    pub.set(1);
    pub.set(2);
    pub.set(3);
    T.await(() -> seen.size() == 3, "three value events, saw " + seen);
    T.eq(List.of(1.0, 2.0, 3.0), seen);
    T.check(inst.waitForListenerQueue(2.0), "queue drained");
    inst.removeListener(h);
    pub.set(4);
    inst.waitForListenerQueue(0.2);
    T.eq(3, seen.size(), "removed listener silent");
    inst.close();
  }

  public static void testPrefixListenerAndImmediate() throws Exception {
    NetworkTableInstance inst = NetworkTableInstance.create();
    DoublePublisher early = inst.getDoubleTopic("/pre/early").publish();
    early.set(10);
    List<String> topics = new CopyOnWriteArrayList<>();
    List<String> values = new CopyOnWriteArrayList<>();
    inst.addListener(
        new String[] {"/pre/"},
        EnumSet.of(NetworkTableEvent.Kind.kPublish, NetworkTableEvent.Kind.kImmediate),
        e -> topics.add(e.topicInfo.name));
    inst.addListener(
        new String[] {"/pre/"},
        EnumSet.of(NetworkTableEvent.Kind.kValueAll, NetworkTableEvent.Kind.kImmediate),
        e -> values.add(e.valueData.getTopic().getName() + "=" + e.valueData.value.getDouble()));
    T.await(() -> topics.size() == 1 && values.size() == 1, "immediate events: " + topics + values);
    T.eq("/pre/early", topics.get(0));
    T.eq("/pre/early=10.0", values.get(0));
    inst.getDoubleTopic("/pre/late").publish().set(5);
    inst.getDoubleTopic("/elsewhere").publish().set(5);
    T.await(() -> topics.size() == 2 && values.size() == 2, "live events: " + topics + values);
    T.eq("/pre/late", topics.get(1));
    inst.close();
  }

  public static void testWaitEndsWhenHandleIsDestroyedAndReused() throws Exception {
    int handle = (Sync.HANDLE_TYPE_USER_BASE << 24) | 1;
    Sync.createSignalObject(handle, false, false);
    int[][] signaled = new int[1][];
    Thread waiter = new Thread(() -> signaled[0] = Sync.waitForObjects(new int[] {handle}, -1).signaled());
    waiter.setDaemon(true);
    waiter.start();
    T.await(() -> waiter.getState() == Thread.State.WAITING, "waiter to block");
    Sync.destroySignalObject(handle);
    Sync.createSignalObject(handle, false, false);
    waiter.join(5000);
    T.check(!waiter.isAlive(), "waiter on a destroyed handle never woke");
    T.eq(new int[] {handle | 0x80000000}, signaled[0]);
    Sync.destroySignalObject(handle);
  }

  public static void testMultiSubscriber() throws Exception {
    NetworkTableInstance inst = NetworkTableInstance.create();
    MultiSubscriber multi = new MultiSubscriber(inst, new String[] {"/ms/"}, PubSubOption.keepDuplicates(true));
    List<String> names = new CopyOnWriteArrayList<>();
    inst.addListener(multi, VALUES, e -> names.add(e.valueData.getTopic().getName()));
    inst.getDoubleTopic("/ms/a").publish().set(1);
    inst.getDoubleTopic("/ms/b").publish().set(2);
    inst.getDoubleTopic("/other").publish().set(3);
    T.await(() -> names.size() == 2, "multi events: " + names);
    T.check(names.contains("/ms/a") && names.contains("/ms/b"), "names " + names);
    multi.close();
    inst.close();
  }

  public static void testTopicEnumeration() {
    NetworkTableInstance inst = NetworkTableInstance.create();
    inst.getDoubleTopic("/e/a").publish().set(1);
    inst.getStringTopic("/e/b").publish().set("s");
    T.eq(2, inst.getTopics("/e/").length, "topics");
    T.eq(1, inst.getTopics("/e/", NetworkTableType.kString.getValue()).length, "type filter");
    T.eq(1, inst.getTopicInfo("/e/", 0x04).length, "topic info with type mask");
    inst.close();
  }

  public static void testNetworkModeAndTime() {
    NetworkTableInstance inst = NetworkTableInstance.create();
    inst.startServer();
    T.check(inst.getNetworkMode().contains(NetworkTableInstance.NetworkMode.kServer), "server mode");
    T.check(inst.getConnections().length == 0, "no connections");
    T.check(NetworkTableInstance.getDefault().getHandle() != inst.getHandle(), "distinct instances");
    T.check(NetworkTablesTime.positive(), "now");
    inst.stopServer();
    inst.close();
  }

  private static final class NetworkTablesTime {
    static boolean positive() {
      return edu.wpi.first.networktables.NetworkTablesJNI.now() > 0;
    }
  }
}
