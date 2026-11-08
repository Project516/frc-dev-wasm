package dev.project516.frcdevwasm.shim.test;

import edu.wpi.first.cscore.CameraServerJNI;
import edu.wpi.first.cscore.MjpegServer;
import edu.wpi.first.cscore.UsbCamera;
import edu.wpi.first.cscore.VideoException;
import edu.wpi.first.cscore.VideoProperty;
import edu.wpi.first.cscore.VideoSink;
import edu.wpi.first.cscore.VideoSource;

public final class CsSinkTest {
  private static boolean listed(VideoSink s) {
    for (VideoSink other : VideoSink.enumerateSinks()) {
      if (other.getHandle() == s.getHandle()) {
        return true;
      }
    }
    return false;
  }

  public static void testMjpegServer() {
    try (MjpegServer srv = new MjpegServer("srv-a", 1181)) {
      T.eq(VideoSink.Kind.kMjpeg, srv.getKind());
      T.eq("srv-a", srv.getName());
      T.eq(1181, srv.getPort());
      T.eq("", srv.getListenAddress());
      T.eq("HTTP Server on port 1181", srv.getDescription());
      T.eq(5, srv.getHandle() >> 24);
      T.check(listed(srv), "listed");
    }
    try (MjpegServer srv = new MjpegServer("srv-b", "127.0.0.1", 1182)) {
      T.eq("127.0.0.1", srv.getListenAddress());
    }
  }

  public static void testMjpegServerProperties() {
    try (MjpegServer srv = new MjpegServer("srv-c", 1183)) {
      VideoProperty[] props = srv.enumerateProperties();
      String[] names = new String[props.length];
      for (int i = 0; i < props.length; i++) {
        names[i] = props[i].getName();
        T.eq(VideoProperty.Kind.kInteger, props[i].getKind());
      }
      T.eq(new String[] {"width", "height", "compression", "default_compression", "fps"}, names);
      T.eq(80, srv.getProperty("default_compression").get());
      T.eq(-1, srv.getProperty("compression").get());
      srv.setCompression(50);
      T.eq(50, srv.getProperty("compression").get());
      srv.setCompression(500);
      T.eq(100, srv.getProperty("compression").get());
      srv.setResolution(640, 480);
      T.eq(640, srv.getProperty("width").get());
      T.eq(480, srv.getProperty("height").get());
      srv.setFPS(10);
      T.eq(10, srv.getProperty("fps").get());
    }
  }

  public static void testMjpegServerConfigJson() {
    try (MjpegServer srv = new MjpegServer("srv-d", 1184)) {
      T.check(srv.setConfigJson("{\"properties\": [{\"name\": \"fps\", \"value\": 12}, {\"name\": \"compression\", \"value\": 30}]}"), "apply");
      T.eq(12, srv.getProperty("fps").get());
      T.eq(30, srv.getProperty("compression").get());
      String json = srv.getConfigJson();
      T.check(json.contains("\"name\": \"fps\""), json);
      T.check(json.contains("\"value\": 12"), json);
      T.eq("property write failed", T.throwsA(VideoException.class, () -> srv.setConfigJson("[")).getMessage());
    }
  }

