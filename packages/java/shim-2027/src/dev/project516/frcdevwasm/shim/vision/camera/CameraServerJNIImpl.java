package dev.project516.frcdevwasm.shim.vision.camera;

import org.wpilib.vision.camera.CameraServerJNI.LoggerFunction;
import org.wpilib.vision.camera.UsbCameraInfo;
import org.wpilib.vision.camera.VideoEvent;
import org.wpilib.vision.camera.VideoException;
import org.wpilib.vision.camera.VideoMode;
import org.wpilib.util.RawFrame;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Pure-Java stand-in for the cscore JNI library. There is no camera hardware: USB and HTTP
 * sources never connect, user-created (Cv/Raw) sources and all sinks work as bookkeeping only,
 * and frames passed to putRawSourceFrame* are dropped, so grabs never return one.
 *
 * <p>Handles use the cscore layout. All state is guarded by the class monitor, which blocking
 * calls wait on.
 */
public final class CameraServerJNIImpl {
  private CameraServerJNIImpl() {}

  // Handle types (wpi::kHandleTypeCSBase = 3)
  private static final int H_PROPERTY = 3;
  private static final int H_SOURCE = 4;
  private static final int H_SINK = 5;
  private static final int H_LISTENER = 6;
  private static final int H_SINK_PROPERTY = 7;
  private static final int H_POLLER = 8;

  // Source and sink kinds
  private static final int SRC_USB = 1;
  private static final int SRC_HTTP = 2;
  private static final int SRC_CV = 4;
  private static final int SRC_RAW = 8;
  private static final int SINK_MJPEG = 2;
  private static final int SINK_CV = 4;
  private static final int SINK_RAW = 8;
  private static final int CONFIGURABLE = SRC_CV | SRC_RAW;
  private static final int IMAGE_SINK = SINK_CV | SINK_RAW;

  // Property kinds
  private static final int P_NONE = 0;
  private static final int P_BOOLEAN = 1;
  private static final int P_INTEGER = 2;
  private static final int P_STRING = 4;
  private static final int P_ENUM = 8;

  // Event kinds
  private static final int E_SOURCE_CREATED = 0x1;
  private static final int E_SOURCE_DESTROYED = 0x2;
  private static final int E_SOURCE_CONNECTED = 0x4;
  private static final int E_SOURCE_DISCONNECTED = 0x8;
  private static final int E_VIDEOMODES_UPDATED = 0x10;
  private static final int E_VIDEOMODE_CHANGED = 0x20;
  private static final int E_SOURCE_PROP_CREATED = 0x40;
  private static final int E_SOURCE_PROP_VALUE = 0x80;
  private static final int E_SOURCE_PROP_CHOICES = 0x100;
  private static final int E_SINK_SOURCE_CHANGED = 0x200;
  private static final int E_SINK_CREATED = 0x400;
  private static final int E_SINK_DESTROYED = 0x800;
  private static final int E_SINK_ENABLED = 0x1000;
  private static final int E_SINK_DISABLED = 0x2000;
  private static final int E_NETWORK_INTERFACES = 0x4000;
  private static final int E_SINK_PROP_CREATED = 0x10000;
  private static final int E_SINK_PROP_VALUE = 0x20000;
  private static final int E_SINK_PROP_CHOICES = 0x40000;
  private static final int E_USB_CAMERAS = 0x80000;

  private static final int PIXFMT_MJPEG = 1;

  private static final Object LOCK = CameraServerJNIImpl.class;
  private static final long SLICE_MS = 500;

  private static final ArrayList<Source> sources = new ArrayList<>();
  private static final ArrayList<Sink> sinks = new ArrayList<>();
  private static final ArrayList<Listener> listeners = new ArrayList<>();
  private static final ArrayList<Poller> pollers = new ArrayList<>();
  private static final ArrayDeque<Pending> callbackQueue = new ArrayDeque<>();
  private static Thread callbackThread;

  private static boolean runLoopSignaled;

  private static boolean telemetryOn;
  private static double telemetryPeriod;
  private static double telemetryElapsed;
  private static long telemetryPrevNanos;
  private static Map<Long, Long> telemetryCurrent = new HashMap<>();
  private static Map<Long, Long> telemetryUser = new HashMap<>();

  private static Constructor<VideoEvent> eventCtor;

  // Errors

  private static VideoException ex(String msg) {
    return new VideoException(msg);
  }

  private static VideoException invalidHandle() {
    return ex("invalid handle");
  }

  private static void nonNull(Object o, String what) {
    if (o == null) {
      throw new NullPointerException(what + " cannot be null");
    }
  }

  // Handles

  private static int handle(int index, int type) {
    return (type << 24) | (index & 0xffff);
  }

  private static <T> T lookup(ArrayList<T> list, int handle, int type) {
    if (((handle >> 24) & 0xff) != type) {
      return null;
    }
    int i = handle & 0xffff;
    return i < list.size() ? list.get(i) : null;
  }

