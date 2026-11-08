package dev.project516.frcdevwasm.shim.test;

import edu.wpi.first.cscore.CameraServerJNI;
import edu.wpi.first.cscore.CvSource;
import edu.wpi.first.cscore.OpenCvLoader;
import edu.wpi.first.cscore.UsbCamera;
import edu.wpi.first.cscore.VideoException;
import edu.wpi.first.cscore.VideoSink;
import edu.wpi.first.cscore.VideoSource;
import edu.wpi.first.util.PixelFormat;

public final class CsMiscTest {
  public static void testHardwareBoundCallsReturnEmpty() {
    T.eq(0, UsbCamera.enumerateUsbCameras().length);
    T.eq(0, CameraServerJNI.getNetworkInterfaces().length);
    T.check(!CameraServerJNI.getHostname().isEmpty(), "hostname");
    CameraServerJNI.setLogger((level, file, line, msg) -> {}, 20);
    T.throwsA(NullPointerException.class, () -> CameraServerJNI.setLogger(null, 20));
  }

  public static void testEnumerateSourcesAndSinks() {
    int before = VideoSource.enumerateSources().length;
    int sinksBefore = VideoSink.enumerateSinks().length;
    try (UsbCamera a = new UsbCamera("enum-a", 0);
        UsbCamera b = new UsbCamera("enum-b", 1)) {
      VideoSource[] all = VideoSource.enumerateSources();
      T.eq(before + 2, all.length);
      boolean foundA = false;
      for (VideoSource s : all) {
        foundA |= s.getName().equals("enum-a");
      }
      T.check(foundA, "enum-a listed");
    }
    T.eq(before, VideoSource.enumerateSources().length);
    T.eq(sinksBefore, VideoSink.enumerateSinks().length);
  }

  public static void testTelemetry() throws Exception {
    OpenCvLoader.Helper.setExtractOnStaticLoad(false);
    try (CvSource src = new CvSource("tele", PixelFormat.kBGR, 2, 2, 30);
        CvSource idle = new CvSource("tele-idle", PixelFormat.kBGR, 2, 2, 30)) {
      T.eq("telemetry not enabled", T.throwsA(VideoException.class, src::getActualFPS).getMessage());
      T.eq("telemetry not enabled", T.throwsA(VideoException.class, () -> CameraServerJNI.getTelemetryValue(src.getHandle(), 2)).getMessage());
      CameraServerJNI.setTelemetryPeriod(0.1);
      T.eq(0.0, src.getActualFPS());
      java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(12);
      for (int i = 0; i < 3; i++) {
        CameraServerJNI.putRawSourceFrameBB(src.getHandle(), data, 12, 2, 2, 6, 4);
      }
      Thread.sleep(150);
      T.eq(3L, CameraServerJNI.getTelemetryValue(src.getHandle(), CameraServerJNI.TelemetryKind.kSourceFramesReceived.getValue()));
      T.eq(36L, CameraServerJNI.getTelemetryValue(src.getHandle(), CameraServerJNI.TelemetryKind.kSourceBytesReceived));
      T.check(CameraServerJNI.getTelemetryElapsedTime() >= 0.1, "elapsed");
      T.check(src.getActualFPS() > 5, "fps " + src.getActualFPS());
      T.eq("empty value", T.throwsA(VideoException.class, idle::getActualFPS).getMessage());
    }
  }

  public static void testMainRunLoop() throws Exception {
    CameraServerJNI.stopMainRunLoop();
    T.eq(2, CameraServerJNI.runMainRunLoopTimeout(0.05));
    T.eq(3, CameraServerJNI.runMainRunLoopTimeout(0.05));
    Thread t = new Thread(CameraServerJNI::runMainRunLoop);
    t.setDaemon(true);
    t.start();
    Thread.sleep(100);
    T.check(t.isAlive(), "blocks");
    CameraServerJNI.stopMainRunLoop();
    t.join(3000);
    T.check(!t.isAlive(), "stop wakes the loop");
  }
}
