package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.RelayData;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/** Port of hal/src/main/native/sim/Relay.cpp and the RelayJNI wrappers. */
public final class RelayJNIImpl {
  private static final class Relay {
    int channel;
    boolean fwd;
    String previousAllocation = "";
  }

  private static final Relay[] RELAYS = new Relay[Ports.RELAY_CHANNELS];

  private RelayJNIImpl() {}

  private static synchronized Relay relay(int handle) {
    int i = Handles.index(handle, Handles.RELAY);
    Relay r = i < 0 || i >= RELAYS.length ? null : RELAYS[i];
    if (r == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return r;
  }

  private static int outOfRange(int channel) {
    Status.indexOutOfRange(
        Status.RESOURCE_OUT_OF_RANGE, "Invalid Index for Relay", 0, Ports.RELAY_CHANNELS, channel);
    Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
    return 0;
  }

  public static synchronized int initializeRelayPort(int halPortHandle, boolean fwd) {
    Sim.ensureInitialized();
    int channel = Handles.portChannel(halPortHandle);
    if (channel == -1 || channel >= Ports.RELAY_CHANNELS) {
      return outOfRange(channel);
    }
    if (!fwd) {
      channel += Ports.RELAY_HEADERS;
    } else if (channel >= Ports.RELAY_HEADERS) {
      // RelayData only has the headers
      return outOfRange(channel);
    }
    if (channel >= RELAYS.length) {
      return outOfRange(channel);
    }
    if (RELAYS[channel] != null) {
      Status.previouslyAllocated(
          Status.RESOURCE_IS_ALLOCATED, "Relay", channel, RELAYS[channel].previousAllocation);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    Relay r = new Relay();
    RELAYS[channel] = r;
    int handle = Handles.make(channel, Handles.RELAY);
    if (!fwd) {
      channel -= Ports.RELAY_HEADERS;
      r.fwd = false;
      RelayData.get(channel).initializedReverse.set(true);
    } else {
      r.fwd = true;
      RelayData.get(channel).initializedForward.set(true);
    }
    r.channel = channel;
    r.previousAllocation = Status.allocationLocation();
    return handle;
  }

  public static void freeRelayPort(int id) {
    if (id == Handles.INVALID) {
      return;
    }
    Relay r;
    synchronized (RelayJNIImpl.class) {
      int i = Handles.index(id, Handles.RELAY);
      if (i < 0 || i >= RELAYS.length) {
        return;
      }
      r = RELAYS[i];
      RELAYS[i] = null;
    }
    if (r == null) {
      return;
    }
    if (r.fwd) {
      RelayData.get(r.channel).initializedForward.set(false);
    } else {
      RelayData.get(r.channel).initializedReverse.set(false);
    }
  }

  public static boolean checkRelayChannel(int channel) {
    // The JNI wrapper narrows the channel to a uint8_t.
    int c = channel & 0xff;
    return c < Ports.RELAY_HEADERS;
  }

  public static void setRelay(int id, boolean value) {
    Relay r = relay(id);
    if (r == null) {
      return;
    }
    RelayData data = RelayData.get(r.channel);
    if (r.fwd) {
      data.forward.set(value);
    } else {
      data.reverse.set(value);
    }
  }

  public static boolean getRelay(int id) {
    Relay r = relay(id);
    if (r == null) {
      return false;
    }
    RelayData data = RelayData.get(r.channel);
    return r.fwd ? data.forward.get() : data.reverse.get();
  }
}
