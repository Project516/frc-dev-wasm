package dev.project516.frcdevwasm.sim;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.wpilib.hardware.hal.OpModeOption;

/**
 * Driver station simulation data and the DS cache, port of mockdata/DriverStationData.cpp and
 * sim/DriverStation.cpp. Joystick and control data become visible to the robot only after
 * {@link #notifyNewData} followed by {@link #refresh}.
 */
public final class DriverStationData {
  public static final int JOYSTICKS = 6;
  public static final int MAX_AXES = 12;
  public static final int MAX_POVS = 8;
  public static final int MAX_BUTTONS = 64;
  public static final int MAX_TOUCHPADS = 2;
  public static final int MAX_FINGERS = 2;
  public static final int MAX_GAME_DATA = 64;
  public static final int MAX_EVENT_NAME = 64;
  public static final int MAX_JOYSTICK_NAME = 255;

  private static final long OPMODE_HASH_MASK = 0x00FFFFFFFFFFFFFFL;
  private static final long ROBOT_MODE_MASK = 0x0300000000000000L;
  private static final long ROBOT_MODE_SHIFT = 56;
  private static final long ENABLED_MASK = 0x0400000000000000L;
  private static final long ESTOP_MASK = 0x0800000000000000L;
  private static final long FMS_ATTACHED_MASK = 0x1000000000000000L;
  private static final long DS_ATTACHED_MASK = 0x2000000000000000L;

  private static final DriverStationData INSTANCE = new DriverStationData();

  public static DriverStationData get() {
    return INSTANCE;
  }

  public final BoolValue enabled = new BoolValue("Enabled");
  public final IntValue robotMode = IntValue.asEnum("RobotMode", 0);
  public final BoolValue eStop = new BoolValue("EStop");
  public final BoolValue fmsAttached = new BoolValue("FmsAttached");
  public final BoolValue dsAttached = new BoolValue("DsAttached");
  public final IntValue allianceStationId = IntValue.asEnum("AllianceStationId", 0);
  public final DoubleValue matchTime = new DoubleValue("MatchTime", -1.0);
  public final LongValue opMode = new LongValue("OpMode", 0);

  /** Joystick input and descriptor, as HAL_JoystickAxes, POVs, Buttons, Touchpads, Descriptor. */
  public static final class Stick {
    public final float[] axes = new float[MAX_AXES];
    public final short[] raw = new short[MAX_AXES];
    public int axesAvailable;
    public final byte[] povs = new byte[MAX_POVS];
    public int povsAvailable;
    public long buttons;
    public long buttonsAvailable;
    public int touchpadCount;
    public final int[] fingerCount = new int[MAX_TOUCHPADS];
    public final boolean[] fingerDown = new boolean[MAX_TOUCHPADS * MAX_FINGERS];
    /** x and y of each finger, at touchpad * 4 + finger * 2. */
    public final float[] fingerXy = new float[MAX_TOUCHPADS * MAX_FINGERS * 2];
    public boolean isGamepad;
    public int gamepadType;
    public int supportedOutputs;
    public String name = "";

    Stick copy() {
      Stick s = new Stick();
      System.arraycopy(axes, 0, s.axes, 0, MAX_AXES);
      System.arraycopy(raw, 0, s.raw, 0, MAX_AXES);
      s.axesAvailable = axesAvailable;
      System.arraycopy(povs, 0, s.povs, 0, MAX_POVS);
      s.povsAvailable = povsAvailable;
      s.buttons = buttons;
      s.buttonsAvailable = buttonsAvailable;
      s.touchpadCount = touchpadCount;
      System.arraycopy(fingerCount, 0, s.fingerCount, 0, fingerCount.length);
      System.arraycopy(fingerDown, 0, s.fingerDown, 0, fingerDown.length);
      System.arraycopy(fingerXy, 0, s.fingerXy, 0, fingerXy.length);
      s.isGamepad = isGamepad;
      s.gamepadType = gamepadType;
      s.supportedOutputs = supportedOutputs;
      s.name = name;
      return s;
    }
  }

