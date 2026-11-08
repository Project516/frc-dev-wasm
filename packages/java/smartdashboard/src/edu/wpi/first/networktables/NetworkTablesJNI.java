package edu.wpi.first.networktables;

import dev.project516.frcdevwasm.smartdashboard.NtClient;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the JNI class of SmartDashboard's bundled ntcore, which is the NetworkTables 3 API.
 * The native library is replaced by {@link NtClient}.
 */
public final class NetworkTablesJNI {
  /** Library loading is not needed. SmartDashboard only calls it to turn extraction off. */
  public static class Helper {
    private static boolean extractOnStaticLoad = true;

    public static boolean getExtractOnStaticLoad() {
      return extractOnStaticLoad;
    }

    public static void setExtractOnStaticLoad(boolean load) {
      extractOnStaticLoad = load;
    }
  }

  private static final double NO_TIMEOUT = -1;
  private static final int PROTOCOL_VERSION = 0x0400;

  private NetworkTablesJNI() {}

  public static synchronized void forceLoad() {}

  private static NtClient inst(int handle) {
    NtClient c = NtClient.of(handle);
    if (c == null) {
      throw new IllegalStateException("invalid NetworkTables handle " + handle);
    }
    return c;
  }

  private static long millis(double seconds) {
    return seconds < 0 ? -1 : (long) (seconds * 1000);
  }

  // instances

  public static int getDefaultInstance() {
    return NtClient.getDefault().handle();
  }

  public static int createInstance() {
    return NtClient.create().handle();
  }

  public static void destroyInstance(int inst) {
    NtClient.destroy(inst);
  }

  public static int getInstanceFromHandle(int handle) {
    NtClient c = NtClient.of(handle);
    return c == null ? 0 : c.handle();
  }

  // entries

  public static int getEntry(int inst, String key) {
    return inst(inst).getEntry(key);
  }

  public static int[] getEntries(int inst, String prefix, int types) {
    return inst(inst).getEntries(prefix, types).stream().mapToInt(e -> e.handle).toArray();
  }

  public static String getEntryName(int entry) {
    NtClient.Entry e = entryOrNull(entry);
    return e == null ? "" : e.name;
  }

  public static long getEntryLastChange(int entry) {
    NtClient.Entry e = entryOrNull(entry);
    return e == null ? 0 : e.time();
  }

  public static int getType(int entry) {
    NtClient.Entry e = entryOrNull(entry);
    return e == null ? 0 : e.type();
  }

  private static NtClient.Entry entryOrNull(int handle) {
    NtClient c = NtClient.of(handle);
    return c == null ? null : c.entry(handle);
  }

  private static boolean set(int entry, int type, Object value, boolean force) {
    NtClient c = NtClient.of(entry);
    return c != null && c.set(entry, type, value, force);
  }

  private static boolean setDefault(int entry, int type, Object value) {
    NtClient c = NtClient.of(entry);
    return c != null && c.setDefault(entry, type, value);
  }

  public static boolean setBoolean(int entry, long time, boolean value, boolean force) {
    return set(entry, NtClient.BOOLEAN, value, force);
  }

  public static boolean setDouble(int entry, long time, double value, boolean force) {
    return set(entry, NtClient.DOUBLE, value, force);
  }

  public static boolean setString(int entry, long time, String value, boolean force) {
    return set(entry, NtClient.STRING, value, force);
  }

  public static boolean setRaw(int entry, long time, byte[] value, boolean force) {
    return set(entry, NtClient.RAW, value.clone(), force);
  }

  public static boolean setRaw(int entry, long time, ByteBuffer value, int len, boolean force) {
    ByteBuffer source = value.duplicate();
    byte[] bytes = new byte[Math.min(len, source.remaining())];
    source.get(bytes);
    return set(entry, NtClient.RAW, bytes, force);
  }

  public static boolean setBooleanArray(int entry, long time, boolean[] value, boolean force) {
    return set(entry, NtClient.BOOLEAN_ARRAY, value.clone(), force);
  }

  public static boolean setDoubleArray(int entry, long time, double[] value, boolean force) {
    return set(entry, NtClient.DOUBLE_ARRAY, value.clone(), force);
  }

  public static boolean setStringArray(int entry, long time, String[] value, boolean force) {
    return set(entry, NtClient.STRING_ARRAY, value.clone(), force);
  }

