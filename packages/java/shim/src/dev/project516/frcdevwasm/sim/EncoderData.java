package dev.project516.frcdevwasm.sim;

import dev.project516.frcdevwasm.bridge.Json;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Simulation data of one encoder (hal/src/main/native/sim/mockdata/EncoderData.cpp). */
public final class EncoderData {
  private static final EncoderData[] ALL = new EncoderData[Ports.ENCODERS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new EncoderData(i);
    }
  }

  public volatile int digitalChannelA;
  public volatile int digitalChannelB;
  public final BoolValue initialized = new BoolValue("Initialized");
  public volatile int simDevice;
  public final IntValue count = new IntValue("Count");
  public final DoubleValue period = new DoubleValue("Period", Double.MAX_VALUE);
  public final BoolValue reset = new BoolValue("Reset");
  public final DoubleValue maxPeriod = new DoubleValue("MaxPeriod");
  public final BoolValue direction = new BoolValue("Direction");
  public final BoolValue reverseDirection = new BoolValue("ReverseDirection");
  public final IntValue samplesToAverage = new IntValue("SamplesToAverage");
  public final DoubleValue distancePerPulse = new DoubleValue("DistancePerPulse", 1);

  private final EncoderWs ws;

  private EncoderData(int index) {
    ws = new EncoderWs(String.valueOf(index));
    bindWs();
  }

  /** The page sees the count since it last attached, not since the robot reset the encoder. */
  private final class EncoderWs extends Ws.Device {
    private volatile int countOffset;

    EncoderWs(String id) {
      super("Encoder", id, initialized);
    }

    @Override
    protected Object json(String key, SimValue value) {
      return value == count ? count.get() + countOffset : super.json(key, value);
    }

    @Override
    public Map<String, Object> state() {
      Map<String, Object> m = new LinkedHashMap<>(super.state());
      m.put("<channel_a", digitalChannelA);
      m.put("<channel_b", digitalChannelB);
      return m;
    }

    @Override
    public void receive(Map<String, Object> data) {
      Object c = data.get(">count");
      if (c == null) {
        super.receive(data);
        return;
      }
      Map<String, Object> rest = new HashMap<>(data);
      rest.remove(">count");
      super.receive(rest);
      count.set((int) Json.toLong(c) - countOffset);
      reset.set(false);
    }

    void onReset(boolean now) {
      if (now) {
        countOffset += count.get();
      }
    }
  }

  private void bindWs() {
    ws.bindEcho(">count", count)
        .bindEcho(">period", period)
        .bind("<reverse_direction", reverseDirection)
        .bind("<samples_to_avg", samplesToAverage);
    reset.register((name, v) -> ws.onReset(v.getBoolean()), false);
  }

  public static EncoderData get(int index) {
    return ALL[index];
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : ALL[i].ws;
  }

  /** HALSIM_FindEncoderForChannel. */
  public static int findForChannel(int channel) {
    for (int i = 0; i < ALL.length; i++) {
      if (ALL[i].initialized.get()
          && (ALL[i].digitalChannelA == channel || ALL[i].digitalChannelB == channel)) {
        return i;
      }
    }
    return -1;
  }

  /** HALSIM_SetEncoderCount. */
  public void setCount(int value) {
    count.set(value);
    reset.set(false);
  }

  /** HALSIM_SetEncoderDistance. */
  public void setDistance(double distance) {
    count.set((int) (distance / distancePerPulse.get()));
    reset.set(false);
  }

  /** HALSIM_GetEncoderDistance. */
  public double getDistance() {
    return count.get() * distancePerPulse.get();
  }

  /** HALSIM_SetEncoderRate. */
  public void setRate(double rate) {
    period.set(rate == 0 ? Double.POSITIVE_INFINITY : distancePerPulse.get() / rate);
  }

  /** HALSIM_GetEncoderRate. */
  public double getRate() {
    return distancePerPulse.get() / period.get();
  }

  /** Resets values and drops callbacks, as HALSIM_ResetEncoderData. */
  public void reset() {
    digitalChannelA = 0;
    digitalChannelB = 0;
    initialized.reset(false);
    simDevice = 0;
    count.reset(0);
    period.reset(Double.MAX_VALUE);
    reset.reset(false);
    maxPeriod.reset(0);
    direction.reset(false);
    reverseDirection.reset(false);
    samplesToAverage.reset(0);
    distancePerPulse.reset(1);
    ws.countOffset = 0;
    ws.rebind();
    reset.register((name, v) -> ws.onReset(v.getBoolean()), false);
  }
}