  public static void testMjpegServerIsNotAnImageSink() {
    try (MjpegServer srv = new MjpegServer("srv-e", 1185)) {
      int h = srv.getHandle();
      T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.setSinkEnabled(h, true)).getMessage());
      T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.setSinkDescription(h, "x")).getMessage());
      T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.getSinkError(h)).getMessage());
    }
    try (UsbCamera cam = new UsbCamera("usb-sink", 0)) {
      int cs = CameraServerJNI.createRawSink("raw-a", false);
      try {
        T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.getMjpegServerPort(cs)).getMessage());
      } finally {
        CameraServerJNI.releaseSink(cs);
      }
    }
  }

  public static void testSinkSourceTracking() {
    try (UsbCamera cam = new UsbCamera("usb-s1", 0);
        UsbCamera other = new UsbCamera("usb-s2", 1);
        MjpegServer a = new MjpegServer("srv-f", 1186);
        MjpegServer b = new MjpegServer("srv-g", 1187)) {
      T.eq(0, a.getSource().getHandle());
      a.setSource(cam);
      b.setSource(cam);
      T.eq(cam.getHandle(), a.getSource().getHandle());
      T.eq(2, cam.enumerateSinks().length);
      T.eq(0, other.enumerateSinks().length);
      b.setSource(other);
      T.eq(1, cam.enumerateSinks().length);
      T.eq(a.getHandle(), cam.enumerateSinks()[0].getHandle());
      a.setSource(null);
      T.eq(0, a.getSource().getHandle());
      T.eq(0, cam.enumerateSinks().length);
    }
  }

  public static void testSinkSourceProperty() {
    try (UsbCamera cam = new UsbCamera("usb-s3", 0);
        MjpegServer srv = new MjpegServer("srv-h", 1188)) {
      T.throwsA(VideoException.class, () -> srv.getSourceProperty("x"));
      srv.setSource(cam);
      T.eq(VideoProperty.Kind.kNone, srv.getSourceProperty("x").getKind());
    }
  }

  public static void testSetSourceRejectsStaleHandle() {
    try (MjpegServer srv = new MjpegServer("srv-i", 1189)) {
      T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.setSinkSource(srv.getHandle(), 0x04000063)).getMessage());
    }
  }

  public static void testSinkEnableMakesSourceEnabled() {
    try (UsbCamera cam = new UsbCamera("usb-s4", 0)) {
      int sink = CameraServerJNI.createRawSink("raw-b", false);
      try {
        CameraServerJNI.setSinkSource(sink, cam.getHandle());
        T.check(!cam.isEnabled(), "before enable");
        CameraServerJNI.setSinkEnabled(sink, true);
        T.check(cam.isEnabled(), "after enable");
        CameraServerJNI.setSinkEnabled(sink, true);
        CameraServerJNI.setSinkEnabled(sink, false);
        T.check(!cam.isEnabled(), "after disable");
        CameraServerJNI.setSinkEnabled(sink, true);
        CameraServerJNI.setSinkSource(sink, 0);
        T.check(!cam.isEnabled(), "after detach");
        CameraServerJNI.setSinkSource(sink, cam.getHandle());
        T.check(cam.isEnabled(), "after reattach");
      } finally {
        CameraServerJNI.releaseSink(sink);
      }
      T.check(!cam.isEnabled(), "after sink released");
    }
  }

  public static void testImageSinkDescriptionAndKind() {
    int cv = CameraServerJNI.createRawSink("cv-sink", true);
    int raw = CameraServerJNI.createRawSink("raw-sink", false);
    try {
      T.eq(4, CameraServerJNI.getSinkKind(cv));
      T.eq(8, CameraServerJNI.getSinkKind(raw));
      T.eq("", CameraServerJNI.getSinkDescription(cv));
      CameraServerJNI.setSinkDescription(cv, "OpenCV Sink");
      T.eq("OpenCV Sink", CameraServerJNI.getSinkDescription(cv));
      T.eq(0, CameraServerJNI.enumerateSinkProperties(cv).length);
      T.eq("no source connected", CameraServerJNI.getSinkError(cv));
    } finally {
      CameraServerJNI.releaseSink(cv);
      CameraServerJNI.releaseSink(raw);
    }
  }

  public static void testGrabWithoutFramesFailsAndExplainsWhy() {
    try (UsbCamera cam = new UsbCamera("usb-s5", 0)) {
      int sink = CameraServerJNI.createRawSink("raw-c", false);
      try {
        CameraServerJNI.setSinkSource(sink, cam.getHandle());
        long start = System.nanoTime();
        T.eq(0L, CameraServerJNI.grabRawSinkFrameTimeout(sink, null, 0, 0.1));
        long ms = (System.nanoTime() - start) / 1_000_000;
        T.check(ms >= 90 && ms < 1500, "waited about the timeout, took " + ms);
        T.eq("timed out getting frame", CameraServerJNI.getSinkError(sink));
        T.check(cam.getLastFrameTime() > 0, "timeout stamps the frame");
        T.check(cam.isEnabled(), "grabbing enables the sink");
        T.eq(0L, CameraServerJNI.grabRawSinkFrame(sink, null, 0));
      } finally {
        CameraServerJNI.releaseSink(sink);
      }
    }
  }

  public static void testGrabWithoutSourceFails() {
    int sink = CameraServerJNI.createRawSink("raw-d", false);
    try {
      T.eq(0L, CameraServerJNI.grabRawSinkFrameTimeout(sink, null, 0, 0.1));
    } finally {
      CameraServerJNI.releaseSink(sink);
    }
  }

  public static void testGrabIsInterruptible() {
    int src = CameraServerJNI.createRawSource("cv-grab", true, 4, 2, 2, 30);
    int sink = CameraServerJNI.createRawSink("raw-e", false);
    try {
      CameraServerJNI.setSinkSource(sink, src);
      java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(12);
      CameraServerJNI.putRawSourceFrameBB(src, data, 12, 2, 2, 6, 4);
      long[] result = {-1};
      Thread t = new Thread(() -> result[0] = CameraServerJNI.grabRawSinkFrame(sink, null, 0));
      t.setDaemon(true);
      t.start();
      Thread.sleep(100);
      T.check(t.isAlive(), "blocks for the next frame");
      t.interrupt();
      t.join(3000);
      T.check(!t.isAlive(), "interrupt wakes the grab");
      T.eq(0L, result[0]);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    } finally {
      CameraServerJNI.releaseSink(sink);
      CameraServerJNI.releaseSource(src);
    }
  }

  public static void testSinkCopyAndRelease() {
    int sink = CameraServerJNI.createRawSink("raw-f", false);
    T.eq(sink, CameraServerJNI.copySink(sink));
    CameraServerJNI.releaseSink(sink);
    T.eq("raw-f", CameraServerJNI.getSinkName(sink));
    CameraServerJNI.releaseSink(sink);
    T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.getSinkName(sink)).getMessage());
    T.eq(0, CameraServerJNI.copySink(0));
    CameraServerJNI.releaseSink(0);
  }

  public static void testSourceKindsViaVideoSource() {
    try (UsbCamera cam = new UsbCamera("usb-s6", 0);
        MjpegServer srv = new MjpegServer("srv-j", 1190)) {
      srv.setSource(cam);
      VideoSource s = srv.getSource();
      T.eq(VideoSource.Kind.kUsb, s.getKind());
      T.eq("usb-s6", s.getName());
      T.eq(cam.getHandle(), s.getHandle());
    }
  }
}