  public static NetworkTableValue getValue(int entry) {
    NtClient.Entry e = entryOrNull(entry);
    return e == null || !e.exists() ? new NetworkTableValue(0, null, 0) : new NetworkTableValue(e.type(), copy(e.value()), e.time());
  }

  private static Object copy(Object value) {
    if (value instanceof byte[] a) {
      return a.clone();
    } else if (value instanceof boolean[] a) {
      return a.clone();
    } else if (value instanceof double[] a) {
      return a.clone();
    } else if (value instanceof String[] a) {
      return a.clone();
    }
    return value;
  }

  private static Object valueOfType(int entry, int type) {
    NtClient.Entry e = entryOrNull(entry);
    return e != null && e.exists() && e.type() == type ? copy(e.value()) : null;
  }

  public static boolean getBoolean(int entry, boolean defaultValue) {
    Object v = valueOfType(entry, NtClient.BOOLEAN);
    return v == null ? defaultValue : (Boolean) v;
  }

  public static double getDouble(int entry, double defaultValue) {
    Object v = valueOfType(entry, NtClient.DOUBLE);
    return v == null ? defaultValue : (Double) v;
  }

  public static String getString(int entry, String defaultValue) {
    Object v = valueOfType(entry, NtClient.STRING);
    return v == null ? defaultValue : (String) v;
  }

  public static byte[] getRaw(int entry, byte[] defaultValue) {
    Object v = valueOfType(entry, NtClient.RAW);
    return v == null ? defaultValue : (byte[]) v;
  }

  public static boolean[] getBooleanArray(int entry, boolean[] defaultValue) {
    Object v = valueOfType(entry, NtClient.BOOLEAN_ARRAY);
    return v == null ? defaultValue : (boolean[]) v;
  }

  public static double[] getDoubleArray(int entry, double[] defaultValue) {
    Object v = valueOfType(entry, NtClient.DOUBLE_ARRAY);
    return v == null ? defaultValue : (double[]) v;
  }

  public static String[] getStringArray(int entry, String[] defaultValue) {
    Object v = valueOfType(entry, NtClient.STRING_ARRAY);
    return v == null ? defaultValue : (String[]) v;
  }

  public static boolean setDefaultBoolean(int entry, long time, boolean value) {
    return setDefault(entry, NtClient.BOOLEAN, value);
  }

  public static boolean setDefaultDouble(int entry, long time, double value) {
    return setDefault(entry, NtClient.DOUBLE, value);
  }

  public static boolean setDefaultString(int entry, long time, String value) {
    return setDefault(entry, NtClient.STRING, value);
  }

  public static boolean setDefaultRaw(int entry, long time, byte[] value) {
    return setDefault(entry, NtClient.RAW, value.clone());
  }

  public static boolean setDefaultBooleanArray(int entry, long time, boolean[] value) {
    return setDefault(entry, NtClient.BOOLEAN_ARRAY, value.clone());
  }

  public static boolean setDefaultDoubleArray(int entry, long time, double[] value) {
    return setDefault(entry, NtClient.DOUBLE_ARRAY, value.clone());
  }

  public static boolean setDefaultStringArray(int entry, long time, String[] value) {
    return setDefault(entry, NtClient.STRING_ARRAY, value.clone());
  }

  public static void setEntryFlags(int entry, int flags) {
    NtClient c = NtClient.of(entry);
    if (c != null) {
      c.setFlags(entry, flags);
    }
  }

  public static int getEntryFlags(int entry) {
    NtClient.Entry e = entryOrNull(entry);
    return e == null ? 0 : e.flags();
  }

  public static void deleteEntry(int entry) {
    NtClient c = NtClient.of(entry);
    if (c != null) {
      c.delete(entry);
    }
  }

  public static void deleteAllEntries(int inst) {
    inst(inst).deleteAll();
  }

  private static EntryInfo info(NetworkTableInstance instance, NtClient.Entry e) {
    return new EntryInfo(instance, e.handle, e.name, e.type(), e.flags(), e.time());
  }

  public static EntryInfo getEntryInfoHandle(NetworkTableInstance instance, int entry) {
    NtClient.Entry e = entryOrNull(entry);
    return e == null ? null : info(instance, e);
  }

