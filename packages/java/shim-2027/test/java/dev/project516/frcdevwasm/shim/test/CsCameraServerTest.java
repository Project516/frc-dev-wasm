package dev.project516.frcdevwasm.shim.test;

import org.wpilib.vision.stream.CameraServer;
import org.wpilib.vision.camera.CvSource;
import org.wpilib.vision.camera.HttpCamera;
import org.wpilib.vision.camera.MjpegServer;
import org.wpilib.vision.camera.OpenCvLoader;
import org.wpilib.vision.camera.UsbCamera;
import org.wpilib.vision.camera.VideoMode;
import org.wpilib.vision.camera.VideoSink;
import org.wpilib.vision.camera.VideoSource;
import org.wpilib.networktables.NetworkTable;
import org.wpilib.networktables.NetworkTableEntry;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.util.PixelFormat;

/** Drives the unmodified WPILib CameraServer on top of the cscore and NetworkTables replacements. */
public final class CsCameraServerTest {
  private static NetworkTable publisher() {
    return NetworkTableInstance.getDefault().getTable("CameraPublisher");
  }

  public static void testStartAutomaticCaptureWithoutACamera() {
    UsbCamera cam = CameraServer.startAutomaticCapture();
    T.eq("USB Camera 0", cam.getName());
    T.eq("/dev/video0", cam.getPath());
    T.check(!cam.isConnected(), "connected");
    VideoSink server = CameraServer.getServer();
    T.eq(VideoSink.Kind.MJPEG, server.getKind());
    T.eq("serve_USB Camera 0", server.getName());
    T.eq(cam.getHandle(), server.getSource().getHandle());
    T.eq(1181, ((MjpegServer) server).getPort());

    T.await(() -> publisher().containsSubTable("USB Camera 0"), "publisher table");
    NetworkTable t = publisher().getSubTable("USB Camera 0");
    // The topics exist before the listener thread sets their values; streams is set last.
    T.await(() -> t.getEntry("streams").getStringArray(new String[0]).length > 0, "streams entry");
    T.eq("usb:/dev/video0", t.getEntry("source").getString(""));
    T.eq(false, t.getEntry("connected").getBoolean(true));
    String[] streams = t.getEntry("streams").getStringArray(new String[0]);
    T.check(streams.length > 0 && streams[0].startsWith("mjpg:http://"), "streams " + String.join(",", streams));
    T.check(streams[0].endsWith(":1181/?action=stream"), "stream port " + streams[0]);
  }

  public static void testStartAutomaticCaptureNamedAndByPath() {
    UsbCamera byPath = CameraServer.startAutomaticCapture("front", "/dev/video7");
    T.eq("front", byPath.getName());
    T.eq("/dev/video7", byPath.getPath());
    T.eq(byPath.getHandle(), CameraServer.getServer("serve_front").getSource().getHandle());
    UsbCamera byDev = CameraServer.startAutomaticCapture("rear", 2);
    T.eq("/dev/video2", byDev.getPath());
  }

  public static void testSetResolutionOnADisconnectedCamera() {
    UsbCamera cam = new UsbCamera("res-cam", 1);
    T.check(cam.setResolution(320, 240), "resolution");
    T.check(cam.setFPS(15), "fps");
    CameraServer.addCamera(cam);
    T.await(() -> publisher().containsSubTable("res-cam"), "res-cam published");
  }

  public static void testAddServerAndAddCamera() {
    MjpegServer s = CameraServer.addServer("extra", 1199);
    T.eq(1199, s.getPort());
    T.eq(s.getHandle(), CameraServer.getServer("extra").getHandle());
    UsbCamera cam = new UsbCamera("added", 3);
    CameraServer.addCamera(cam);
    T.await(() -> publisher().containsSubTable("added"), "added camera published");
    CameraServer.addServer(new MjpegServer("extra2", 1198));
    T.eq("extra2", CameraServer.getServer("extra2").getName());
  }

  public static void testAddHttpCameras() {
    HttpCamera http = new HttpCamera("lime", "http://limelight.local:5800");
    CameraServer.addCamera(http);
    NetworkTableEntry source = publisher().getSubTable("lime").getEntry("source");
    T.await(() -> !source.getString("").isEmpty(), "http source value");
    T.eq("ip:http://limelight.local:5800", source.getString(""));
  }

  public static void testPutVideo() {
    OpenCvLoader.Helper.setExtractOnStaticLoad(false);
    CvSource out = CameraServer.putVideo("processed", 160, 120);
    T.eq(VideoSource.Kind.CV, out.getKind());
    T.eq(new VideoMode(PixelFormat.MJPEG, 160, 120, 30), out.getVideoMode());
    T.eq(VideoSink.Kind.MJPEG, CameraServer.getServer("serve_processed").getKind());

    T.await(() -> publisher().containsSubTable("processed"), "processed published");
  }

  public static void testGetServerOfUnknownNameIsNull() {
    T.eq(null, CameraServer.getServer("does-not-exist"));
  }
}
