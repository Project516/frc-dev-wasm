package dev.project516.frcdevwasm.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.wpilib.hardware.hal.simulation.ConstBufferCallback;

/**
 * Simulation data of the addressable LED outputs (mockdata/AddressableLEDData.cpp). All outputs
 * share one buffer of colors; each output has a start and a length into it.
 */
public final class AddressableLedData {
  public static final int MAX_LENGTH = 1024;
  private static final int BYTES_PER_LED = 3;

  private static final AddressableLedData[] ALL = new AddressableLedData[Ports.ADDRESSABLE_LEDS];
  private static final byte[] DATA = new byte[MAX_LENGTH * BYTES_PER_LED];
  private static final CallbackRegistry<ConstBufferCallback> DATA_CALLBACKS =
      new CallbackRegistry<>("Data");

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new AddressableLedData(i);
    }
  }

  /** The page sees the colors of the output's own range, from its start, as "&lt;data". */
  private final class DataValue extends SimValue {
    DataValue() {
      super("Data");
    }

    @Override
    public int type() {
      return 0;
    }

    @Override
    protected long nativeLong() {
      return 0;
    }

    @Override
    protected double nativeDouble() {
      return 0;
    }

    @Override
    public Object toJson() {
      byte[] bytes = getData(start.get(), length.get());
      List<Object> leds = new ArrayList<>(bytes.length / BYTES_PER_LED);
      for (int i = 0; i + 2 < bytes.length; i += BYTES_PER_LED) {
        Map<String, Object> led = new LinkedHashMap<>();
        led.put("r", bytes[i] & 0xff);
        led.put("g", bytes[i + 1] & 0xff);
        led.put("b", bytes[i + 2] & 0xff);
        leds.add(led);
      }
      return leds;
    }

    @Override
    public void fromJson(Object json) {}

    void changed() {
      notifyChanged();
    }
  }

  public final BoolValue initialized = new BoolValue("Initialized");
  public final IntValue start = new IntValue("Start");
  public final IntValue length = new IntValue("Length");

  private final DataValue dataValue = new DataValue();
  private final Ws.Device ws;

  private AddressableLedData(int index) {
    ws = new Ws.Device("AddressableLED", String.valueOf(index), initialized);
    bindWs();
  }

  private void bindWs() {
    ws.bind("<start", start).bind("<length", length).bind("<data", dataValue);
  }

  public static AddressableLedData get(int index) {
    return ALL[index];
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : ALL[i].ws;
  }

  private boolean overlaps(int writeStart, int writeLength) {
    return writeStart < start.get() + length.get() && start.get() < writeStart + writeLength;
  }

  /**
   * Stores len LEDs at start and calls the data callbacks, as SetData. Unlike halsim_ws_core, which
   * sends the written bytes to every output, only outputs whose range overlaps the write get a
   * message, with the colors of their own range.
   */
  public static void setData(int start, int len, byte[] d) {
    if (start + len > MAX_LENGTH) {
      len = MAX_LENGTH - start;
    }
    if (len <= 0) {
      return;
    }
    int count = len * BYTES_PER_LED;
    byte[] written = Arrays.copyOf(d, count);
    synchronized (DATA) {
      System.arraycopy(written, 0, DATA, start * BYTES_PER_LED, count);
    }
    DATA_CALLBACKS.invokeCatching(cb -> cb.callback(DATA_CALLBACKS.name, written, count));
    for (AddressableLedData led : ALL) {
      if (led.initialized.get() && led.overlaps(start, len)) {
        led.dataValue.changed();
      }
    }
  }

  /** The colors of up to length LEDs from start, as GetData. */
  public static byte[] getData(int start, int length) {
    if (start + length > MAX_LENGTH) {
      length = MAX_LENGTH - start;
    }
    if (length <= 0) {
      return new byte[0];
    }
    synchronized (DATA) {
      return Arrays.copyOfRange(
          DATA, start * BYTES_PER_LED, (start + length) * BYTES_PER_LED);
    }
  }

  public static int registerDataCallback(ConstBufferCallback callback) {
    return DATA_CALLBACKS.register(callback);
  }

  public static void cancelDataCallback(int uid) {
    DATA_CALLBACKS.cancel(uid);
  }

  /** Resets values and drops callbacks, as HALSIM_ResetAddressableLEDData. The buffer is kept. */
  public void reset() {
    initialized.reset(false);
    start.reset(0);
    length.reset(0);
    dataValue.clearCallbacks();
    ws.rebind();
  }
}