  private static <T> int allocate(ArrayList<T> list, T item, int type) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i) == null) {
        list.set(i, item);
        return handle(i, type);
      }
    }
    if (list.size() >= 0xffff) {
      return 0;
    }
    list.add(item);
    return handle(list.size() - 1, type);
  }

  private static Source source(int h) {
    Source s = lookup(sources, h, H_SOURCE);
    if (s == null) {
      throw invalidHandle();
    }
    return s;
  }

  private static Source configurable(int h) {
    Source s = source(h);
    if ((s.kind & CONFIGURABLE) == 0) {
      throw invalidHandle();
    }
    return s;
  }

  private static Sink sink(int h) {
    Sink s = lookup(sinks, h, H_SINK);
    if (s == null) {
      throw invalidHandle();
    }
    return s;
  }

  private static Sink imageSink(int h) {
    Sink s = sink(h);
    if ((s.kind & IMAGE_SINK) == 0) {
      throw invalidHandle();
    }
    return s;
  }

  private static Container owner(int propertyHandle) {
    int type = (propertyHandle >> 24) & 0xff;
    int parent = handle((propertyHandle >> 16) & 0xff, type == H_PROPERTY ? H_SOURCE : H_SINK);
    if (type == H_PROPERTY) {
      return source(parent);
    }
    if (type == H_SINK_PROPERTY) {
      return sink(parent);
    }
    throw invalidHandle();
  }

  private static <T> int[] handles(ArrayList<T> list, int type) {
    int n = 0;
    for (T t : list) {
      if (t != null) {
        n++;
      }
    }
    int[] out = new int[n];
    n = 0;
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i) != null) {
        out[n++] = handle(i, type);
      }
    }
    return out;
  }

  // Properties

  private static final class Prop {
    final String name;
    int kind = P_NONE;
    boolean hasMin;
    boolean hasMax;
    int min;
    int max = 100;
    int step = 1;
    int def;
    int value;
    String valueStr = "";
    String[] choices = new String[0];

    Prop(String name) {
      this.name = name;
    }

    void set(int v) {
      if (hasMin && v < min) {
        value = min;
      } else if (hasMax && v > max) {
        value = max;
      } else {
        value = v;
      }
    }
  }

  /** Property storage shared by sources and sinks. */
  private abstract static class Container {
    final String name;
    final boolean isSink;
    int handle;
    final ArrayList<Prop> props = new ArrayList<>();
    final Map<String, Integer> byName = new HashMap<>();
    boolean cached;

    Container(String name, boolean isSink) {
      this.name = name;
      this.isSink = isSink;
    }

    void onCache() {}

    // Mirrors cscore: property values only produce update events once something read them.
    final void cache() {
      if (!cached) {
        cached = true;
        onCache();
      }
    }

    final Prop prop(int index) {
      return index > 0 && index <= props.size() ? props.get(index - 1) : null;
    }

    final int propHandle(int index) {
      int type = isSink ? H_SINK_PROPERTY : H_PROPERTY;
      return (type << 24) | ((handle & 0xff) << 16) | (index & 0xffff);
    }

    /** Looks a property up by name, creating an empty one as cscore does. */
    final int index(String propName) {
      cache();
      Integer i = byName.get(propName);
      if (i == null) {
        props.add(new Prop(propName));
        i = props.size();
        byName.put(propName, i);
      }
      return i;
    }

    final int create(
        String propName, int kind, int min, int max, int step, int def, int value, boolean notify) {
      Integer i = byName.get(propName);
      Prop p;
      if (i == null) {
        p = new Prop(propName);
        p.value = value;
        props.add(p);
        i = props.size();
        byName.put(propName, i);
      } else {
        p = props.get(i - 1);
      }
      p.kind = kind;
      p.hasMin = true;
      p.hasMax = true;
      p.min = min;
      p.max = max;
      p.step = step;
      p.def = def;
      if (notify) {
        sendProperty(this, isSink ? E_SINK_PROP_CREATED : E_SOURCE_PROP_CREATED, i, p);
      }
      return i;
    }

    final void setInt(int index, int value) {
      Prop p = prop(index);
      if (p == null) {
        throw ex("invalid property");
      }
      if (p.kind == P_NONE) {
        p.kind = P_INTEGER;
      }
      if ((p.kind & (P_BOOLEAN | P_INTEGER | P_ENUM)) == 0) {
        throw ex("wrong property type");
      }
      p.set(value);
      valueUpdated(index, p);
    }

    final void setString(int index, String value) {
      Prop p = prop(index);
      if (p == null) {
        throw ex("invalid property");
      }
      if (p.kind == P_NONE) {
        p.kind = P_STRING;
      }
      if (p.kind != P_STRING) {
        throw ex("wrong property type");
      }
      p.valueStr = value;
      valueUpdated(index, p);
    }

    private void valueUpdated(int index, Prop p) {
      if (cached) {
        sendProperty(this, isSink ? E_SINK_PROP_VALUE : E_SOURCE_PROP_VALUE, index, p);
      }
    }
  }

  // Sources and sinks

  private static final class Source extends Container {
    final int kind;
    String description = "";
    int strategy;
    boolean connected;
    int numSinks;
    int numSinksEnabled;
    int refCount;
    int pixelFormat;
    int width;
    int height;
    int fps;
    final ArrayList<int[]> modes = new ArrayList<>();
    String path = "";
    int httpKind;
    String[] urls = new String[0];
    int brightness;
    long frameTime;
    String frameError = "";

    Source(int kind, String name) {
      super(name, false);
      this.kind = kind;
    }

    boolean isEnabled() {
      return strategy == 1 || (strategy == 0 && numSinksEnabled > 0);
    }

    int[] mode() {
      return new int[] {pixelFormat, width, height, fps};
    }

    void setMode(int pf, int w, int h, int f) {
      pixelFormat = pf;
      width = w;
      height = h;
      fps = f;
    }

    @Override
    void onCache() {
      if (kind != SRC_HTTP) {
        return;
      }
      modes.clear();
      int[][] sizes = {{640, 480}, {320, 240}, {160, 120}};
      for (int[] wh : sizes) {
        modes.add(new int[] {PIXFMT_MJPEG, wh[0], wh[1], 30});
      }
    }
  }

  private static final class Sink extends Container {
    final int kind;
    String description = "";
    int enabledCount;
    Source source;
    int sourceHandle;
    int refCount;
    String listenAddress = "";
    int port;

    Sink(int kind, String name) {
      super(name, true);
      this.kind = kind;
    }
  }

  private static int createSource(Source s) {
    int h = allocate(sources, s, H_SOURCE);
    s.handle = h;
    send(new Ev(E_SOURCE_CREATED, s.name).source(h));
    if ((s.kind & CONFIGURABLE) != 0) {
      send(new Ev(E_SOURCE_CONNECTED, s.name).source(h));
      send(new Ev(E_VIDEOMODES_UPDATED, s.name).source(h));
      sendMode(s);
    }
    return h;
  }

  private static int createSink(Sink s) {
    int h = allocate(sinks, s, H_SINK);
    s.handle = h;
    send(new Ev(E_SINK_CREATED, s.name).sink(h));
    return h;
  }

  private static void sendMode(Source s) {
    Ev e = new Ev(E_VIDEOMODE_CHANGED, s.name).source(s.handle);
    e.mode = s.mode();
    send(e);
  }

  private static void sendProperty(Container c, int kind, int index, Prop p) {
    Ev e = new Ev(kind, p.name);
    if (c.isSink) {
      e.sink(c.handle);
    } else {
      e.source(c.handle);
    }
    e.property = c.propHandle(index);
    e.propertyKind = p.kind;
    e.value = p.value;
    e.valueStr = p.valueStr;
    send(e);
  }

  private static void wakeFrames(Source s) {
    s.frameTime = 0;
    s.frameError = "";
    LOCK.notifyAll();
  }

  private static void destroySource(int h) {
    Source s = lookup(sources, h, H_SOURCE);
    sources.set(h & 0xffff, null);
    wakeFrames(s);
    send(new Ev(E_SOURCE_DESTROYED, s.name).source(h));
  }

  private static void destroySink(int h) {
    Sink s = lookup(sinks, h, H_SINK);
    sinks.set(h & 0xffff, null);
    detach(s);
    if (s.source != null) {
      wakeFrames(s.source);
    }
    send(new Ev(E_SINK_DESTROYED, s.name).sink(h));
  }

  private static void detach(Sink s) {
    if (s.source != null) {
      if (s.enabledCount > 0) {
        s.source.numSinksEnabled--;
      }
      s.source.numSinks--;
    }
  }

  // Events

  private static final class Ev {
    final int kind;
    final String name;
    int source;
    int sink;
    int[] mode = {0, 0, 0, 0};
    int property;
    int propertyKind;
    int value;
    String valueStr = "";
    int listener;

    Ev(int kind, String name) {
      this.kind = kind;
      this.name = name;
    }

    Ev source(int h) {
      source = h;
      return this;
    }

    Ev sink(int h) {
      sink = h;
      return this;
    }

    Ev copyFor(int listenerHandle) {
      Ev e = new Ev(kind, name);
      e.source = source;
      e.sink = sink;
      e.mode = mode;
      e.property = property;
      e.propertyKind = propertyKind;
      e.value = value;
      e.valueStr = valueStr;
      e.listener = listenerHandle;
      return e;
    }
  }

  private static final class Poller {
    final ArrayDeque<Ev> queue = new ArrayDeque<>();
    boolean canceled;
    boolean destroyed;
  }

  private static final class Listener {
    final int mask;
    final Poller poller;
    final Consumer<VideoEvent> callback;
    boolean removed;
    int handle;

    Listener(int mask, Poller poller, Consumer<VideoEvent> callback) {
      this.mask = mask;
      this.poller = poller;
      this.callback = callback;
    }
  }

  private static final class Pending {
    final Listener listener;
    final Ev event;

    Pending(Listener listener, Ev event) {
      this.listener = listener;
      this.event = event;
    }
  }

  private static void send(Ev e) {
    boolean wake = false;
    for (Listener l : listeners) {
      if (l == null || (e.kind & l.mask) == 0) {
        continue;
      }
      Ev copy = e.copyFor(l.handle);
      if (l.poller != null) {
        l.poller.queue.add(copy);
      } else {
        callbackQueue.add(new Pending(l, copy));
      }
      wake = true;
    }
    if (wake) {
      LOCK.notifyAll();
    }
  }

  private static VideoEvent toVideoEvent(Ev e) {
    try {
      if (eventCtor == null) {
        Constructor<VideoEvent> c = VideoEvent.class.getDeclaredConstructor(
            int.class, int.class, int.class, String.class, int.class, int.class, int.class,
            int.class, int.class, int.class, int.class, String.class, int.class);
        c.setAccessible(true);
        eventCtor = c;
      }
      return eventCtor.newInstance(e.kind, e.source, e.sink, e.name, e.mode[0], e.mode[1],
          e.mode[2], e.mode[3], e.property, e.propertyKind, e.value, e.valueStr, e.listener);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static VideoEvent[] toVideoEvents(ArrayDeque<Ev> queue) {
    VideoEvent[] out = new VideoEvent[queue.size()];
    int i = 0;
    for (Ev e : queue) {
      out[i++] = toVideoEvent(e);
    }
    queue.clear();
    return out;
  }

  private static void callbackLoop() {
    while (true) {
      Pending p;
      synchronized (LOCK) {
        while ((p = callbackQueue.poll()) == null) {
          try {
            LOCK.wait(SLICE_MS * 2);
          } catch (InterruptedException e) {
            return;
          }
        }
      }
      if (p.listener.removed) {
        continue;
      }
      try {
        p.listener.callback.accept(toVideoEvent(p.event));
      } catch (Throwable t) {
        t.printStackTrace();
      }
    }
  }

  private static int addListenerImpl(Poller poller, Consumer<VideoEvent> cb, int mask, boolean immediate) {
    Listener l = new Listener(mask, poller, cb);
    l.handle = allocate(listeners, l, H_LISTENER);
    if (poller == null && callbackThread == null) {
      callbackThread = new Thread(CameraServerJNIImpl::callbackLoop, "CSListener");
      callbackThread.setDaemon(true);
      callbackThread.start();
    }
    if (immediate) {
      if ((mask & E_NETWORK_INTERFACES) != 0) {
        send(new Ev(E_NETWORK_INTERFACES, ""));
      }
      if ((mask & E_USB_CAMERAS) != 0) {
        send(new Ev(E_USB_CAMERAS, ""));
      }
    }
    return l.handle;
  }

  /** Waits up to one slice, or less if the deadline is sooner. Returns false when interrupted. */
  private static boolean pause(long ms) {
    try {
      LOCK.wait(Math.max(1, Math.min(ms, SLICE_MS)));
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static long nowMicros() {
    return Math.max(1, System.nanoTime() / 1000);
  }

  // Property functions

  public static synchronized int getPropertyKind(int property) {
    Container c = owner(property);
    c.cache();
    Prop p = c.prop(property & 0xffff);
    return p == null ? P_NONE : p.kind;
  }

  private static Prop cachedProp(int property) {
    Container c = owner(property);
    c.cache();
    Prop p = c.prop(property & 0xffff);
    if (p == null) {
      throw ex("invalid property");
    }
    return p;
  }

  public static synchronized String getPropertyName(int property) {
    return cachedProp(property).name;
  }

  public static synchronized int getProperty(int property) {
    Prop p = cachedProp(property);
    if ((p.kind & (P_BOOLEAN | P_INTEGER | P_ENUM)) == 0) {
      throw ex("wrong property type");
    }
    return p.value;
  }

  private static boolean isHttp(Container c) {
    return c instanceof Source s && s.kind == SRC_HTTP;
  }

  public static synchronized void setProperty(int property, int value) {
    Container c = owner(property);
    if (!isHttp(c)) {
      c.setInt(property & 0xffff, value);
    }
  }

  public static synchronized int getPropertyMin(int property) {
    return cachedProp(property).min;
  }

  public static synchronized int getPropertyMax(int property) {
    return cachedProp(property).max;
  }

  public static synchronized int getPropertyStep(int property) {
    return cachedProp(property).step;
  }

  public static synchronized int getPropertyDefault(int property) {
    return cachedProp(property).def;
  }

  public static synchronized String getStringProperty(int property) {
    Prop p = cachedProp(property);
    if (p.kind != P_STRING) {
      throw ex("wrong property type");
    }
    return p.valueStr;
  }

  public static synchronized void setStringProperty(int property, String value) {
    nonNull(value, "value");
    Container c = owner(property);
    if (!isHttp(c)) {
      c.setString(property & 0xffff, value);
    }
  }

  public static synchronized String[] getEnumPropertyChoices(int property) {
    Prop p = cachedProp(property);
    if (p.kind != P_ENUM) {
      throw ex("wrong property type");
    }
    return p.choices.clone();
  }

  // Source creation functions

  private static int usbDev(String path) {
    String prefix = "/dev/video";
    if (!path.startsWith(prefix) || path.length() == prefix.length()) {
      return -1;
    }
    try {
      return Integer.parseInt(path.substring(prefix.length()));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static int createUsb(String name, String path) {
    Source s = new Source(SRC_USB, name);
    s.path = path;
    return createSource(s);
  }

  public static synchronized int createUsbCameraDev(String name, int dev) {
    nonNull(name, "name");
    return createUsb(name, "/dev/video" + dev);
  }

  public static synchronized int createUsbCameraPath(String name, String path) {
    nonNull(name, "name");
    nonNull(path, "path");
    return createUsb(name, path);
  }

  /** Mirrors wpi::HttpLocation: http scheme, non-empty host, numeric port. */
  private static boolean validUrl(String url) {
    int colon = url.indexOf(':');
    if (colon < 0 || !url.substring(0, colon).equalsIgnoreCase("http")) {
      return false;
    }
    String rest = url.substring(colon + 1);
    if (!rest.startsWith("//")) {
      return false;
    }
    rest = rest.substring(2);
    int slash = rest.indexOf('/');
    String authority = slash < 0 ? rest : rest.substring(0, slash);
    String hostPort = authority.substring(authority.indexOf('@') + 1);
    int portColon = hostPort.lastIndexOf(':');
    String host = portColon < 0 ? hostPort : hostPort.substring(0, portColon);
    if (host.isEmpty()) {
      return false;
    }
    if (portColon >= 0 && portColon < hostPort.length() - 1) {
      try {
        Integer.parseInt(hostPort.substring(portColon + 1));
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }

  private static int createHttp(String name, String[] urls, int kind) {
    for (String u : urls) {
      if (!validUrl(u)) {
        throw ex("bad URL");
      }
    }
    Source s = new Source(SRC_HTTP, name);
    s.httpKind = kind;
    s.urls = urls.clone();
    return createSource(s);
  }

  public static synchronized int createHttpCamera(String name, String url, int kind) {
    nonNull(name, "name");
    nonNull(url, "url");
    return createHttp(name, new String[] {url}, kind);
  }

  public static synchronized int createHttpCameraMulti(String name, String[] urls, int kind) {
    nonNull(name, "name");
    nonNull(urls, "urls");
    for (String u : urls) {
      if (u == null) {
        return 0;
      }
    }
    if (urls.length == 0) {
      throw ex("empty value");
    }
    return createHttp(name, urls, kind);
  }

  public static synchronized int createRawSource(
      String name, boolean isCv, int pixelFormat, int width, int height, int fps) {
    nonNull(name, "name");
    Source s = new Source(isCv ? SRC_CV : SRC_RAW, name);
    s.setMode(pixelFormat, width, height, fps);
    return createSource(s);
  }

  // Source functions

  public static synchronized int getSourceKind(int source) {
    return source(source).kind;
  }

  public static synchronized String getSourceName(int source) {
    return source(source).name;
  }

  public static synchronized String getSourceDescription(int source) {
    return source(source).description;
  }

  public static synchronized long getSourceLastFrameTime(int source) {
    return source(source).frameTime;
  }

  public static synchronized void setSourceConnectionStrategy(int source, int strategy) {
    source(source).strategy = strategy;
  }

  public static synchronized boolean isSourceConnected(int source) {
    return source(source).connected;
  }

  public static synchronized boolean isSourceEnabled(int source) {
    return source(source).isEnabled();
  }

  public static synchronized int getSourceProperty(int source, String name) {
    nonNull(name, "name");
    Source s = source(source);
    return s.propHandle(s.index(name));
  }

  public static synchronized int[] enumerateSourceProperties(int source) {
    return propertyHandles(source(source));
  }

  private static int[] propertyHandles(Container c) {
    c.cache();
    int[] out = new int[c.props.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = c.propHandle(i + 1);
    }
    return out;
  }

  private static VideoMode toMode(int[] m) {
    return new VideoMode(m[0], m[1], m[2], m[3]);
  }

  public static synchronized VideoMode getSourceVideoMode(int source) {
    Source s = source(source);
    s.cache();
    return toMode(s.mode());
  }

  public static synchronized boolean setSourceVideoMode(
      int source, int pixelFormat, int width, int height, int fps) {
    return applyMode(source(source), pixelFormat, width, height, fps);
  }

  private static boolean applyMode(Source s, int pf, int w, int h, int fps) {
    switch (s.kind) {
      case SRC_HTTP:
        if (pf != PIXFMT_MJPEG) {
          return false;
        }
        s.setMode(pf, w, h, fps);
        return true;
      case SRC_CV:
      case SRC_RAW:
        s.setMode(pf, w, h, fps);
        sendMode(s);
        return true;
      default:
        s.setMode(pf, w, h, fps);
        return true;
    }
  }

  // Cv, raw and HTTP sources refuse a partial change until they have a full mode.
  private static boolean partialModeOk(Source s) {
    s.cache();
    return s.kind == SRC_USB || s.pixelFormat != 0;
  }

  public static synchronized boolean setSourcePixelFormat(int source, int pixelFormat) {
    Source s = source(source);
    return partialModeOk(s) && applyMode(s, pixelFormat, s.width, s.height, s.fps);
  }

  public static synchronized boolean setSourceResolution(int source, int width, int height) {
    Source s = source(source);
    return partialModeOk(s) && applyMode(s, s.pixelFormat, width, height, s.fps);
  }

  public static synchronized boolean setSourceFPS(int source, int fps) {
    Source s = source(source);
    return partialModeOk(s) && applyMode(s, s.pixelFormat, s.width, s.height, fps);
  }

  private static final String[] PIXEL_FORMAT_NAMES = {
    null, "mjpeg", "yuyv", "rgb565", "bgr", "gray", "y16", "uyvy", "bgra"
  };

  private static int pixelFormatFromName(String name) {
    for (int i = 1; i < PIXEL_FORMAT_NAMES.length; i++) {
      if (PIXEL_FORMAT_NAMES[i].equalsIgnoreCase(name)) {
        return i;
      }
    }
    return 0;
  }

  private static Map<String, Object> parseObject(String config) {
    try {
      if (MiniJson.parse(config) instanceof Map<?, ?> m) {
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) m;
        return cast;
      }
    } catch (IllegalArgumentException | NullPointerException e) {
      // falls through
    }
    throw ex("property write failed");
  }

  private static int unsignedInt(Object o) {
    return o instanceof Number n && n.longValue() >= 0 ? n.intValue() : 0;
  }

  private static void setPropertiesJson(Container c, Object props) {
    if (!(props instanceof List<?> list)) {
      return;
    }
    for (Object o : list) {
      if (!(o instanceof Map<?, ?> m) || !(m.get("name") instanceof String name)) {
        continue;
      }
      int index = c.index(name);
      Object v = m.get("value");
      if (isHttp(c)) {
        continue;
      }
      if (v instanceof String str) {
        c.setString(index, str);
      } else if (v instanceof Boolean b) {
        c.setInt(index, b ? 1 : 0);
      } else if (v instanceof Number n) {
        c.setInt(index, n.intValue());
      }
    }
  }

  private static List<Object> propertiesJson(Container c) {
    c.cache();
    List<Object> out = new ArrayList<>();
    for (Prop p : c.props) {
      Map<String, Object> m = new TreeMap<>();
      m.put("name", p.name);
      switch (p.kind) {
        case P_BOOLEAN -> m.put("value", p.value != 0);
        case P_INTEGER, P_ENUM -> m.put("value", p.value);
        case P_STRING -> m.put("value", p.valueStr);
        default -> {
          continue;
        }
      }
      out.add(m);
    }
    return out;
  }

  public static synchronized boolean setSourceConfigJson(int source, String config) {
    Source s = source(source);
    Map<String, Object> j = parseObject(config);
    int pf = j.get("pixel format") instanceof String str ? pixelFormatFromName(str) : 0;
    int w = unsignedInt(j.get("width"));
    int h = unsignedInt(j.get("height"));
    int fps = unsignedInt(j.get("fps"));
    if (pf != 0 && w != 0 && h != 0 && fps != 0) {
      applyMode(s, pf, w, h, fps);
    } else {
      if (pf != 0) {
        setSourcePixelFormat(source, pf);
      }
      if (w != 0 && h != 0) {
        setSourceResolution(source, w, h);
      }
      if (fps != 0) {
        setSourceFPS(source, fps);
      }
    }
    if (j.get("brightness") instanceof Number n) {
      setCameraBrightness(source, n.intValue());
    }
    whiteBalanceOrExposure(source, j.get("white balance"), true);
    whiteBalanceOrExposure(source, j.get("exposure"), false);
    setPropertiesJson(s, j.get("properties"));
    return true;
  }

  private static void whiteBalanceOrExposure(int source, Object v, boolean whiteBalance) {
    if (v instanceof String str) {
      if (str.equalsIgnoreCase("auto")) {
        if (whiteBalance) {
          setCameraWhiteBalanceAuto(source);
        } else {
          setCameraExposureAuto(source);
        }
      } else if (str.equalsIgnoreCase("hold")) {
        if (whiteBalance) {
          setCameraWhiteBalanceHoldCurrent(source);
        } else {
          setCameraExposureHoldCurrent(source);
        }
      }
    } else if (v instanceof Number n) {
      if (whiteBalance) {
        setCameraWhiteBalanceManual(source, n.intValue());
      } else {
        setCameraExposureManual(source, n.intValue());
      }
    }
  }

  public static synchronized String getSourceConfigJson(int source) {
    Source s = source(source);
    Map<String, Object> j = new TreeMap<>();
    if (s.pixelFormat > 0 && s.pixelFormat < PIXEL_FORMAT_NAMES.length) {
      j.put("pixel format", PIXEL_FORMAT_NAMES[s.pixelFormat]);
    }
    if (s.width != 0) {
      j.put("width", s.width);
    }
    if (s.height != 0) {
      j.put("height", s.height);
    }
    if (s.fps != 0) {
      j.put("fps", s.fps);
    }
    List<Object> props = propertiesJson(s);
    if (!props.isEmpty()) {
      j.put("properties", props);
    }
    return MiniJson.dump(j);
  }

  public static synchronized VideoMode[] enumerateSourceVideoModes(int source) {
    Source s = source(source);
    s.cache();
    List<int[]> modes = (s.kind & CONFIGURABLE) != 0 ? List.of(s.mode()) : s.modes;
    VideoMode[] out = new VideoMode[modes.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = toMode(modes.get(i));
    }
    return out;
  }

  public static synchronized int[] enumerateSourceSinks(int source) {
    source(source);
    int n = 0;
    int[] tmp = new int[sinks.size()];
    for (int i = 0; i < sinks.size(); i++) {
      Sink s = sinks.get(i);
      if (s != null && s.sourceHandle == source) {
        tmp[n++] = handle(i, H_SINK);
      }
    }
    return java.util.Arrays.copyOf(tmp, n);
  }

  public static synchronized int copySource(int source) {
    if (source == 0) {
      return 0;
    }
    source(source).refCount++;
    return source;
  }

  public static synchronized void releaseSource(int source) {
    if (source == 0) {
      return;
    }
    if (source(source).refCount-- == 0) {
      destroySource(source);
    }
  }

  // Camera source common property functions

  public static synchronized void setCameraBrightness(int source, int brightness) {
    Source s = source(source);
    if (s.kind == SRC_USB) {
      s.brightness = brightness;
    } else if (s.kind != SRC_HTTP) {
      throw invalidHandle();
    }
  }

  public static synchronized int getCameraBrightness(int source) {
    Source s = source(source);
    if (s.kind != SRC_USB && s.kind != SRC_HTTP) {
      throw invalidHandle();
    }
    return s.kind == SRC_USB ? s.brightness : 0;
  }

  private static void cameraOnly(int source) {
    int kind = source(source).kind;
    if (kind != SRC_USB && kind != SRC_HTTP) {
      throw invalidHandle();
    }
  }

  public static synchronized void setCameraWhiteBalanceAuto(int source) {
    cameraOnly(source);
  }

  public static synchronized void setCameraWhiteBalanceHoldCurrent(int source) {
    cameraOnly(source);
  }

  public static synchronized void setCameraWhiteBalanceManual(int source, int value) {
    cameraOnly(source);
  }

  public static synchronized void setCameraExposureAuto(int source) {
    cameraOnly(source);
  }

  public static synchronized void setCameraExposureHoldCurrent(int source) {
    cameraOnly(source);
  }

  public static synchronized void setCameraExposureManual(int source, int value) {
    cameraOnly(source);
  }

  // USB camera functions

  private static Source usb(int source) {
    Source s = source(source);
    if (s.kind != SRC_USB) {
      throw invalidHandle();
    }
    return s;
  }

  public static synchronized void setUsbCameraPath(int source, String path) {
    usb(source).path = path;
  }

  public static synchronized String getUsbCameraPath(int source) {
    return usb(source).path;
  }

  public static synchronized UsbCameraInfo getUsbCameraInfo(int source) {
    Source s = usb(source);
    return new UsbCameraInfo(usbDev(s.path), s.path, s.description, new String[0], 0, 0);
  }

  // HTTP camera functions

  private static Source http(int source) {
    Source s = source(source);
    if (s.kind != SRC_HTTP) {
      throw invalidHandle();
    }
    return s;
  }

  public static synchronized int getHttpCameraKind(int source) {
    return http(source).httpKind;
  }

  public static synchronized void setHttpCameraUrls(int source, String[] urls) {
    nonNull(urls, "urls");
    for (String u : urls) {
      if (u == null) {
        return;
      }
    }
    if (urls.length == 0) {
      throw ex("empty value");
    }
    Source s = http(source);
    for (String u : urls) {
      if (!validUrl(u)) {
        throw ex("bad URL");
      }
    }
    s.urls = urls.clone();
  }

  public static synchronized String[] getHttpCameraUrls(int source) {
    return http(source).urls.clone();
  }

  // Image source functions

  private static void putFrame(int source, int size) {
    Source s = configurable(source);
    s.frameTime = nowMicros();
    s.frameError = "";
    telemetryRecord(source, 2, 1);
    telemetryRecord(source, 1, size);
    LOCK.notifyAll();
  }

  public static synchronized void putRawSourceFrame(int source, long frame) {
    if (frame == 0) {
      throw new NullPointerException("frame is null");
    }
    putFrame(source, 0);
  }

  public static synchronized void putRawSourceFrameBB(
      int source, ByteBuffer data, int size, int width, int height, int stride, int pixelFormat) {
    if (data == null || !data.isDirect()) {
      throw new NullPointerException("data is null");
    }
    putFrame(source, size);
  }

  public static synchronized void putRawSourceFrameData(
      int source, long data, int size, int width, int height, int stride, int pixelFormat) {
    if (data == 0) {
      throw new NullPointerException("data is null");
    }
    putFrame(source, size);
  }

  public static synchronized void notifySourceError(int source, String msg) {
    nonNull(msg, "msg");
    Source s = configurable(source);
    s.frameTime = nowMicros();
    s.frameError = msg;
    LOCK.notifyAll();
  }

  public static synchronized void setSourceConnected(int source, boolean connected) {
    Source s = configurable(source);
    boolean was = s.connected;
    s.connected = connected;
    if (was && !connected) {
      send(new Ev(E_SOURCE_DISCONNECTED, s.name).source(s.handle));
    } else if (!was && connected) {
      send(new Ev(E_SOURCE_CONNECTED, s.name).source(s.handle));
    }
  }

  public static synchronized void setSourceDescription(int source, String description) {
    nonNull(description, "description");
    configurable(source).description = description;
  }

  public static synchronized int createSourceProperty(
      int source,
      String name,
      int kind,
      int minimum,
      int maximum,
      int step,
      int defaultValue,
      int value) {
    Source s = configurable(source);
    int index = s.create(name, kind, minimum, maximum, step, defaultValue, value, true);
    return s.propHandle(index);
  }

  public static synchronized void setSourceEnumPropertyChoices(
      int source, int property, String[] choices) {
    nonNull(choices, "choices");
    Source s = configurable(source);
    for (String c : choices) {
      if (c == null) {
        return;
      }
    }
    if (owner(property) != s) {
      throw invalidHandle();
    }
    int index = property & 0xffff;
    Prop p = s.prop(index);
    if (p == null) {
      throw ex("invalid property");
    }
    if (p.kind != P_ENUM) {
      throw ex("wrong property type");
    }
    p.choices = choices.clone();
    sendProperty(s, E_SOURCE_PROP_CHOICES, index, p);
  }

  // Sink creation functions

  private static Prop sinkProp(Sink s, String name, int min, int max, int step, int def, int value, boolean ranged) {
    int i = s.create(name, P_INTEGER, min, max, step, def, value, false);
    Prop p = s.props.get(i - 1);
    if (!ranged) {
      p.hasMin = false;
      p.hasMax = false;
      p.min = 0;
      p.max = 100;
    }
    return p;
  }

  public static synchronized int createMjpegServer(String name, String listenAddress, int port) {
    nonNull(name, "name");
    nonNull(listenAddress, "listenAddress");
    Sink s = new Sink(SINK_MJPEG, name);
    s.listenAddress = listenAddress;
    s.port = port;
    s.description = "HTTP Server on port " + port;
    sinkProp(s, "width", 0, 0, 1, 0, 0, false);
    sinkProp(s, "height", 0, 0, 1, 0, 0, false);
    sinkProp(s, "compression", -1, 100, 1, -1, -1, true);
    sinkProp(s, "default_compression", 0, 100, 1, 80, 80, true);
    sinkProp(s, "fps", 0, 0, 1, 0, 0, false);
    return createSink(s);
  }

  public static synchronized int createRawSink(String name, boolean isCv) {
    nonNull(name, "name");
    return createSink(new Sink(isCv ? SINK_CV : SINK_RAW, name));
  }

  // Sink functions

  public static synchronized int getSinkKind(int sink) {
    return sink(sink).kind;
  }

  public static synchronized String getSinkName(int sink) {
    return sink(sink).name;
  }

  public static synchronized String getSinkDescription(int sink) {
    return sink(sink).description;
  }

  public static synchronized int getSinkProperty(int sink, String name) {
    nonNull(name, "name");
    Sink s = sink(sink);
    return s.propHandle(s.index(name));
  }

  public static synchronized int[] enumerateSinkProperties(int sink) {
    return propertyHandles(sink(sink));
  }

  public static synchronized boolean setSinkConfigJson(int sink, String config) {
    Sink s = sink(sink);
    setPropertiesJson(s, parseObject(config).get("properties"));
    return true;
  }

  public static synchronized String getSinkConfigJson(int sink) {
    Map<String, Object> j = new TreeMap<>();
    List<Object> props = propertiesJson(sink(sink));
    if (!props.isEmpty()) {
      j.put("properties", props);
    }
    return MiniJson.dump(j);
  }

  public static synchronized void setSinkSource(int sink, int source) {
    Sink s = sink(sink);
    Source src = null;
    if (source != 0) {
      src = source(source);
    }
    if (s.source != src) {
      detach(s);
      s.source = src;
      if (src != null) {
        src.numSinks++;
        if (s.enabledCount > 0) {
          src.numSinksEnabled++;
        }
      }
    }
    s.sourceHandle = source;
    send(new Ev(E_SINK_SOURCE_CHANGED, s.name).sink(sink).source(source));
  }

  public static synchronized int getSinkSourceProperty(int sink, String name) {
    nonNull(name, "name");
    return getSourceProperty(sink(sink).sourceHandle, name);
  }

  public static synchronized int getSinkSource(int sink) {
    return sink(sink).sourceHandle;
  }

  public static synchronized int copySink(int sink) {
    if (sink == 0) {
      return 0;
    }
    sink(sink).refCount++;
    return sink;
  }

  public static synchronized void releaseSink(int sink) {
    if (sink == 0) {
      return;
    }
    if (sink(sink).refCount-- == 0) {
      destroySink(sink);
    }
  }

  // MJPEG server functions

  private static Sink mjpeg(int sink) {
    Sink s = sink(sink);
    if (s.kind != SINK_MJPEG) {
      throw invalidHandle();
    }
    return s;
  }

  public static synchronized String getMjpegServerListenAddress(int sink) {
    return mjpeg(sink).listenAddress;
  }

  public static synchronized int getMjpegServerPort(int sink) {
    return mjpeg(sink).port;
  }

  // Image sink functions

  public static synchronized void setSinkDescription(int sink, String description) {
    nonNull(description, "description");
    imageSink(sink).description = description;
  }

  private static void enableSink(Sink s, boolean enabled) {
    if (enabled && s.enabledCount == 0) {
      if (s.source != null) {
        s.source.numSinksEnabled++;
      }
      s.enabledCount = 1;
      send(new Ev(E_SINK_ENABLED, s.name).sink(s.handle));
    } else if (!enabled && s.enabledCount > 0) {
      if (s.source != null) {
        s.source.numSinksEnabled--;
      }
      s.enabledCount = 0;
      send(new Ev(E_SINK_DISABLED, s.name).sink(s.handle));
    }
  }

  // Frames are never delivered, so a grab only reports why it failed. Returns 0.
  private static long grab(int sinkHandle, double timeoutSeconds, boolean timed) {
    Sink s = imageSink(sinkHandle);
    enableSink(s, true);
    Source src = s.source;
    if (src == null) {
      pause(1000);
      return 0;
    }
    long last = src.frameTime;
    if (timed) {
      long deadline = System.nanoTime() + (long) (timeoutSeconds * 1e9);
      long left;
      while (src.frameTime == last && (left = (deadline - System.nanoTime()) / 1_000_000) > 0) {
        if (!pause(left)) {
          return 0;
        }
      }
      if (src.frameTime == last) {
        src.frameTime = nowMicros();
        src.frameError = "timed out getting frame";
        LOCK.notifyAll();
      }
    } else if (last != 0 && (src.kind & CONFIGURABLE) != 0) {
      // USB and HTTP sources never produce frames, so there is nothing to wait for
      while (src.frameTime == last) {
        if (!pause(SLICE_MS)) {
          return 0;
        }
      }
    }
    pause(20);
    return 0;
  }

  public static synchronized long grabRawSinkFrame(int sink, RawFrame frame, long nativeObj) {
    return grab(sink, 0, false);
  }

  public static synchronized long grabRawSinkFrameTimeout(
      int sink, RawFrame frame, long nativeObj, double timeout) {
    return grab(sink, timeout, true);
  }

  public static synchronized String getSinkError(int sink) {
    Sink s = imageSink(sink);
    return s.source == null ? "no source connected" : s.source.frameError;
  }

  public static synchronized void setSinkEnabled(int sink, boolean enabled) {
    enableSink(imageSink(sink), enabled);
  }

  // Listener functions

  public static synchronized int addListener(
      Consumer<VideoEvent> listener, int eventMask, boolean immediateNotify) {
    nonNull(listener, "listener");
    return addListenerImpl(null, listener, eventMask, immediateNotify);
  }

  public static synchronized void removeListener(int handle) {
    Listener l = lookup(listeners, handle, H_LISTENER);
    if (l == null) {
      throw invalidHandle();
    }
    l.removed = true;
    listeners.set(handle & 0xffff, null);
  }

  public static synchronized int createListenerPoller() {
    return allocate(pollers, new Poller(), H_POLLER);
  }

  public static synchronized void destroyListenerPoller(int poller) {
    Poller p = lookup(pollers, poller, H_POLLER);
    if (p == null) {
      return;
    }
    p.destroyed = true;
    pollers.set(poller & 0xffff, null);
    for (int i = 0; i < listeners.size(); i++) {
      Listener l = listeners.get(i);
      if (l != null && l.poller == p) {
        l.removed = true;
        listeners.set(i, null);
      }
    }
    LOCK.notifyAll();
  }

  public static synchronized int addPolledListener(int poller, int eventMask, boolean immediateNotify) {
    Poller p = lookup(pollers, poller, H_POLLER);
    if (p == null) {
      throw invalidHandle();
    }
    return addListenerImpl(p, null, eventMask, immediateNotify);
  }

  public static synchronized VideoEvent[] pollListener(int poller) throws InterruptedException {
    return poll(poller, -1);
  }

  public static synchronized VideoEvent[] pollListenerTimeout(int poller, double timeout)
      throws InterruptedException {
    return poll(poller, timeout);
  }

  // timeout < 0 waits indefinitely
  private static VideoEvent[] poll(int poller, double timeout) throws InterruptedException {
    Poller p = lookup(pollers, poller, H_POLLER);
    if (p == null) {
      throw new InterruptedException("PollListener interrupted");
    }
    long deadline = System.nanoTime() + (long) (Math.max(timeout, 0) * 1e9);
    while (true) {
      if (p.destroyed) {
        throw new InterruptedException("PollListener interrupted");
      }
      if (!p.queue.isEmpty()) {
        return toVideoEvents(p.queue);
      }
      if (p.canceled) {
        p.canceled = false;
        throw new InterruptedException("PollListener interrupted");
      }
      long wait = SLICE_MS;
      if (timeout >= 0) {
        long left = (deadline - System.nanoTime()) / 1_000_000;
        if (left <= 0) {
          return new VideoEvent[0];
        }
        wait = Math.min(wait, left);
      }
      LOCK.wait(wait);
    }
  }

  public static synchronized void cancelPollListener(int poller) {
    Poller p = lookup(pollers, poller, H_POLLER);
    if (p != null) {
      p.canceled = true;
      LOCK.notifyAll();
    }
  }

  // Telemetry functions

  private static void telemetryTick() {
    if (!telemetryOn) {
      return;
    }
    long now = System.nanoTime();
    double period = telemetryPeriod == 0 ? 1000.0 : telemetryPeriod;
    double sinceLast = (now - telemetryPrevNanos) / 1e9;
    if (sinceLast >= period) {
      telemetryUser = telemetryCurrent;
      telemetryCurrent = new HashMap<>();
      telemetryElapsed = sinceLast;
      telemetryPrevNanos = now;
    }
  }

  private static void telemetryRecord(int source, int kind, int quantity) {
    if (telemetryOn) {
      telemetryTick();
      telemetryCurrent.merge(((long) source << 8) | kind, (long) quantity, Long::sum);
    }
  }

  private static long telemetryValue(int handle, int kind) {
    if (!telemetryOn) {
      throw ex("telemetry not enabled");
    }
    Long v = telemetryUser.get(((long) handle << 8) | kind);
    if (v == null) {
      throw ex("empty value");
    }
    return v;
  }

  public static synchronized void setTelemetryPeriod(double seconds) {
    if (!telemetryOn) {
      telemetryOn = true;
      telemetryPrevNanos = System.nanoTime();
    }
    telemetryTick();
    telemetryPeriod = seconds;
  }

  public static synchronized double getTelemetryElapsedTime() {
    telemetryTick();
    return telemetryElapsed;
  }

  public static synchronized long getTelemetryValue(int handle, int kind) {
    telemetryTick();
    return telemetryValue(handle, kind);
  }

  public static synchronized double getTelemetryAverageValue(int handle, int kind) {
    if (!telemetryOn) {
      throw ex("telemetry not enabled");
    }
    telemetryTick();
    if (telemetryElapsed == 0) {
      return 0.0;
    }
    return telemetryValue(handle, kind) / telemetryElapsed;
  }

  // Logging functions

  public static synchronized void setLogger(LoggerFunction func, int minLevel) {
    nonNull(func, "func");
  }

  // Utility functions

  public static synchronized UsbCameraInfo[] enumerateUsbCameras() {
    return new UsbCameraInfo[0];
  }

  public static synchronized int[] enumerateSources() {
    return handles(sources, H_SOURCE);
  }

  public static synchronized int[] enumerateSinks() {
    return handles(sinks, H_SINK);
  }

  public static String getHostname() {
    return "localhost";
  }

  public static String[] getNetworkInterfaces() {
    return new String[0];
  }

  public static synchronized void runMainRunLoop() {
    while (!runLoopSignaled) {
      if (!pause(SLICE_MS)) {
        return;
      }
    }
    runLoopSignaled = false;
  }

  public static synchronized int runMainRunLoopTimeout(double timeoutSeconds) {
    long deadline = System.nanoTime() + (long) (Math.max(timeoutSeconds, 0) * 1e9);
    while (!runLoopSignaled) {
      long left = (deadline - System.nanoTime()) / 1_000_000;
      if (left <= 0) {
        return 3;
      }
      if (!pause(left)) {
        return 1;
      }
    }
    runLoopSignaled = false;
    return 2;
  }

  public static synchronized void stopMainRunLoop() {
    runLoopSignaled = true;
    LOCK.notifyAll();
  }
}