  /** What robot code last set on a joystick. */
  public static final class Outputs {
    public int leds;
    public int leftRumble;
    public int rightRumble;
    public int leftTriggerRumble;
    public int rightTriggerRumble;
  }

  /** Match info as HAL_MatchInfo. */
  public static final class MatchInfo {
    public String eventName = "";
    public int matchType;
    public int matchNumber;
    public int replayNumber;

    MatchInfo copy() {
      MatchInfo m = new MatchInfo();
      m.eventName = eventName;
      m.matchType = matchType;
      m.matchNumber = matchNumber;
      m.replayNumber = replayNumber;
      return m;
    }
  }

  /** What the robot sees after a refresh: one consistent snapshot of DS state. */
  private static final class Snapshot {
    final Stick[] sticks = new Stick[JOYSTICKS];
    int allianceStation;
    double matchTime;
    long controlWord;
    String gameData = "";

    Snapshot() {
      for (int i = 0; i < JOYSTICKS; i++) {
        sticks[i] = new Stick();
      }
    }
  }

  private record OpModeOptionsReg(int uid, BiConsumer<String, OpModeOption[]> callback) {}

  private final Object lock = new Object();
  private final Stick[] sticks = new Stick[JOYSTICKS];
  private final Outputs[] outputs = new Outputs[JOYSTICKS];
  private final int[] version = new int[JOYSTICKS];
  private final int[] sentVersion = new int[JOYSTICKS];
  private MatchInfo matchInfo = new MatchInfo();
  private String gameData = "";
  private OpModeOption[] opModeOptions = new OpModeOption[0];
  private final List<OpModeOptionsReg> opModeOptionsCallbacks = new CopyOnWriteArrayList<>();
  private int nextOpModeOptionsUid = 1;
  private final List<Integer> newDataEvents = new ArrayList<>();

  private Snapshot pending;
  private Snapshot current = new Snapshot();
  private long newestControlWord;
  private MatchInfo currentMatchInfo = new MatchInfo();
  private Stick[] currentDescriptors = new Stick[JOYSTICKS];

  private DriverStationData() {
    for (int i = 0; i < JOYSTICKS; i++) {
      sticks[i] = new Stick();
      outputs[i] = new Outputs();
      currentDescriptors[i] = new Stick();
    }
    Arrays.fill(sentVersion, -1);
  }

  /** ResetData: values, joysticks, match info and callbacks. Game data and options stay. */
  public void reset() {
    enabled.reset(false);
    robotMode.reset(0);
    eStop.reset(false);
    fmsAttached.reset(false);
    dsAttached.reset(false);
    allianceStationId.reset(0);
    matchTime.reset(-1.0);
    opMode.reset(0);
    synchronized (lock) {
      for (int i = 0; i < JOYSTICKS; i++) {
        sticks[i] = new Stick();
        outputs[i] = new Outputs();
        version[i]++;
      }
      matchInfo = new MatchInfo();
    }
    opModeOptionsCallbacks.clear();
    wsDevice.rebind();
  }

  // Joystick access. Out of range sticks are ignored as in the C++ sim.

  private static boolean valid(int stick) {
    return stick >= 0 && stick < JOYSTICKS;
  }

  private static int mask(int count) {
    return count >= 32 ? -1 : (1 << count) - 1;
  }

  /** Replaces the axes of a stick: the first count of axes, with the given available mask. */
  public void setJoystickAxes(int stick, float[] axes, int available) {
    if (!valid(stick)) {
      return;
    }
    synchronized (lock) {
      Stick s = sticks[stick];
      Arrays.fill(s.axes, 0f);
      Arrays.fill(s.raw, (short) 0);
      if (axes != null) {
        System.arraycopy(axes, 0, s.axes, 0, Math.min(axes.length, MAX_AXES));
      }
      s.axesAvailable = available & 0xffff;
      version[stick]++;
    }
  }

