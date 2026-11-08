package dev.project516.frcdevwasm.sim;

/**
 * Shared allocation table of digital channels and PWM headers, as digitalChannelHandles in
 * hal/src/main/native/sim/DigitalInternal.cpp. DIO and PWM allocate through it, so a channel cannot
 * be used twice.
 */
public final class DigitalPorts {
  /** Per-channel state, like struct DigitalPort. */
  public static final class Port {
    public int channel;
    public int filterIndex;
    public String previousAllocation = "";
  }

  /** Result of {@link #allocate}: a handle and port, or the failing status. */
  public static final class Allocation {
    public int status;
    public int handle;
    public Port port;
  }

  private static final int SIZE = Ports.DIGITAL_CHANNELS + Ports.PWM_HEADERS;
  private static final Port[] PORTS = new Port[SIZE];

  private DigitalPorts() {}

  /**
   * Allocates a channel, as DigitalHandleResource::Allocate. On failure the last error of the
   * thread is set and status is HAL_USE_LAST_ERROR.
   */
  public static synchronized Allocation allocate(int index, int handleType, String name) {
    Allocation a = new Allocation();
    if (index < 0 || index >= SIZE) {
      a.status = Status.indexOutOfRange(Status.RESOURCE_OUT_OF_RANGE, name, 0, SIZE, index);
      return a;
    }
    if (PORTS[index] != null) {
      a.status =
          Status.previouslyAllocated(
              Status.RESOURCE_IS_ALLOCATED, name, index, PORTS[index].previousAllocation);
      return a;
    }
    PORTS[index] = new Port();
    a.port = PORTS[index];
    a.handle = Handles.make(index, handleType);
    return a;
  }

  public static synchronized Port get(int handle, int handleType) {
    int i = Handles.index(handle, handleType);
    return i < 0 || i >= SIZE ? null : PORTS[i];
  }

  public static synchronized void free(int handle, int handleType) {
    int i = Handles.index(handle, handleType);
    if (i >= 0 && i < SIZE) {
      PORTS[i] = null;
    }
  }

  public static int remapMxpPwmChannel(int channel) {
    return channel < 14 ? channel - 10 : channel - 6;
  }

  /** DIO channel of a DIO handle, or -1 if the handle is not a DIO handle. */
  public static int digitalInputChannel(int handle) {
    Port p = get(handle, Handles.DIO);
    return p == null ? -1 : p.channel;
  }
}