  public static EntryInfo[] getEntryInfo(NetworkTableInstance instance, int inst, String prefix, int types) {
    List<EntryInfo> out = new ArrayList<>();
    for (NtClient.Entry e : inst(inst).getEntries(prefix, types)) {
      out.add(info(instance, e));
    }
    return out.toArray(new EntryInfo[0]);
  }

  // entry listeners

  public static int createEntryListenerPoller(int inst) {
    return inst(inst).createEntryPoller();
  }

  public static void destroyEntryListenerPoller(int poller) {
    inst(poller).destroyEntryPoller(poller);
  }

  public static int addPolledEntryListener(int poller, String prefix, int flags) {
    return inst(poller).addEntryListener(poller, prefix, flags);
  }

  public static int addPolledEntryListener(int poller, int entry, int flags) {
    return inst(poller).addEntryListener(poller, entry, flags);
  }

  private static EntryNotification[] notifications(NetworkTableInstance instance, NtClient.Event[] events) {
    EntryNotification[] out = new EntryNotification[events.length];
    for (int i = 0; i < out.length; i++) {
      NtClient.Event e = events[i];
      out[i] = new EntryNotification(instance, e.listener, e.entry, e.name, new NetworkTableValue(e.type, copy(e.value), e.time), e.flags);
    }
    return out;
  }

  public static EntryNotification[] pollEntryListener(NetworkTableInstance instance, int poller) throws InterruptedException {
    return pollEntryListenerTimeout(instance, poller, NO_TIMEOUT);
  }

  public static EntryNotification[] pollEntryListenerTimeout(NetworkTableInstance instance, int poller, double timeout)
      throws InterruptedException {
    NtClient c = NtClient.of(poller);
    return c == null ? new EntryNotification[0] : notifications(instance, c.pollEntries(poller, millis(timeout)));
  }

  public static void cancelPollEntryListener(int poller) {
    inst(poller).cancelEntryPoll(poller);
  }

  public static void removeEntryListener(int listener) {
    inst(listener).removeEntryListener(listener);
  }

  public static boolean waitForEntryListenerQueue(int inst, double timeout) {
    return true;
  }

  // connection listeners

  public static int createConnectionListenerPoller(int inst) {
    return inst(inst).createConnectionPoller();
  }

  public static void destroyConnectionListenerPoller(int poller) {
    inst(poller).destroyConnectionPoller(poller);
  }

  public static int addPolledConnectionListener(int poller, boolean immediateNotify) {
    return inst(poller).addConnectionListener(poller, immediateNotify);
  }

  public static ConnectionNotification[] pollConnectionListener(NetworkTableInstance instance, int poller)
      throws InterruptedException {
    return pollConnectionListenerTimeout(instance, poller, NO_TIMEOUT);
  }

  public static ConnectionNotification[] pollConnectionListenerTimeout(NetworkTableInstance instance, int poller, double timeout)
      throws InterruptedException {
    NtClient c = NtClient.of(poller);
    NtClient.ConnectionEvent[] events = c == null ? new NtClient.ConnectionEvent[0] : c.pollConnections(poller, millis(timeout));
    ConnectionNotification[] out = new ConnectionNotification[events.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = new ConnectionNotification(instance, events[i].listener, events[i].connected, connection());
    }
    return out;
  }

  public static void cancelPollConnectionListener(int poller) {
    inst(poller).cancelConnectionPoll(poller);
  }

  public static void removeConnectionListener(int listener) {
    inst(listener).removeConnectionListener(listener);
  }

  public static boolean waitForConnectionListenerQueue(int inst, double timeout) {
    return true;
  }

  private static ConnectionInfo connection() {
    return new ConnectionInfo("frc-dev-wasm", "localhost", 0, NtClient.now(), PROTOCOL_VERSION);
  }

  // RPC and logging have no events here, so their pollers only wait to be cancelled

  public static int createRpcCallPoller(int inst) {
    return inst(inst).createIdlePoller();
  }

  public static void destroyRpcCallPoller(int poller) {
    inst(poller).destroyIdlePoller(poller);
  }

  public static void createPolledRpc(int entry, byte[] def, int poller) {}

  public static RpcAnswer[] pollRpc(NetworkTableInstance instance, int poller) throws InterruptedException {
    inst(poller).pollIdle(poller, -1);
    return new RpcAnswer[0];
  }

