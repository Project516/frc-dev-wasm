package edu.wpi.first.wpiutil;

/** Replaces the JNI class of SmartDashboard's bundled wpiutil. There are no sockets to forward ports to. */
public final class WPIUtilJNI {
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

  private WPIUtilJNI() {}

  public static synchronized void forceLoad() {}

  public static long now() {
    return System.nanoTime() / 1000;
  }

  public static void addPortForwarder(int port, String remoteHost, int remotePort) {}

  public static void removePortForwarder(int port) {}
}
