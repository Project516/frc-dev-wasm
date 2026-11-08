package dev.project516.frcdevwasm.sim;

/**
 * Shared allocation table of digital channels and PWM headers, as digitalChannelHandles in
 * hal/src/main/native/sim/DigitalInternal.cpp. DIO, PWM and the things built on DIO channels
 * allocate through it, so a channel cannot be used twice.
 */
public final class DigitalPorts {
  /** Per-channel state, like struct DigitalPort. */
  public static final class Port {
    public int channel;
    public boolean configSet;
    public boolean eliminateDeadband;
    public int maxPwm;
    public int deadbandMaxPwm;
    public int centerPwm;
    public int deadbandMinPwm;
    public int minPwm;
    public int filterIndex;
    public String previousAllocation = "";
  }

  /** Result of {@link #allocate}. */
  public static final class Allocation {
    public int status;
    public int handle;
    public Port port;
  }

  public static final int MXP_DIGITAL_PWM_OFFSET = 6;
  public static final int EXPECTED_LOOP_TIMING = 40;
  public static final int PWM_DISABLED = 0;

  private static final int SIZE = Ports.DIGITAL_CHANNELS + Ports.PWM_HEADERS;
  private static final Port[] PORTS = new Port[SIZE];

  private DigitalPorts() {}

  /**
   * Allocates a channel. On failure status is RESOURCE_OUT_OF_RANGE or RESOURCE_IS_ALLOCATED and,
   * in the second case, port is the existing owner.
   */
  public static synchronized Allocation allocate(int index, int handleType) {
    Allocation a = new Allocation();
    if (index < 0 || index >= SIZE) {
      a.status = Status.RESOURCE_OUT_OF_RANGE;
      return a;
    }
    if (PORTS[index] != null) {
      a.status = Status.RESOURCE_IS_ALLOCATED;
      a.port = PORTS[index];
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

  /** Maps MXP DIO channels (10 to 26) to their position in the bit field. */
  public static int remapMxpChannel(int channel) {
    return channel - 10;
  }

  public static int remapMxpPwmChannel(int channel) {
    return channel < 14 ? channel - 10 : channel - 6;
  }

  /** Result of {@link #remapDigitalSource}. */
  public record Source(int channel, int module, boolean analogTrigger) {}

  /**
   * remapDigitalSource: a DIO or analog trigger handle to channel and module. Returns null for any
   * other handle type.
   */
  public static Source remapDigitalSource(int handle, int analogTriggerType) {
    if (Handles.type(handle) == Handles.ANALOG_TRIGGER) {
      int index = handle & 0xffff;
      int channel = ((index << 2) + analogTriggerType) & 0xff;
      return new Source(channel, channel >> 4, true);
    }
    if (Handles.type(handle) == Handles.DIO) {
      int index = handle & 0xffff;
      if (index >= Ports.DIGITAL_HEADERS) {
        return new Source(remapMxpChannel(index), 1, false);
      }
      return new Source(index, 0, false);
    }
    return null;
  }

  /** DIO channel of a DIO handle, or -1 if the handle is not a DIO handle. */
  public static int digitalInputChannel(int handle) {
    Port p = get(handle, Handles.DIO);
    return p == null ? -1 : p.channel;
  }
}
