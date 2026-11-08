package dev.project516.frcdevwasm.sim;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.Json;
import edu.wpi.first.hal.simulation.NotifyCallback;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Driver station simulation data and the DS cache, port of mockdata/DriverStationData.cpp and
 * sim/DriverStation.cpp. Joystick and control data become visible to the robot only after
 * {@link #notifyNewData} followed by {@link #refresh}.
 */
public final class DriverStationData {
  public static final int JOYSTICKS = 6;
  public static final int MAX_AXES = 12;
  public static final int MAX_POVS = 12;
  public static final int MAX_MATCH_MESSAGE = 64;

  private static final DriverStationData INSTANCE = new DriverStationData();

  public static DriverStationData get() {
    return INSTANCE;
  }

  public final BoolValue enabled = new BoolValue("Enabled");
  public final BoolValue autonomous = new BoolValue("Autonomous");
  public final BoolValue test = new BoolValue("Test");
  public final BoolValue eStop = new BoolValue("EStop");
  public final BoolValue fmsAttached = new BoolValue("FmsAttached");
  public final BoolValue dsAttached = new BoolValue("DsAttached");
  public final IntValue allianceStationId = IntValue.asEnum("AllianceStationId", 0);
  public final DoubleValue matchTime = new DoubleValue("MatchTime", -1.0);

  /** Raw state of one joystick. */
  public static final class Stick {
    public final float[] axes = new float[MAX_AXES];
    public final byte[] raw = new byte[MAX_AXES];
    public int axesCount;
    public final short[] povs = new short[MAX_POVS];
    public int povCount;
    public int buttons;
    public int buttonCount;
    public boolean isXbox;
    public int type = -1;
    public String name = "";
    public int axisCount;
    public final byte[] axisTypes = new byte[MAX_AXES];
    public int descriptorButtonCount;
    public int descriptorPovCount;
    public long outputs;
    public int leftRumble;
    public int rightRumble;

    Stick copy() {
      Stick s = new Stick();
      System.arraycopy(axes, 0, s.axes, 0, MAX_AXES);
      System.arraycopy(raw, 0, s.raw, 0, MAX_AXES);
      s.axesCount = axesCount;
      System.arraycopy(povs, 0, s.povs, 0, MAX_POVS);
      s.povCount = povCount;
      s.buttons = buttons;
      s.buttonCount = buttonCount;
      s.isXbox = isXbox;
      s.type = type;
      s.name = name;
      s.axisCount = axisCount;
      System.arraycopy(axisTypes, 0, s.axisTypes, 0, MAX_AXES);
      s.descriptorButtonCount = descriptorButtonCount;
      s.descriptorPovCount = descriptorPovCount;
      return s;
    }
  }

  /** Match info as HAL_MatchInfo. */
  public static final class MatchInfo {
    public String eventName = "";
    public byte[] gameSpecificMessage = new byte[0];
    public int matchType;
    public int matchNumber;
    public int replayNumber;

    MatchInfo copy() {
      MatchInfo m = new MatchInfo();
      m.eventName = eventName;
      m.gameSpecificMessage = gameSpecificMessage.clone();
      m.matchType = matchType;
      m.matchNumber = matchNumber;
      m.replayNumber = replayNumber;
      return m;
    }
  }

  /** What the robot sees after a refresh: one consistent snapshot of DS state. */
  private static final class Snapshot {
    Stick[] sticks = new Stick[JOYSTICKS];
    int allianceStation;
    double matchTime;
    int controlWord;
  }

  private final Object lock = new Object();
  private final Stick[] sticks = new Stick[JOYSTICKS];
  private MatchInfo matchInfo = new MatchInfo();
  private final List<NotifyCallback> newDataCallbacks = new CopyOnWriteArrayList<>();
  private final List<Integer> newDataEvents = new ArrayList<>();

  private Snapshot pending;
  private Snapshot current = new Snapshot();
  private int newestControlWord;
  private MatchInfo currentMatchInfo = new MatchInfo();
  private Stick[] currentDescriptors = new Stick[JOYSTICKS];

  private DriverStationData() {
    for (int i = 0; i < JOYSTICKS; i++) {
      sticks[i] = new Stick();
      current.sticks[i] = new Stick();
      currentDescriptors[i] = new Stick();
    }
  }

  /** ResetData: values, joysticks, match info and callbacks. */
  public void reset() {
    enabled.reset(false);
    autonomous.reset(false);
    test.reset(false);
    eStop.reset(false);
    fmsAttached.reset(false);
    dsAttached.reset(false);
    allianceStationId.reset(0);
    matchTime.reset(-1.0);
    synchronized (lock) {
      for (int i = 0; i < JOYSTICKS; i++) {
        sticks[i] = new Stick();
      }
      matchInfo = new MatchInfo();
    }
    newDataCallbacks.clear();
    wsDevice.rebind();
  }

  // Joystick access. Callers pass checked indexes; out of range is ignored as in the C++ sim.

  private static boolean valid(int stick) {
    return stick >= 0 && stick < JOYSTICKS;
  }

  public void setJoystickAxes(int stick, float[] axes, int count) {
    if (!valid(stick)) {
      return;
    }
    synchronized (lock) {
      Stick s = sticks[stick];
      s.axesCount = Math.min(count, MAX_AXES);
      java.util.Arrays.fill(s.axes, 0f);
      if (s.axesCount > 0) {
        System.arraycopy(axes, 0, s.axes, 0, s.axesCount);
      }
    }
  }

  public void setJoystickPovs(int stick, short[] povs, int count) {
    if (!valid(stick)) {
      return;
    }
    synchronized (lock) {
      Stick s = sticks[stick];
      s.povCount = Math.min(count, MAX_POVS);
      java.util.Arrays.fill(s.povs, (short) 0);
      if (s.povCount > 0) {
        System.arraycopy(povs, 0, s.povs, 0, s.povCount);
      }
    }
  }

  public void setJoystickButtons(int stick, int buttons, int count) {
    if (!valid(stick)) {
      return;
    }
    synchronized (lock) {
      sticks[stick].buttons = buttons;
      sticks[stick].buttonCount = Math.min(count, 32);
    }
  }

  public void setJoystickButton(int stick, int button, boolean state) {
    if (!valid(stick)) {
      return;
    }
    synchronized (lock) {
      if (state) {
        sticks[stick].buttons |= 1 << (button - 1);
      } else {
        sticks[stick].buttons &= ~(1 << (button - 1));
      }
    }
  }

  public void setJoystickButtonsValue(int stick, int buttons) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].buttons = buttons;
      }
      }
  }

  public void setJoystickAxis(int stick, int axis, double value) {
    if (valid(stick) && axis >= 0 && axis < MAX_AXES) {
      synchronized (lock) {
        sticks[stick].axes[axis] = (float) value;
      }
    }
  }

  public void setJoystickPov(int stick, int pov, int value) {
    if (valid(stick) && pov >= 0 && pov < MAX_POVS) {
      synchronized (lock) {
        sticks[stick].povs[pov] = (short) value;
      }
    }
  }

  public void setJoystickAxisCount(int stick, int count) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].axesCount = count;
        sticks[stick].axisCount = count;
      }
    }
  }

  public void setJoystickPovCount(int stick, int count) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].povCount = count;
        sticks[stick].descriptorPovCount = count;
      }
    }
  }

  public void setJoystickButtonCount(int stick, int count) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].buttonCount = count;
        sticks[stick].descriptorButtonCount = count;
      }
    }
  }

  public void setJoystickIsXbox(int stick, boolean isXbox) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].isXbox = isXbox;
      }
    }
  }

  public void setJoystickType(int stick, int type) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].type = type;
      }
    }
  }

  public void setJoystickName(int stick, String name) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].name = name.length() > 255 ? name.substring(0, 255) : name;
      }
    }
  }

  public void setJoystickAxisType(int stick, int axis, int type) {
    if (valid(stick) && axis >= 0 && axis < MAX_AXES) {
      synchronized (lock) {
        sticks[stick].axisTypes[axis] = (byte) type;
      }
    }
  }

  /** Joystick outputs as set by robot code; reported to the page. */
  public void setJoystickOutputs(int stick, long outputs, int leftRumble, int rightRumble) {
    if (!valid(stick)) {
      return;
    }
    synchronized (lock) {
      sticks[stick].outputs = outputs;
      sticks[stick].leftRumble = leftRumble;
      sticks[stick].rightRumble = rightRumble;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("<outputs", outputs);
    data.put("<rumble_left", leftRumble);
    data.put("<rumble_right", rightRumble);
    BridgeLoop.sendDeltas("Joystick", String.valueOf(stick), data);
  }

  /** Live joystick state, for the sim data JNI getters. Do not hold the result across calls. */
  public Stick stick(int stick) {
    return valid(stick) ? sticks[stick] : new Stick();
  }

  public void setGameSpecificMessage(String message) {
    byte[] b = message.getBytes(StandardCharsets.UTF_8);
    synchronized (lock) {
      matchInfo.gameSpecificMessage =
          b.length > MAX_MATCH_MESSAGE ? java.util.Arrays.copyOf(b, MAX_MATCH_MESSAGE) : b;
    }
  }

  public void setEventName(String name) {
    synchronized (lock) {
      matchInfo.eventName = name.length() > 63 ? name.substring(0, 63) : name;
    }
  }

  public void setMatchType(int type) {
    synchronized (lock) {
      matchInfo.matchType = type;
    }
  }

  public void setMatchNumber(int n) {
    synchronized (lock) {
      matchInfo.matchNumber = n;
    }
  }

  public void setReplayNumber(int n) {
    synchronized (lock) {
      matchInfo.replayNumber = n;
    }
  }

  public void setMatchInfo(
      String eventName, String gameSpecificMessage, int matchNumber, int replayNumber, int type) {
    setEventName(eventName);
    setGameSpecificMessage(gameSpecificMessage);
    synchronized (lock) {
      matchInfo.matchNumber = matchNumber;
      matchInfo.replayNumber = replayNumber;
      matchInfo.matchType = type;
    }
  }

  // New data and the robot-facing cache.

  public int registerNewDataCallback(NotifyCallback callback, boolean initialNotify) {
    newDataCallbacks.add(callback);
    if (initialNotify) {
      callback.callbackNative("NewData", 0, 0, 0);
    }
    return newDataCallbacks.size();
  }

  /** HALSIM_NotifyDriverStationNewData: snapshot the state for the robot and wake waiters. */
  public void notifyNewData() {
    dsAttached.set(true);
    Snapshot s = new Snapshot();
    synchronized (lock) {
      for (int i = 0; i < JOYSTICKS; i++) {
        s.sticks[i] = sticks[i].copy();
      }
    }
    s.allianceStation = allianceStationId.get();
    s.matchTime = matchTime.get();
    s.controlWord =
        (enabled.get() ? 1 : 0)
            | (autonomous.get() ? 2 : 0)
            | (test.get() ? 4 : 0)
            | (eStop.get() ? 8 : 0)
            | (fmsAttached.get() ? 16 : 0)
            | (dsAttached.get() ? 32 : 0);
    synchronized (this) {
      pending = s;
    }
    List<Integer> events;
    synchronized (newDataEvents) {
      events = new ArrayList<>(newDataEvents);
    }
    for (int handle : events) {
      Sync.setEvent(handle);
    }
    for (NotifyCallback cb : newDataCallbacks) {
      cb.callbackNative("NewData", 0, 0, 0);
    }
  }

  /** HAL_RefreshDSData: makes the newest snapshot current. Returns true if there was one. */
  public boolean refresh() {
    boolean dsAttachedNow = dsAttached.get();
    Snapshot p;
    synchronized (this) {
      p = pending;
      pending = null;
      if (p != null) {
        current = p;
      }
      newestControlWord = dsAttachedNow ? current.controlWord : 0;
    }
    synchronized (lock) {
      currentMatchInfo = matchInfo.copy();
      for (int i = 0; i < JOYSTICKS; i++) {
        currentDescriptors[i] = sticks[i].copy();
      }
    }
    return p != null;
  }

  public synchronized int controlWord() {
    return newestControlWord;
  }

  /** What DriverStation.isEnabled reads, without its ReentrantLock, which is slow on CheerpJ. */
  public boolean robotEnabled() {
    return (controlWord() & 0x21) == 0x21;
  }

  public synchronized int currentAllianceStation() {
    return current.allianceStation;
  }

  public synchronized double currentMatchTime() {
    return current.matchTime;
  }

  /** Joystick data as seen by the robot (the current snapshot). */
  public synchronized Stick currentStick(int stick) {
    return current.sticks[stick] == null ? new Stick() : current.sticks[stick];
  }

  /** Joystick descriptor as seen by the robot (copied at refresh). */
  public synchronized Stick currentDescriptor(int stick) {
    return currentDescriptors[stick];
  }

  public synchronized MatchInfo currentMatchInfo() {
    return currentMatchInfo;
  }

  public boolean outputsEnabled() {
    int word;
    synchronized (this) {
      word = newestControlWord;
    }
    return (word & 1) != 0 && (word & 32) != 0;
  }

  public void provideNewDataEventHandle(int handle) {
    Sim.ensureInitialized();
    synchronized (newDataEvents) {
      newDataEvents.add(handle);
    }
  }

  public void removeNewDataEventHandle(int handle) {
    synchronized (newDataEvents) {
      newDataEvents.remove((Integer) handle);
    }
  }

  // Bridge: halsim_ws WSProvider_DriverStation and WSProvider_Joystick.

  private static final String[] STATIONS = {"unknown", "red1", "red2", "red3", "blue1", "blue2", "blue3"};

  private static String stationName(int id) {
    return id >= 0 && id < STATIONS.length ? STATIONS[id] : "unknown";
  }

  private final Ws.Device wsDevice =
      new Ws.Device("DriverStation", "") {
        @Override
        protected Object json(String key, SimValue value) {
          return key.equals(">station") ? stationName(allianceStationId.get()) : value.toJson();
        }

        @Override
        public void receive(Map<String, Object> data) {
          Map<String, Object> rest = new LinkedHashMap<>(data);
          Object station = rest.remove(">station");
          if (station instanceof String s) {
            int id = java.util.Arrays.asList(STATIONS).indexOf(s);
            if (id > 0) {
              allianceStationId.set(id);
            }
          }
          if (rest.remove(">game_data") instanceof String s) {
            setGameSpecificMessage(s);
          }
          boolean newData = rest.remove(">new_data") != null;
          // halsim_gui announces new data only while its DS is connected, but its first message says ds false
          boolean disconnected = Boolean.FALSE.equals(rest.get(">ds"));
          super.receive(rest);
          if (newData && !disconnected) {
            notifyNewData();
          }
        }
      };

  private final Ws.Device[] wsSticks = new Ws.Device[JOYSTICKS];

  {
    wsDevice
        .bindEcho(">enabled", enabled)
        .bindEcho(">autonomous", autonomous)
        .bindEcho(">test", test)
        .bindEcho(">estop", eStop)
        .bindEcho(">fms", fmsAttached)
        .bindEcho(">ds", dsAttached)
        .bindEcho(">station", allianceStationId)
        .bindEcho(">match_time", matchTime);
    for (int i = 0; i < JOYSTICKS; i++) {
      int stick = i;
      wsSticks[i] =
          new Ws.Device("Joystick", String.valueOf(i)) {
            @Override
            public void receive(Map<String, Object> data) {
              receiveJoystick(stick, data);
            }

            @Override
            public Map<String, Object> state() {
              return joystickState(stick);
            }
          };
    }
  }

  Ws.Device wsDriverStation() {
    return wsDevice;
  }

  Ws.Device wsJoystick(String id) {
    int stick = Ws.index(id, JOYSTICKS);
    return stick < 0 ? null : wsSticks[stick];
  }

  private void receiveJoystick(int stick, Map<String, Object> data) {
    if (data.get(">axes") instanceof List<?> axes) {
      float[] a = new float[Math.min(axes.size(), MAX_AXES)];
      for (int i = 0; i < a.length; i++) {
        a[i] = (float) Json.toDouble(axes.get(i));
      }
      setJoystickAxes(stick, a, a.length);
    }
    if (data.get(">buttons") instanceof List<?> buttons) {
      int count = Math.min(buttons.size(), 32);
      int bits = 0;
      for (int i = 0; i < count; i++) {
        if (Json.toBool(buttons.get(i))) {
          bits |= 1 << i;
        }
      }
      setJoystickButtons(stick, bits, count);
    }
    if (data.get(">povs") instanceof List<?> povs) {
      short[] p = new short[Math.min(povs.size(), MAX_POVS)];
      for (int i = 0; i < p.length; i++) {
        p[i] = (short) Json.toLong(povs.get(i));
      }
      setJoystickPovs(stick, p, p.length);
    }
  }

  private Map<String, Object> joystickState(int stick) {
    Stick s;
    synchronized (lock) {
      s = sticks[stick].copy();
      s.outputs = sticks[stick].outputs;
      s.leftRumble = sticks[stick].leftRumble;
      s.rightRumble = sticks[stick].rightRumble;
    }
    double[] axes = new double[s.axesCount];
    for (int i = 0; i < axes.length; i++) {
      axes[i] = s.axes[i];
    }
    int[] povs = new int[s.povCount];
    for (int i = 0; i < povs.length; i++) {
      povs[i] = s.povs[i];
    }
    boolean[] buttons = new boolean[s.buttonCount];
    for (int i = 0; i < buttons.length; i++) {
      buttons[i] = ((s.buttons >> i) & 1) != 0;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(">axes", axes);
    m.put(">povs", povs);
    m.put(">buttons", buttons);
    m.put("<outputs", s.outputs);
    m.put("<rumble_left", s.leftRumble);
    m.put("<rumble_right", s.rightRumble);
    return m;
  }
}