  public void setJoystickPovs(int stick, byte[] povs, int available) {
    if (!valid(stick)) {
      return;
    }
    synchronized (lock) {
      Stick s = sticks[stick];
      Arrays.fill(s.povs, (byte) 0);
      if (povs != null) {
        System.arraycopy(povs, 0, s.povs, 0, Math.min(povs.length, MAX_POVS));
      }
      s.povsAvailable = available & 0xff;
      version[stick]++;
    }
  }

  public void setJoystickButtons(int stick, long buttons, long available) {
    if (!valid(stick)) {
      return;
    }
    synchronized (lock) {
      sticks[stick].buttons = buttons;
      sticks[stick].buttonsAvailable = available;
      version[stick]++;
    }
  }

  public void setJoystickButton(int stick, int button, boolean state) {
    if (!valid(stick) || button < 0 || button >= MAX_BUTTONS) {
      return;
    }
    synchronized (lock) {
      if (state) {
        sticks[stick].buttons |= 1L << button;
      } else {
        sticks[stick].buttons &= ~(1L << button);
      }
      version[stick]++;
    }
  }

  public void setJoystickButtonsValue(int stick, long buttons) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].buttons = buttons;
        version[stick]++;
      }
    }
  }

  public void setJoystickAxis(int stick, int axis, double value) {
    if (valid(stick) && axis >= 0 && axis < MAX_AXES) {
      synchronized (lock) {
        sticks[stick].axes[axis] = (float) value;
        version[stick]++;
      }
    }
  }

  public void setJoystickPov(int stick, int pov, byte value) {
    if (valid(stick) && pov >= 0 && pov < MAX_POVS) {
      synchronized (lock) {
        sticks[stick].povs[pov] = value;
        version[stick]++;
      }
    }
  }

  public void setJoystickAxesAvailable(int stick, int available) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].axesAvailable = available & 0xffff;
        version[stick]++;
      }
    }
  }

  public void setJoystickPovsAvailable(int stick, int available) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].povsAvailable = available & 0xff;
        version[stick]++;
      }
    }
  }

  public void setJoystickButtonsAvailable(int stick, long available) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].buttonsAvailable = available;
        version[stick]++;
      }
    }
  }

  public void setTouchpadCounts(int stick, int touchpadCount, int[] fingerCounts) {
    if (!valid(stick)) {
      return;
    }
    synchronized (lock) {
      Stick s = sticks[stick];
      s.touchpadCount = touchpadCount & 0xff;
      int n = Math.min(Math.min(touchpadCount, MAX_TOUCHPADS), fingerCounts == null ? 0 : fingerCounts.length);
      for (int i = 0; i < n; i++) {
        s.fingerCount[i] = fingerCounts[i] & 0xff;
      }
    }
  }

  public void setTouchpadFinger(
      int stick, int touchpad, int finger, boolean down, float x, float y) {
    if (!valid(stick)
        || touchpad < 0
        || touchpad >= MAX_TOUCHPADS
        || finger < 0
        || finger >= MAX_FINGERS) {
      return;
    }
    synchronized (lock) {
      Stick s = sticks[stick];
      s.fingerDown[touchpad * MAX_FINGERS + finger] = down;
      s.fingerXy[touchpad * 4 + finger * 2] = x;
      s.fingerXy[touchpad * 4 + finger * 2 + 1] = y;
    }
  }

  public void setJoystickIsGamepad(int stick, boolean isGamepad) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].isGamepad = isGamepad;
      }
    }
  }

  public void setJoystickGamepadType(int stick, int type) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].gamepadType = type & 0xff;
      }
    }
  }

  public void setJoystickSupportedOutputs(int stick, int supportedOutputs) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].supportedOutputs = supportedOutputs & 0xff;
      }
    }
  }

  public void setJoystickName(int stick, String name) {
    if (valid(stick)) {
      synchronized (lock) {
        sticks[stick].name = truncate(name, MAX_JOYSTICK_NAME);
      }
    }
  }

  private static String truncate(String s, int maxBytes) {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    return b.length <= maxBytes ? s : new String(b, 0, maxBytes, StandardCharsets.UTF_8);
  }

  public void setJoystickLeds(int stick, int leds) {
    if (!valid(stick)) {
      return;
    }
    synchronized (lock) {
      if (outputs[stick].leds == leds) {
        return;
      }
      outputs[stick].leds = leds;
    }
    BridgeLoop.sendDelta("Joystick", String.valueOf(stick), "<leds", leds);
  }

  public void setJoystickRumbles(int stick, int left, int right, int leftTrigger, int rightTrigger) {
    if (!valid(stick)) {
      return;
    }
    boolean leftChanged;
    boolean rightChanged;
    synchronized (lock) {
      Outputs o = outputs[stick];
      leftChanged = o.leftRumble != left;
      rightChanged = o.rightRumble != right;
      o.leftRumble = left;
      o.rightRumble = right;
      o.leftTriggerRumble = leftTrigger;
      o.rightTriggerRumble = rightTrigger;
    }
    if (leftChanged) {
      BridgeLoop.sendDelta("Joystick", String.valueOf(stick), "<rumble_left", left);
    }
    if (rightChanged) {
      BridgeLoop.sendDelta("Joystick", String.valueOf(stick), "<rumble_right", right);
    }
  }

  public int joystickLeds(int stick) {
    synchronized (lock) {
      return valid(stick) ? outputs[stick].leds : 0;
    }
  }

  /** Rumble 0 left, 1 right, 2 left trigger, 3 right trigger. */
  public int joystickRumble(int stick, int which) {
    if (!valid(stick)) {
      return 0;
    }
    synchronized (lock) {
      Outputs o = outputs[stick];
      return switch (which) {
        case 0 -> o.leftRumble;
        case 1 -> o.rightRumble;
        case 2 -> o.leftTriggerRumble;
        case 3 -> o.rightTriggerRumble;
        default -> 0;
      };
    }
  }

  // Match info and game data.

  public void setGameData(String data) {
    synchronized (lock) {
      gameData = truncate(data, MAX_GAME_DATA);
    }
  }

  public void setEventName(String name) {
    synchronized (lock) {
      matchInfo.eventName = truncate(name, MAX_EVENT_NAME);
    }
  }

  public void setMatchType(int type) {
    synchronized (lock) {
      matchInfo.matchType = type;
    }
  }

  public void setMatchNumber(int n) {
    synchronized (lock) {
      matchInfo.matchNumber = n & 0xffff;
    }
  }

  public void setReplayNumber(int n) {
    synchronized (lock) {
      matchInfo.replayNumber = n & 0xff;
    }
  }

  public void setMatchInfo(String eventName, int matchNumber, int replayNumber, int type) {
    synchronized (lock) {
      matchInfo.eventName = truncate(eventName, MAX_EVENT_NAME);
      matchInfo.matchNumber = matchNumber & 0xffff;
      matchInfo.replayNumber = replayNumber & 0xff;
      matchInfo.matchType = type;
    }
  }

  // Operating mode options.

  /** DriverStationData::SetOpModeOptions: entries with id 0 are dropped. */
  public void setOpModeOptions(OpModeOption[] options) {
    List<OpModeOption> kept = new ArrayList<>();
    if (options != null) {
      for (OpModeOption o : options) {
        if (o == null) {
          throw new IllegalArgumentException("Null OpModeOption passed in array");
        }
        if (o.id != 0) {
          kept.add(o);
        }
      }
    }
    OpModeOption[] copy = kept.toArray(new OpModeOption[0]);
    synchronized (lock) {
      opModeOptions = copy;
    }
    BridgeLoop.sendDelta("DriverStation", "", "<opmode_options", opModeOptionsJson(copy));
    for (OpModeOptionsReg r : opModeOptionsCallbacks) {
      r.callback().accept("OpModeOptions", copy.clone());
    }
  }

  private static List<Object> opModeOptionsJson(OpModeOption[] options) {
    List<Object> list = new ArrayList<>();
    for (OpModeOption o : options) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", Long.toString(o.id));
      m.put("name", o.name);
      m.put("group", o.group);
      m.put("description", o.description);
      m.put("textColor", o.textColor);
      m.put("backgroundColor", o.backgroundColor);
      list.add(m);
    }
    return list;
  }

  public OpModeOption[] opModeOptions() {
    synchronized (lock) {
      return opModeOptions.clone();
    }
  }

  public int registerOpModeOptionsCallback(
      BiConsumer<String, OpModeOption[]> callback, boolean initialNotify) {
    int uid;
    synchronized (lock) {
      uid = nextOpModeOptionsUid++;
      opModeOptionsCallbacks.add(new OpModeOptionsReg(uid, callback));
    }
    if (initialNotify) {
      callback.accept("OpModeOptions", opModeOptions());
    }
    return uid;
  }

  public void cancelOpModeOptionsCallback(int uid) {
    opModeOptionsCallbacks.removeIf(r -> r.uid() == uid);
  }

  // New data and the robot-facing cache.

  /** HAL_MakeControlWord. */
  public static long makeControlWord(
      long opModeHash, int robotMode, boolean enabled, boolean eStop, boolean fms, boolean ds) {
    return (opModeHash & OPMODE_HASH_MASK)
        | (((long) robotMode << ROBOT_MODE_SHIFT) & ROBOT_MODE_MASK)
        | (enabled ? ENABLED_MASK : 0)
        | (eStop ? ESTOP_MASK : 0)
        | (fms ? FMS_ATTACHED_MASK : 0)
        | (ds ? DS_ATTACHED_MASK : 0);
  }

  private long liveControlWord() {
    return makeControlWord(
        opMode.get(),
        robotMode.get(),
        enabled.get(),
        eStop.get(),
        fmsAttached.get(),
        dsAttached.get());
  }

  /** HALSIM_NotifyDriverStationNewData: snapshot the state for the robot and wake waiters. */
  public void notifyNewData() {
    dsAttached.set(true);
    Snapshot s = new Snapshot();
    synchronized (lock) {
      for (int i = 0; i < JOYSTICKS; i++) {
        s.sticks[i] = sticks[i].copy();
      }
      s.gameData = gameData;
    }
    s.allianceStation = allianceStationId.get();
    s.matchTime = matchTime.get();
    s.controlWord = liveControlWord();
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
    BridgeLoop.sendDelta("DriverStation", "", ">new_data", true);
    sendChangedJoysticks();
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
      if (!dsAttachedNow) {
        current.controlWord = 0;
      }
      newestControlWord = current.controlWord;
    }
    synchronized (lock) {
      currentMatchInfo = matchInfo.copy();
      for (int i = 0; i < JOYSTICKS; i++) {
        currentDescriptors[i] = sticks[i].copy();
      }
    }
    return p != null;
  }

  /** The control word of the last refresh. */
  public synchronized long controlWord() {
    return newestControlWord;
  }

  /** HAL_GetUncachedControlWord: the live state, zero without a DS. */
  public long uncachedControlWord() {
    return dsAttached.get() ? liveControlWord() : 0;
  }

  /** What DriverStation.isEnabled reads, without its ReentrantLock, which is slow on CheerpJ. */
  public boolean robotEnabled() {
    return (controlWord() & (ENABLED_MASK | DS_ATTACHED_MASK)) == (ENABLED_MASK | DS_ATTACHED_MASK);
  }

  public synchronized int currentAllianceStation() {
    return current.allianceStation;
  }

  public synchronized double currentMatchTime() {
    return current.matchTime;
  }

  public synchronized String currentGameData() {
    return current.gameData;
  }

  /** Joystick data as seen by the robot (the current snapshot). */
  public synchronized Stick currentStick(int stick) {
    return current.sticks[stick];
  }

  /** Joystick descriptor as seen by the robot (copied at refresh). */
  public synchronized Stick currentDescriptor(int stick) {
    return currentDescriptors[stick];
  }

  public synchronized MatchInfo currentMatchInfo() {
    return currentMatchInfo;
  }

  public boolean outputsEnabled() {
    long word = controlWord();
    return (word & ENABLED_MASK) != 0 && (word & DS_ATTACHED_MASK) != 0;
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
          if (key.equals(">station")) {
            return stationName(allianceStationId.get());
          }
          // Ids go past 2^53, which JSON numbers cannot hold.
          return key.equals(">opmode") ? Long.toString(opMode.get()) : value.toJson();
        }

        @Override
        public Map<String, Object> state() {
          Map<String, Object> m = super.state();
          m.put(">new_data", true);
          m.put("<opmode_options", opModeOptionsJson(opModeOptions()));
          m.put("<program_state", Long.toString(Sim.getProgramState()));
          return m;
        }

        @Override
        public void receive(Map<String, Object> data) {
          Map<String, Object> rest = new LinkedHashMap<>(data);
          if (rest.remove(">station") instanceof String s) {
            int id = Arrays.asList(STATIONS).indexOf(s);
            if (id > 0) {
              allianceStationId.set(id);
            }
          }
          if (rest.remove(">game_data") instanceof String s) {
            setGameData(s);
          }
          if (rest.get(">opmode") instanceof String s) {
            try {
              rest.put(">opmode", Long.parseLong(s));
            } catch (NumberFormatException e) {
              rest.remove(">opmode");
            }
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
        .bindEcho(">robotMode", robotMode)
        .bindEcho(">opmode", opMode)
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

  private void sendChangedJoysticks() {
    for (int i = 0; i < JOYSTICKS; i++) {
      boolean changed;
      synchronized (lock) {
        changed = version[i] != sentVersion[i];
        sentVersion[i] = version[i];
      }
      if (changed) {
        wsSticks[i].sendState();
      }
    }
  }

  private void receiveJoystick(int stick, Map<String, Object> data) {
    if (data.get(">axes") instanceof List<?> axes) {
      int count = Math.min(axes.size(), MAX_AXES);
      float[] a = new float[count];
      for (int i = 0; i < count; i++) {
        double v = Json.toDouble(axes.get(i));
        a[i] = Double.isNaN(v) ? 0f : (float) v;
      }
      setJoystickAxes(stick, a, mask(count));
    }
    if (data.get(">buttons") instanceof List<?> buttons) {
      int count = Math.min(buttons.size(), MAX_BUTTONS);
      long bits = 0;
      for (int i = 0; i < count; i++) {
        if (buttons.get(i) instanceof Boolean b && b) {
          bits |= 1L << i;
        }
      }
      setJoystickButtons(stick, bits, count < MAX_BUTTONS ? (1L << count) - 1 : -1L);
    }
    if (data.get(">povs") instanceof List<?> povs) {
      int count = Math.min(povs.size(), MAX_POVS);
      byte[] p = new byte[count];
      for (int i = 0; i < count; i++) {
        p[i] = povs.get(i) instanceof Number n ? (byte) n.intValue() : 0;
      }
      setJoystickPovs(stick, p, mask(count));
    }
    // The page already has what it sent.
    synchronized (lock) {
      sentVersion[stick] = version[stick];
    }
  }

  private Map<String, Object> joystickState(int stick) {
    Stick s;
    int leds;
    int left;
    int right;
    synchronized (lock) {
      s = sticks[stick].copy();
      leds = outputs[stick].leds;
      left = outputs[stick].leftRumble;
      right = outputs[stick].rightRumble;
    }
    double[] axes = new double[Math.min(32 - Integer.numberOfLeadingZeros(s.axesAvailable), MAX_AXES)];
    for (int i = 0; i < axes.length; i++) {
      axes[i] = s.axes[i];
    }
    int[] povs = new int[Math.min(32 - Integer.numberOfLeadingZeros(s.povsAvailable), MAX_POVS)];
    for (int i = 0; i < povs.length; i++) {
      povs[i] = s.povs[i] & 0xff;
    }
    boolean[] buttons = new boolean[64 - Long.numberOfLeadingZeros(s.buttonsAvailable)];
    for (int i = 0; i < buttons.length; i++) {
      buttons[i] = ((s.buttons >> i) & 1) != 0;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(">axes", axes);
    m.put(">povs", povs);
    m.put(">buttons", buttons);
    m.put("<leds", leds);
    m.put("<rumble_left", left);
    m.put("<rumble_right", right);
    return m;
  }
}
