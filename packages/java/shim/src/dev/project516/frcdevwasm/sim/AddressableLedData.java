package dev.project516.frcdevwasm.sim;

import edu.wpi.first.hal.simulation.ConstBufferCallback;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Simulation data of an addressable LED output (mockdata/AddressableLEDData.cpp). */
public final class AddressableLedData {
  public static final int MAX_LENGTH = 5460;
  private static final int BYTES_PER_LED = 4;

  private static final AddressableLedData[] ALL = new AddressableLedData[Ports.ADDRESSABLE_LEDS];

  static {
    for (int i = 0; i < ALL.length; i++) {
      ALL[i] = new AddressableLedData(i);
    }
  }

  /** Buffer of LED colors, bytes b, g, r, padding per LED. The page sees it as "&lt;data". */
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
      byte[] bytes = getData();
      List<Object> leds = new ArrayList<>(bytes.length / BYTES_PER_LED);
      for (int i = 0; i + 3 < bytes.length; i += BYTES_PER_LED) {
        Map<String, Object> led = new LinkedHashMap<>();
        led.put("r", bytes[i + 2] & 0xff);
        led.put("g", bytes[i + 1] & 0xff);
        led.put("b", bytes[i] & 0xff);
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

  private record BufferReg(int uid, ConstBufferCallback callback) {}

  public final BoolValue initialized = new BoolValue("Initialized");
  public final IntValue outputPort = new IntValue("OutputPort", -1);
  public final IntValue length = new IntValue("Length", 1);
  public final BoolValue running = new BoolValue("Running");

  private final DataValue dataValue = new DataValue();
  private final byte[] data = new byte[MAX_LENGTH * BYTES_PER_LED];
  private BufferReg[] dataCallbacks = new BufferReg[0];
  private int nextUid = 1;

  private final Ws.Device ws;

  private AddressableLedData(int index) {
    ws = new Ws.Device("AddressableLED", String.valueOf(index), initialized);
    bindWs();
  }

  private void bindWs() {
    ws.bind("<output_port", outputPort)
        .bind("<length", length)
        .bind("<running", running)
        .bind("<data", dataValue);
  }

  public static AddressableLedData get(int index) {
    return ALL[index];
  }

  /** Resolver for Ws.type. */
  static Ws.Device ws(String id) {
    int i = Ws.index(id, ALL.length);
    return i < 0 ? null : ALL[i].ws;
  }

  /** Index of the initialized strip on the output port, or -1. */
  public static int findForChannel(int channel) {
    for (int i = 0; i < ALL.length; i++) {
      if (ALL[i].initialized.get() && ALL[i].outputPort.get() == channel) {
        return i;
      }
    }
    return -1;
  }

  /** Stores len LEDs from d and calls the data callbacks, as SetData. */
  public void setData(byte[] d, int len) {
    len = Math.max(0, Math.min(Math.min(MAX_LENGTH, len), d.length / BYTES_PER_LED));
    int count = len * BYTES_PER_LED;
    BufferReg[] regs;
    synchronized (this) {
      System.arraycopy(d, 0, data, 0, count);
      regs = dataCallbacks;
    }
    for (BufferReg r : regs) {
      r.callback.callback(dataValue.name, Arrays.copyOf(d, count), count);
    }
    dataValue.changed();
  }

  /** Current colors of length LEDs, as GetData. */
  public synchronized byte[] getData() {
    int len = Math.max(0, Math.min(MAX_LENGTH, length.get()));
    return Arrays.copyOf(data, len * BYTES_PER_LED);
  }

  public synchronized int registerDataCallback(ConstBufferCallback callback) {
    int uid = nextUid++;
    BufferReg[] next = Arrays.copyOf(dataCallbacks, dataCallbacks.length + 1);
    next[dataCallbacks.length] = new BufferReg(uid, callback);
    dataCallbacks = next;
    return uid;
  }

  public synchronized void cancelDataCallback(int uid) {
    for (int i = 0; i < dataCallbacks.length; i++) {
      if (dataCallbacks[i].uid == uid) {
        BufferReg[] next = new BufferReg[dataCallbacks.length - 1];
        System.arraycopy(dataCallbacks, 0, next, 0, i);
        System.arraycopy(dataCallbacks, i + 1, next, i, next.length - i);
        dataCallbacks = next;
        return;
      }
    }
  }

  /** Resets values and drops callbacks, as HALSIM_ResetAddressableLEDData. The buffer is kept. */
  public void reset() {
    initialized.reset(false);
    outputPort.reset(-1);
    length.reset(1);
    running.reset(false);
    dataValue.clearCallbacks();
    synchronized (this) {
      dataCallbacks = new BufferReg[0];
    }
    ws.rebind();
  }
}