  public static RpcAnswer[] pollRpcTimeout(NetworkTableInstance instance, int poller, double timeout) throws InterruptedException {
    inst(poller).pollIdle(poller, millis(timeout));
    return new RpcAnswer[0];
  }

  public static void cancelPollRpc(int poller) {
    inst(poller).cancelIdlePoll(poller);
  }

  public static boolean waitForRpcCallQueue(int inst, double timeout) {
    return true;
  }

  public static boolean postRpcResponse(int entry, int call, byte[] result) {
    return false;
  }

  public static int callRpc(int entry, byte[] params) {
    return 0;
  }

  public static byte[] getRpcResult(int entry, int call) {
    return null;
  }

  public static byte[] getRpcResult(int entry, int call, double timeout) {
    return null;
  }

  public static void cancelRpcResult(int entry, int call) {}

  public static byte[] getRpc(int entry, byte[] defaultValue) {
    return defaultValue;
  }

  public static int createLoggerPoller(int inst) {
    return inst(inst).createIdlePoller();
  }

  public static void destroyLoggerPoller(int poller) {
    inst(poller).destroyIdlePoller(poller);
  }

  public static int addPolledLogger(int poller, int minLevel, int maxLevel) {
    return poller;
  }

  public static LogMessage[] pollLogger(NetworkTableInstance instance, int poller) throws InterruptedException {
    inst(poller).pollIdle(poller, -1);
    return new LogMessage[0];
  }

  public static LogMessage[] pollLoggerTimeout(NetworkTableInstance instance, int poller, double timeout)
      throws InterruptedException {
    inst(poller).pollIdle(poller, millis(timeout));
    return new LogMessage[0];
  }

  public static void cancelPollLogger(int poller) {
    inst(poller).cancelIdlePoll(poller);
  }

  public static void removeLogger(int logger) {}

  public static boolean waitForLoggerQueue(int inst, double timeout) {
    return true;
  }

  // network

  public static void setNetworkIdentity(int inst, String name) {
    inst(inst).setIdentity(name);
  }

  public static int getNetworkMode(int inst) {
    return inst(inst).mode();
  }

  public static void startLocal(int inst) {
    inst(inst).startLocal();
  }

  public static void stopLocal(int inst) {
    inst(inst).stopLocal();
  }

  public static void startServer(int inst, String persistFilename, String listenAddress, int port) {
    inst(inst).startServer();
  }

  public static void stopServer(int inst) {
    inst(inst).stopServer();
  }

  public static void startClient(int inst) {
    inst(inst).startClient();
  }

  public static void startClient(int inst, String serverName, int port) {
    inst(inst).startClient();
  }

  public static void startClient(int inst, String[] serverNames, int[] ports) {
    inst(inst).startClient();
  }

  public static void startClientTeam(int inst, int team, int port) {
    inst(inst).startClient();
  }

  public static void stopClient(int inst) {
    inst(inst).stopClient();
  }

  public static void setServer(int inst, String serverName, int port) {}

  public static void setServer(int inst, String[] serverNames, int[] ports) {}

  public static void setServerTeam(int inst, int team, int port) {}

  public static void startDSClient(int inst, int port) {}

  public static void stopDSClient(int inst) {}

  public static void setUpdateRate(int inst, double interval) {}

  public static void flush(int inst) {}

  public static ConnectionInfo[] getConnections(int inst) {
    return inst(inst).isConnected() ? new ConnectionInfo[] {connection()} : new ConnectionInfo[0];
  }

  public static boolean isConnected(int inst) {
    return inst(inst).isConnected();
  }

  public static void savePersistent(int inst, String filename) throws PersistentException {
    throw new PersistentException("persistent values are kept by the server");
  }

  public static String[] loadPersistent(int inst, String filename) throws PersistentException {
    throw new PersistentException("persistent values are kept by the server");
  }

  public static void saveEntries(int inst, String filename, String prefix) throws PersistentException {
    throw new PersistentException("saving entries to a file is not supported");
  }

  public static String[] loadEntries(int inst, String filename, String prefix) throws PersistentException {
    throw new PersistentException("loading entries from a file is not supported");
  }

  public static long now() {
    return NtClient.now();
  }
}
