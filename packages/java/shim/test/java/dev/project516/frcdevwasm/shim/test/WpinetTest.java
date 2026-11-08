package dev.project516.frcdevwasm.shim.test;

import dev.project516.frcdevwasm.shim.net.WPINetJNIImpl;
import edu.wpi.first.net.MulticastServiceAnnouncer;
import edu.wpi.first.net.MulticastServiceResolver;
import edu.wpi.first.net.PortForwarder;
import edu.wpi.first.net.WebServer;
import java.util.Map;

public final class WpinetTest {
  public static void testPortForwarderAndWebServerDoNotThrow() {
    PortForwarder.add(5800, "10.0.0.2", 5800);
    PortForwarder.add(5800, "10.0.0.3", 5801);
    PortForwarder.remove(5800);
    PortForwarder.remove(1234);
    WebServer.start(5801, "/tmp");
    WebServer.stop(5801);
    WebServer.stop(9);
  }

  public static void testAnnouncerHasNoImplementation() {
    try (MulticastServiceAnnouncer a = new MulticastServiceAnnouncer("robot", "_frc._tcp", 1735, Map.of("k", "v"))) {
      a.start();
      T.check(!a.hasImplementation(), "implementation");
      a.stop();
    }
    try (MulticastServiceAnnouncer a = new MulticastServiceAnnouncer("robot", "_frc._tcp", 1735)) {
      a.start();
      T.check(!a.hasImplementation(), "implementation");
    }
  }

  public static void testResolverFindsNothing() {
    try (MulticastServiceResolver r = new MulticastServiceResolver("_frc._tcp")) {
      r.start();
      T.check(!r.hasImplementation(), "implementation");
      T.eq(0, r.getData().length);
      T.check(r.getEventHandle() != 0, "event handle");
      r.stop();
    }
  }

  public static void testResolverEventHandlesAreDistinct() {
    try (MulticastServiceResolver a = new MulticastServiceResolver("_a._tcp");
        MulticastServiceResolver b = new MulticastServiceResolver("_b._tcp")) {
      T.check(a.getEventHandle() != b.getEventHandle(), "distinct");
    }
  }

  public static void testHandlesAreRecycledOnFree() {
    int a = WPINetJNIImpl.createMulticastServiceAnnouncer("n", "_t._tcp", 1, null, null);
    int r = WPINetJNIImpl.createMulticastServiceResolver("_t._tcp");
    T.check(a != r, "shared id space");
    WPINetJNIImpl.freeMulticastServiceResolver(a);
    int c = WPINetJNIImpl.createMulticastServiceAnnouncer("n", "_t._tcp", 1, null, null);
    T.check(c != a, "wrong-kind free is ignored");
    WPINetJNIImpl.freeMulticastServiceAnnouncer(a);
    T.eq(a, WPINetJNIImpl.createMulticastServiceResolver("_t._tcp"));
  }
}
