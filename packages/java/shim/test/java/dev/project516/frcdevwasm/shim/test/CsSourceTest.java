package dev.project516.frcdevwasm.shim.test;

import edu.wpi.first.cscore.CameraServerJNI;
import edu.wpi.first.cscore.CvSource;
import edu.wpi.first.cscore.HttpCamera;
import edu.wpi.first.cscore.HttpCamera.HttpCameraKind;
import edu.wpi.first.cscore.OpenCvLoader;
import edu.wpi.first.cscore.UsbCamera;
import edu.wpi.first.cscore.UsbCameraInfo;
import edu.wpi.first.cscore.VideoException;
import edu.wpi.first.cscore.VideoMode;
import edu.wpi.first.cscore.VideoProperty;
import edu.wpi.first.cscore.VideoSource;
import edu.wpi.first.util.PixelFormat;

public final class CsSourceTest {
  private static CvSource newCvSource(String name) {
    OpenCvLoader.Helper.setExtractOnStaticLoad(false);
    return new CvSource(name, PixelFormat.kBGR, 320, 240, 30);
  }

  private static boolean listed(VideoSource s) {
    for (VideoSource other : VideoSource.enumerateSources()) {
      if (other.getHandle() == s.getHandle()) {
        return true;
      }
    }
    return false;
  }

  public static void testUsbCameraIsDisconnected() {
    try (UsbCamera cam = new UsbCamera("usb-a", 3)) {
      T.check(cam.isValid(), "valid");
      T.eq(VideoSource.Kind.kUsb, cam.getKind());
      T.eq("usb-a", cam.getName());
      T.eq("/dev/video3", cam.getPath());
      T.check(!cam.isConnected(), "connected");
      T.check(!cam.isEnabled(), "enabled");
      T.eq(0L, cam.getLastFrameTime());
      UsbCameraInfo info = cam.getInfo();
      T.eq(3, info.dev);
      T.eq("/dev/video3", info.path);
      T.check(listed(cam), "listed");
    }
  }

  public static void testUsbCameraByPath() {
    try (UsbCamera cam = new UsbCamera("usb-b", "/dev/v4l/by-id/foo")) {
      T.eq("/dev/v4l/by-id/foo", cam.getPath());
      T.eq(-1, cam.getInfo().dev);
    }
  }

  public static void testUsbCameraAcceptsVideoMode() {
    try (UsbCamera cam = new UsbCamera("usb-c", 0)) {
      T.check(cam.setResolution(640, 480), "resolution");
      T.check(cam.setFPS(15), "fps");
      T.check(cam.setPixelFormat(PixelFormat.kMJPEG), "pixel format");
      VideoMode m = cam.getVideoMode();
      T.eq(640, m.width);
      T.eq(480, m.height);
      T.eq(15, m.fps);
      T.eq(PixelFormat.kMJPEG, m.pixelFormat);
      cam.setBrightness(40);
      T.eq(40, cam.getBrightness());
      cam.setExposureAuto();
      cam.setExposureManual(10);
      cam.setExposureHoldCurrent();
      cam.setWhiteBalanceAuto();
      cam.setWhiteBalanceManual(4000);
      cam.setWhiteBalanceHoldCurrent();
      T.eq(0, cam.enumerateVideoModes().length);
    }
  }

