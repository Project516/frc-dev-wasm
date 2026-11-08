package dev.project516.frcdevwasm.shim.test;

import edu.wpi.first.cscore.CameraServerJNI;
import edu.wpi.first.cscore.CvSource;
import edu.wpi.first.cscore.MjpegServer;
import edu.wpi.first.cscore.OpenCvLoader;
import edu.wpi.first.cscore.UsbCamera;
import edu.wpi.first.cscore.VideoEvent;
import edu.wpi.first.cscore.VideoListener;
import edu.wpi.first.cscore.VideoProperty;
import edu.wpi.first.util.PixelFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class CsListenerTest {
  private static final int ALL = 0xfffff;

  private static VideoEvent[] poll(int poller) throws InterruptedException {
    return CameraServerJNI.pollListenerTimeout(poller, 2.0);
  }

  public static void testSourceCreatedAndDestroyed() throws Exception {
    int poller = CameraServerJNI.createListenerPoller();
    int listener = CameraServerJNI.addPolledListener(poller, ALL, false);
    try {
      UsbCamera cam = new UsbCamera("ev-a", 0);
      VideoEvent[] ev = poll(poller);
      T.eq(1, ev.length);
      T.eq(VideoEvent.Kind.kSourceCreated, ev[0].kind);
      T.eq("ev-a", ev[0].name);
      T.eq(cam.getHandle(), ev[0].sourceHandle);
      T.eq(listener, ev[0].listener);
      int handle = cam.getHandle();
      cam.close();
      ev = poll(poller);
      T.eq(1, ev.length);
      T.eq(VideoEvent.Kind.kSourceDestroyed, ev[0].kind);
      T.eq(handle, ev[0].sourceHandle);
    } finally {
      CameraServerJNI.destroyListenerPoller(poller);
    }
  }

  public static void testCvSourceEventOrder() throws Exception {
    OpenCvLoader.Helper.setExtractOnStaticLoad(false);
    int poller = CameraServerJNI.createListenerPoller();
    CameraServerJNI.addPolledListener(poller, ALL, false);
    try (CvSource src = new CvSource("ev-b", PixelFormat.kBGR, 64, 48, 10)) {
      VideoEvent[] ev = poll(poller);
      T.eq(4, ev.length);
      T.eq(VideoEvent.Kind.kSourceCreated, ev[0].kind);
      T.eq(VideoEvent.Kind.kSourceConnected, ev[1].kind);
      T.eq(VideoEvent.Kind.kSourceVideoModesUpdated, ev[2].kind);
      T.eq(VideoEvent.Kind.kSourceVideoModeChanged, ev[3].kind);
      T.eq(64, ev[3].mode.width);
      T.eq(48, ev[3].mode.height);
      T.eq(10, ev[3].mode.fps);
      T.eq(PixelFormat.kBGR, ev[3].mode.pixelFormat);
      src.setConnected(true);
      src.setConnected(true);
      src.setConnected(false);
      ev = poll(poller);
      T.eq(2, ev.length);
      T.eq(VideoEvent.Kind.kSourceConnected, ev[0].kind);
      T.eq(VideoEvent.Kind.kSourceDisconnected, ev[1].kind);
    } finally {
      CameraServerJNI.destroyListenerPoller(poller);
    }
  }

  public static void testPropertyEvents() throws Exception {
    OpenCvLoader.Helper.setExtractOnStaticLoad(false);
    try (CvSource src = new CvSource("ev-c", PixelFormat.kBGR, 64, 48, 10)) {
      int poller = CameraServerJNI.createListenerPoller();
      CameraServerJNI.addPolledListener(
          poller,
          VideoEvent.Kind.kSourcePropertyCreated.getValue()
              | VideoEvent.Kind.kSourcePropertyValueUpdated.getValue()
              | VideoEvent.Kind.kSourcePropertyChoicesUpdated.getValue(),
          false);
      try {
        VideoProperty gain = src.createIntegerProperty("gain", 0, 10, 1, 5, 7);
        VideoEvent[] ev = poll(poller);
        T.eq(1, ev.length);
        T.eq(VideoEvent.Kind.kSourcePropertyCreated, ev[0].kind);
        T.eq("gain", ev[0].name);
        T.eq(VideoProperty.Kind.kInteger, ev[0].propertyKind);
        T.eq(7, ev[0].value);
        T.eq(src.getHandle(), ev[0].sourceHandle);
        gain.set(9);
        ev = poll(poller);
        T.eq(1, ev.length);
        T.eq(VideoEvent.Kind.kSourcePropertyValueUpdated, ev[0].kind);
        T.eq(9, ev[0].value);
        T.eq(gain.getName(), ev[0].getProperty().getName());
        VideoProperty mode = src.createProperty("mode", VideoProperty.Kind.kEnum, 0, 1, 1, 0, 0);
        src.setEnumPropertyChoices(mode, new String[] {"a", "b"});
        ev = poll(poller);
        T.eq(2, ev.length);
        T.eq(VideoEvent.Kind.kSourcePropertyCreated, ev[0].kind);
        T.eq(VideoEvent.Kind.kSourcePropertyChoicesUpdated, ev[1].kind);
      } finally {
        CameraServerJNI.destroyListenerPoller(poller);
      }
    }
  }

  public static void testSinkEvents() throws Exception {
    int poller = CameraServerJNI.createListenerPoller();
    CameraServerJNI.addPolledListener(poller, ALL, false);
    try (UsbCamera cam = new UsbCamera("ev-d", 0)) {
      poll(poller);
      int sink = CameraServerJNI.createRawSink("ev-sink", false);
      VideoEvent[] ev = poll(poller);
      T.eq(1, ev.length);
      T.eq(VideoEvent.Kind.kSinkCreated, ev[0].kind);
      T.eq(sink, ev[0].sinkHandle);
      T.eq("ev-sink", ev[0].name);
      CameraServerJNI.setSinkSource(sink, cam.getHandle());
      CameraServerJNI.setSinkEnabled(sink, true);
      CameraServerJNI.setSinkEnabled(sink, false);
      CameraServerJNI.releaseSink(sink);
      ev = poll(poller);
      T.eq(4, ev.length);
      T.eq(VideoEvent.Kind.kSinkSourceChanged, ev[0].kind);
      T.eq(sink, ev[0].sinkHandle);
      T.eq(cam.getHandle(), ev[0].sourceHandle);
      T.eq(VideoEvent.Kind.kSinkEnabled, ev[1].kind);
      T.eq(VideoEvent.Kind.kSinkDisabled, ev[2].kind);
      T.eq(VideoEvent.Kind.kSinkDestroyed, ev[3].kind);
    } finally {
      CameraServerJNI.destroyListenerPoller(poller);
    }
  }

  public static void testSinkPropertyEvents() throws Exception {
    int poller = CameraServerJNI.createListenerPoller();
    CameraServerJNI.addPolledListener(poller, VideoEvent.Kind.kSinkPropertyValueUpdated.getValue(), false);
    try (MjpegServer srv = new MjpegServer("ev-srv", 1191)) {
      VideoProperty fps = srv.getProperty("fps");
      fps.set(5);
      VideoEvent[] ev = poll(poller);
      T.eq(1, ev.length);
      T.eq(VideoEvent.Kind.kSinkPropertyValueUpdated, ev[0].kind);
      T.eq(5, ev[0].value);
      T.eq(srv.getHandle(), ev[0].sinkHandle);
    } finally {
      CameraServerJNI.destroyListenerPoller(poller);
    }
  }

  public static void testMaskFiltersEvents() throws Exception {
    int poller = CameraServerJNI.createListenerPoller();
    CameraServerJNI.addPolledListener(poller, VideoEvent.Kind.kSourceDestroyed.getValue(), false);
    try {
      new UsbCamera("ev-e", 0).close();
      VideoEvent[] ev = poll(poller);
      T.eq(1, ev.length);
      T.eq(VideoEvent.Kind.kSourceDestroyed, ev[0].kind);
    } finally {
      CameraServerJNI.destroyListenerPoller(poller);
    }
  }

  public static void testImmediateNotifyForNetworkAndUsb() throws Exception {
    int poller = CameraServerJNI.createListenerPoller();
    CameraServerJNI.addPolledListener(poller, VideoEvent.Kind.kNetworkInterfacesChanged.getValue(), true);
    try {
      VideoEvent[] ev = poll(poller);
      T.eq(1, ev.length);
      T.eq(VideoEvent.Kind.kNetworkInterfacesChanged, ev[0].kind);
    } finally {
      CameraServerJNI.destroyListenerPoller(poller);
    }
  }

  public static void testPollTimeoutReturnsEmpty() throws Exception {
    int poller = CameraServerJNI.createListenerPoller();
    try {
      long start = System.nanoTime();
      VideoEvent[] ev = CameraServerJNI.pollListenerTimeout(poller, 0.1);
      long ms = (System.nanoTime() - start) / 1_000_000;
      T.eq(0, ev.length);
      T.check(ms >= 90 && ms < 1500, "timeout took " + ms);
    } finally {
      CameraServerJNI.destroyListenerPoller(poller);
    }
  }

  public static void testCancelAndDestroyWakePoll() throws Exception {
    int poller = CameraServerJNI.createListenerPoller();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread t = new Thread(() -> {
      try {
        CameraServerJNI.pollListener(poller);
      } catch (Throwable e) {
        thrown.set(e);
      }
    });
    t.setDaemon(true);
    t.start();
    Thread.sleep(100);
    T.check(t.isAlive(), "blocks");
    CameraServerJNI.cancelPollListener(poller);
    t.join(3000);
    T.check(!t.isAlive(), "cancel wakes the poll");
    T.check(thrown.get() instanceof InterruptedException, "cancel throws InterruptedException, got " + thrown.get());

    thrown.set(null);
    Thread t2 = new Thread(() -> {
      try {
        CameraServerJNI.pollListener(poller);
      } catch (Throwable e) {
        thrown.set(e);
      }
    });
    t2.setDaemon(true);
    t2.start();
    Thread.sleep(100);
    CameraServerJNI.destroyListenerPoller(poller);
    t2.join(3000);
    T.check(!t2.isAlive(), "destroy wakes the poll");
    T.check(thrown.get() instanceof InterruptedException, "destroy throws InterruptedException");
    boolean threw = false;
    try {
      CameraServerJNI.pollListener(poller);
    } catch (InterruptedException e) {
      threw = true;
    }
    T.check(threw, "polling a destroyed poller throws");
  }

  public static void testInterruptedThreadLeavesPoll() throws Exception {
    int poller = CameraServerJNI.createListenerPoller();
    try {
      Thread.currentThread().interrupt();
      boolean threw = false;
      try {
        CameraServerJNI.pollListener(poller);
      } catch (InterruptedException e) {
        threw = true;
      }
      T.check(threw, "interrupt");
    } finally {
      Thread.interrupted();
      CameraServerJNI.destroyListenerPoller(poller);
    }
  }

  public static void testCallbackListener() throws Exception {
    List<VideoEvent> seen = new CopyOnWriteArrayList<>();
    int handle = CameraServerJNI.addListener(seen::add, VideoEvent.Kind.kSourceCreated.getValue(), false);
    try {
      UsbCamera cam = new UsbCamera("ev-f", 0);
      T.await(() -> !seen.isEmpty(), "callback");
      T.eq("ev-f", seen.get(0).name);
      T.eq(handle, seen.get(0).listener);
      cam.close();
      CameraServerJNI.removeListener(handle);
      new UsbCamera("ev-g", 0).close();
      Thread.sleep(100);
      T.eq(1, seen.size());
    } finally {
      T.throwsA(edu.wpi.first.cscore.VideoException.class, () -> CameraServerJNI.removeListener(handle));
    }
  }

  public static void testCallbackExceptionDoesNotKillListenerThread() throws Exception {
    List<String> seen = new CopyOnWriteArrayList<>();
    int handle = CameraServerJNI.addListener(e -> {
      seen.add(e.name);
      if (e.name.equals("ev-h")) {
        throw new IllegalStateException("expected by test");
      }
    }, VideoEvent.Kind.kSourceCreated.getValue(), false);
    try {
      new UsbCamera("ev-h", 0).close();
      new UsbCamera("ev-i", 0).close();
      T.await(() -> seen.contains("ev-i"), "second event");
    } finally {
      CameraServerJNI.removeListener(handle);
    }
  }

  public static void testVideoListenerClass() throws Exception {
    List<VideoEvent> seen = new CopyOnWriteArrayList<>();
    try (VideoListener l = new VideoListener(seen::add, VideoEvent.Kind.kSourceCreated.getValue() | VideoEvent.Kind.kSinkCreated.getValue(), false)) {
      T.check(l.isValid(), "valid");
      UsbCamera cam = new UsbCamera("ev-j", 0);
      MjpegServer srv = new MjpegServer("ev-k", 1192);
      T.await(() -> seen.size() >= 2, "both events");
      T.eq(VideoEvent.Kind.kSourceCreated, seen.get(0).kind);
      T.eq(VideoEvent.Kind.kSinkCreated, seen.get(1).kind);
      T.eq("ev-j", seen.get(0).getSource().getName());
      T.eq("ev-k", seen.get(1).getSink().getName());
      srv.close();
      cam.close();
    }
  }

  public static void testPolledListenerOnUnknownPollerThrows() {
    T.eq("invalid handle", T.throwsA(edu.wpi.first.cscore.VideoException.class, () -> CameraServerJNI.addPolledListener(0, ALL, false)).getMessage());
    T.throwsA(NullPointerException.class, () -> CameraServerJNI.addListener(null, ALL, false));
  }
}
