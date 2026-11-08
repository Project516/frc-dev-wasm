package dev.project516.frcdevwasm.shim.net;

import edu.wpi.first.net.ServiceData;
import java.util.ArrayList;

/**
 * Pure-Java stand-in for the wpinet JNI library. The browser has no sockets or mDNS, so port
 * forwarding and the web server do nothing, announcers and resolvers report no implementation
 * and a resolver never finds a service.
 */
public final class WPINetJNIImpl {
  private WPINetJNIImpl() {}

  private static final int EVENT_HANDLE_TYPE = 1;
  private static final ServiceData[] NO_SERVICES = new ServiceData[0];

  // Announcer and resolver handles share one id space, like the native handle manager.
  // The value says whether the slot holds a resolver.
  private static final ArrayList<Boolean> handles = new ArrayList<>();

  private static synchronized int allocate(boolean resolver) {
    for (int i = 0; i < handles.size(); i++) {
      if (handles.get(i) == null) {
        handles.set(i, resolver);
        return i;
      }
    }
    handles.add(resolver);
    return handles.size() - 1;
  }

  private static synchronized void free(int handle, boolean resolver) {
    if (handle >= 0 && handle < handles.size() && Boolean.valueOf(resolver).equals(handles.get(handle))) {
      handles.set(handle, null);
    }
  }

  public static void addPortForwarder(int port, String remoteHost, int remotePort) {}

  public static void removePortForwarder(int port) {}

  public static void startWebServer(int port, String path) {}

  public static void stopWebServer(int port) {}

  public static int createMulticastServiceAnnouncer(
      String serviceName, String serviceType, int port, String[] keys, String[] values) {
    return allocate(false);
  }

  public static void freeMulticastServiceAnnouncer(int handle) {
    free(handle, false);
  }

  public static void startMulticastServiceAnnouncer(int handle) {}

  public static void stopMulticastServiceAnnouncer(int handle) {}

  public static boolean getMulticastServiceAnnouncerHasImplementation(int handle) {
    return false;
  }

  public static int createMulticastServiceResolver(String serviceType) {
    return allocate(true);
  }

  public static void freeMulticastServiceResolver(int handle) {
    free(handle, true);
  }

  public static void startMulticastServiceResolver(int handle) {}

  public static void stopMulticastServiceResolver(int handle) {}

  public static boolean getMulticastServiceResolverHasImplementation(int handle) {
    return false;
  }

  // Never signaled. Shaped like a wpi event handle: type in bits 24-30, nonzero.
  public static int getMulticastServiceResolverEventHandle(int handle) {
    return (EVENT_HANDLE_TYPE << 24) | (handle + 1);
  }

  public static ServiceData[] getMulticastServiceResolverData(int handle) {
    return NO_SERVICES;
  }
}