  public static void testConnectionStrategyControlsEnabled() {
    try (UsbCamera cam = new UsbCamera("usb-d", 0)) {
      cam.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);
      T.check(cam.isEnabled(), "keep open");
      cam.setConnectionStrategy(VideoSource.ConnectionStrategy.kForceClose);
      T.check(!cam.isEnabled(), "force close");
      cam.setConnectionStrategy(VideoSource.ConnectionStrategy.kAutoManage);
      T.check(!cam.isEnabled(), "auto");
    }
  }

  public static void testUsbCameraPropertiesCreateOnDemand() {
    try (UsbCamera cam = new UsbCamera("usb-e", 0)) {
      T.eq(0, cam.enumerateProperties().length);
      cam.setConnectVerbose(2);
      VideoProperty p = cam.getProperty("connect_verbose");
      T.eq(VideoProperty.Kind.kInteger, p.getKind());
      T.eq(2, p.get());
      T.eq(1, cam.enumerateProperties().length);
    }
  }

  public static void testUsbCameraRejectsHttpCalls() {
    try (UsbCamera cam = new UsbCamera("usb-f", 0)) {
      T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.getHttpCameraKind(cam.getHandle())).getMessage());
    }
  }

  public static void testHttpCamera() {
    try (HttpCamera cam = new HttpCamera("http-a", "http://10.0.0.2:5800/stream.mjpg", HttpCameraKind.kMJPGStreamer)) {
      T.eq(VideoSource.Kind.kHttp, cam.getKind());
      T.eq(HttpCameraKind.kMJPGStreamer, cam.getHttpCameraKind());
      T.eq(new String[] {"http://10.0.0.2:5800/stream.mjpg"}, cam.getUrls());
      T.check(!cam.isConnected(), "connected");
      T.eq("", cam.getDescription());
      T.eq(3, cam.enumerateVideoModes().length);
      cam.setUrls(new String[] {"http://a/", "http://b/"});
      T.eq(2, cam.getUrls().length);
    }
  }

  public static void testHttpCameraNeedsFullModeFirst() {
    try (HttpCamera cam = new HttpCamera("http-b", "http://host/x")) {
      T.check(!cam.setResolution(320, 240), "partial mode before a full one");
      T.check(!cam.setVideoMode(PixelFormat.kBGR, 320, 240, 30), "non-mjpeg");
      T.check(cam.setVideoMode(PixelFormat.kMJPEG, 320, 240, 30), "mjpeg");
      T.check(cam.setResolution(160, 120), "partial mode after");
      T.eq(160, cam.getVideoMode().width);
      cam.setBrightness(10);
      T.eq(0, cam.getBrightness());
    }
  }

  public static void testHttpCameraBadUrls() {
    T.eq("bad URL", T.throwsA(VideoException.class, () -> new HttpCamera("http-c", "ftp://x/")).getMessage());
    T.eq("bad URL", T.throwsA(VideoException.class, () -> new HttpCamera("http-c", "http://:80/")).getMessage());
    T.eq("bad URL", T.throwsA(VideoException.class, () -> new HttpCamera("http-c", "http://host:abc/")).getMessage());
    T.eq("empty value", T.throwsA(VideoException.class, () -> new HttpCamera("http-c", new String[0])).getMessage());
    T.throwsA(NullPointerException.class, () -> CameraServerJNI.createHttpCamera("n", null, 0));
  }

  public static void testAxisCameraHasProperties() {
    try (HttpCamera cam = new HttpCamera("axis", "http://axis/", HttpCameraKind.kAxis)) {
      VideoProperty[] props = cam.enumerateProperties();
      T.eq(5, props.length);
      T.eq("brightness", props[0].getName());
      VideoProperty wb = cam.getProperty("white_balance");
      T.eq(VideoProperty.Kind.kEnum, wb.getKind());
      T.eq(7, wb.getChoices().length);
      T.eq(6, cam.enumerateVideoModes().length);
      cam.getProperty("brightness").set(80);
      T.eq(50, cam.getProperty("brightness").get());
    }
  }

  public static void testCvSourceIsNotConnectedUntilTold() {
    try (CvSource src = newCvSource("cv-a")) {
      T.eq(VideoSource.Kind.kCv, src.getKind());
      T.check(!src.isConnected(), "connected");
      src.setConnected(true);
      T.check(src.isConnected(), "connected after");
      src.setConnected(false);
      T.check(!src.isConnected(), "disconnected after");
      src.setDescription("a camera");
      T.eq("a camera", src.getDescription());
      src.notifyError("oops");
      T.check(src.getLastFrameTime() > 0, "error stamps the frame time");
    }
  }

  public static void testCvSourceVideoMode() {
    try (CvSource src = newCvSource("cv-b")) {
      VideoMode m = src.getVideoMode();
      T.eq(new VideoMode(PixelFormat.kBGR, 320, 240, 30), m);
      T.eq(1, src.enumerateVideoModes().length);
      T.check(src.setResolution(640, 360), "resolution");
      T.check(src.setFPS(20), "fps");
      T.check(src.setPixelFormat(PixelFormat.kGray), "pixel format");
      T.eq(new VideoMode(PixelFormat.kGray, 640, 360, 20), src.getVideoMode());
      T.eq(new VideoMode(PixelFormat.kGray, 640, 360, 20), src.enumerateVideoModes()[0]);
    }
  }

  public static void testCvSourceProperties() {
    try (CvSource src = newCvSource("cv-c")) {
      VideoProperty i = src.createIntegerProperty("gain", 0, 10, 1, 5, 7);
      T.eq(VideoProperty.Kind.kInteger, i.getKind());
      T.eq("gain", i.getName());
      T.eq(7, i.get());
      T.eq(0, i.getMin());
      T.eq(10, i.getMax());
      T.eq(1, i.getStep());
      T.eq(5, i.getDefault());
      i.set(99);
      T.eq(10, i.get());
      i.set(-5);
      T.eq(0, i.get());

      VideoProperty b = src.createBooleanProperty("on", true, false);
      T.eq(VideoProperty.Kind.kBoolean, b.getKind());
      T.eq(0, b.get());
      b.set(1);
      T.eq(1, b.get());

      VideoProperty s = src.createStringProperty("label", "x");
      T.eq(VideoProperty.Kind.kString, s.getKind());
      T.eq("x", s.getString());
      s.setString("y");
      T.eq("y", s.getString());

      VideoProperty e = src.createProperty("mode", VideoProperty.Kind.kEnum, 0, 2, 1, 0, 1);
      src.setEnumPropertyChoices(e, new String[] {"a", "b", "c"});
      T.eq(new String[] {"a", "b", "c"}, e.getChoices());

      T.eq(4, src.enumerateProperties().length);
      T.eq("wrong property type", T.throwsA(VideoException.class, s::get).getMessage());
      T.eq("wrong property type", T.throwsA(VideoException.class, () -> i.setString("z")).getMessage());
      T.eq("wrong property type", T.throwsA(VideoException.class, () -> src.setEnumPropertyChoices(i, new String[] {"q"})).getMessage());
    }
  }

  public static void testUnknownPropertyHasNoKind() {
    try (CvSource src = newCvSource("cv-d")) {
      VideoProperty p = src.getProperty("nope");
      T.eq(VideoProperty.Kind.kNone, p.getKind());
      T.eq("wrong property type", T.throwsA(VideoException.class, p::get).getMessage());
      p.set(3);
      VideoProperty again = src.getProperty("nope");
      T.eq(VideoProperty.Kind.kInteger, again.getKind());
      T.eq(3, again.get());
    }
  }

  public static void testCvSourceRejectsCameraCalls() {
    try (CvSource src = newCvSource("cv-e")) {
      T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.setCameraBrightness(src.getHandle(), 1)).getMessage());
      T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.getUsbCameraPath(src.getHandle())).getMessage());
    }
  }

  public static void testConfigJsonRoundTrip() {
    try (CvSource a = newCvSource("cv-f");
        CvSource b = newCvSource("cv-g")) {
      a.createIntegerProperty("gain", 0, 10, 1, 5, 7);
      a.createStringProperty("label", "hi \"there\"");
      a.createBooleanProperty("on", false, true);
      a.setResolution(160, 120);
      String json = a.getConfigJson();
      T.check(json.contains("\"pixel format\": \"bgr\""), json);
      T.check(json.contains("\"width\": 160"), json);
      b.createIntegerProperty("gain", 0, 10, 1, 5, 0);
      b.createStringProperty("label", "");
      b.createBooleanProperty("on", false, false);
      T.check(b.setConfigJson(json), "apply");
      T.eq(a.getConfigJson(), b.getConfigJson());
      T.eq(7, b.getProperty("gain").get());
      T.eq("hi \"there\"", b.getProperty("label").getString());
      T.eq(160, b.getVideoMode().width);
    }
  }

  public static void testConfigJsonPieces() {
    try (UsbCamera cam = new UsbCamera("usb-g", 0)) {
      T.check(cam.setConfigJson("{\"pixel format\": \"yuyv\", \"width\": 320, \"height\": 240, \"fps\": 30, \"brightness\": 5, \"exposure\": \"auto\", \"white balance\": 3000}"), "apply");
      T.eq(new VideoMode(PixelFormat.kYUYV, 320, 240, 30), cam.getVideoMode());
      T.eq(5, cam.getBrightness());
      T.eq("property write failed", T.throwsA(VideoException.class, () -> cam.setConfigJson("{nope")).getMessage());
      T.check(cam.getConfigJson().contains("\"pixel format\": \"yuyv\""), cam.getConfigJson());
      T.check(!cam.getConfigJson().contains("properties"), cam.getConfigJson());
    }
  }

  public static void testCopyAndReleaseCountReferences() {
    UsbCamera cam = new UsbCamera("usb-h", 0);
    int handle = cam.getHandle();
    int copy = CameraServerJNI.copySource(handle);
    T.eq(handle, copy);
    CameraServerJNI.releaseSource(copy);
    T.check(cam.isValid() && cam.getName().equals("usb-h"), "still alive");
    cam.close();
    T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.getSourceName(handle)).getMessage());
    T.eq(0, CameraServerJNI.copySource(0));
    CameraServerJNI.releaseSource(0);
  }

  public static void testHandlesAreReusedAndTyped() {
    UsbCamera a = new UsbCamera("usb-i", 0);
    int h = a.getHandle();
    T.eq(4, h >> 24);
    a.close();
    UsbCamera b = new UsbCamera("usb-j", 0);
    T.eq(h, b.getHandle());
    b.close();
    T.throwsA(VideoException.class, () -> CameraServerJNI.getSourceKind(0x05000000));
    T.throwsA(VideoException.class, () -> CameraServerJNI.getSourceKind(0));
  }

  public static void testNullNamesAreRejected() {
    T.eq("name cannot be null", T.throwsA(NullPointerException.class, () -> CameraServerJNI.createUsbCameraDev(null, 0)).getMessage());
    T.throwsA(NullPointerException.class, () -> CameraServerJNI.createUsbCameraPath("n", null));
    T.throwsA(NullPointerException.class, () -> CameraServerJNI.createRawSource(null, true, 1, 1, 1, 1));
  }

  public static void testPutFrameIsAcceptedAndStamped() {
    try (CvSource src = newCvSource("cv-h")) {
      java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(16);
      T.eq(0L, src.getLastFrameTime());
      CameraServerJNI.putRawSourceFrameBB(src.getHandle(), data, 16, 4, 4, 4, 5);
      long t = src.getLastFrameTime();
      T.check(t > 0, "stamped");
      T.throwsA(NullPointerException.class, () -> CameraServerJNI.putRawSourceFrameBB(src.getHandle(), java.nio.ByteBuffer.allocate(4), 4, 1, 1, 1, 5));
      T.throwsA(NullPointerException.class, () -> CameraServerJNI.putRawSourceFrameData(src.getHandle(), 0, 0, 0, 0, 0, 0));
      T.throwsA(NullPointerException.class, () -> CameraServerJNI.putRawSourceFrame(src.getHandle(), 0));
    }
    try (UsbCamera cam = new UsbCamera("usb-k", 0)) {
      T.eq("invalid handle", T.throwsA(VideoException.class, () -> CameraServerJNI.putRawSourceFrameData(cam.getHandle(), 1, 1, 1, 1, 1, 1)).getMessage());
    }
  }
}
