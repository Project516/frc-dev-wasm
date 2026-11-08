package dev.project516.frcdevwasm.sim;

import java.util.function.BiConsumer;

/**
 * HAL handle encoding and resource tables, as in hal/handles. A handle is
 * (type &lt;&lt; 24) | index; 0 is the invalid handle. The rolling reset detection byte is not kept.
 */
public final class Handles {
  public static final int INVALID = 0;

  public static final int DIO = 48;
  public static final int PORT = 2;
  public static final int NOTIFIER = 3;
  public static final int ANALOG_INPUT = 4;
  public static final int PWM = 5;
  public static final int DIGITAL_PWM = 6;
  public static final int COUNTER = 7;
  public static final int FPGA_ENCODER = 8;
  public static final int ENCODER = 9;
  public static final int COMPRESSOR = 10;
  public static final int SOLENOID = 11;
  public static final int VENDOR = 12;
  public static final int SIMULATION_JNI = 13;
  public static final int CAN = 14;
  public static final int SERIAL_PORT = 15;
  public static final int DUTY_CYCLE = 16;
  public static final int ADDRESSABLE_LED = 17;
  public static final int CTRE_PCM = 18;
  public static final int CTRE_PDP = 19;
  public static final int REV_PDH = 20;
  public static final int REV_PH = 21;
  public static final int CAN_STREAM = 22;
  public static final int ALERT = 23;

  private Handles() {}

  public static int make(int index, int type) {
    if (index < 0) {
      return INVALID;
    }
    return (type << 24) | (index & 0xffff);
  }

  public static int type(int handle) {
    return (handle >> 24) & 0xff;
  }

  /** Index of a handle of the given type, or -1 if the handle has another type. */
  public static int index(int handle, int type) {
    return type(handle) == type ? handle & 0xffff : -1;
  }

  /** Fixed size table, like LimitedHandleResource. */
  public static final class Limited<T> {
    private final int type;
    private final Object[] items;
    private final String[] locations;

    public Limited(int type, int size) {
      this.type = type;
      this.items = new Object[size];
      this.locations = new String[size];
    }

    /**
     * IndexedHandleResource::Allocate: allocates the given index and throws the allocation
     * exception, as the JNI wrapper does, if it is out of range or taken. The offset shifts the
     * indices in the message.
     */
    public synchronized int allocate(int index, T item, String name, String location, int offset) {
      if (index < 0 || index >= items.length) {
        Status.indexOutOfRange(
            Status.RESOURCE_OUT_OF_RANGE, name, offset, items.length + offset, index + offset);
        Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      }
      if (items[index] != null) {
        Status.previouslyAllocated(
            Status.RESOURCE_IS_ALLOCATED, name, index + offset, locations[index]);
        Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      }
      items[index] = item;
      locations[index] = location;
      return make(index, type);
    }

    /** Allocates the given index. Returns INVALID if it is out of range or taken. */
    public synchronized int allocate(int index, T item) {
      if (index < 0 || index >= items.length || items[index] != null) {
        return INVALID;
      }
      items[index] = item;
      return make(index, type);
    }

    /** Allocates the first free index. Returns INVALID if the table is full. */
    public synchronized int allocate(T item) {
      for (int i = 0; i < items.length; i++) {
        if (items[i] == null) {
          items[i] = item;
          return make(i, type);
        }
      }
      return INVALID;
    }

    @SuppressWarnings("unchecked")
    public synchronized T get(int handle) {
      int i = index(handle, type);
      return i < 0 || i >= items.length ? null : (T) items[i];
    }

    @SuppressWarnings("unchecked")
    public synchronized T free(int handle) {
      int i = index(handle, type);
      if (i < 0 || i >= items.length) {
        return null;
      }
      T old = (T) items[i];
      items[i] = null;
      return old;
    }

    public int size() {
      return items.length;
    }

    @SuppressWarnings("unchecked")
    public synchronized void forEach(BiConsumer<Integer, T> fn) {
      for (int i = 0; i < items.length; i++) {
        if (items[i] != null) {
          fn.accept(make(i, type), (T) items[i]);
        }
      }
    }

    public synchronized void clear() {
      java.util.Arrays.fill(items, null);
    }
  }

  /** Growable table, like UnlimitedHandleResource. */
  public static final class Unlimited<T> {
    private final int type;
    private final java.util.ArrayList<T> items = new java.util.ArrayList<>();

    public Unlimited(int type) {
      this.type = type;
    }

    public synchronized int allocate(T item) {
      for (int i = 0; i < items.size(); i++) {
        if (items.get(i) == null) {
          items.set(i, item);
          return make(i, type);
        }
      }
      items.add(item);
      return make(items.size() - 1, type);
    }

    public synchronized T get(int handle) {
      int i = index(handle, type);
      return i < 0 || i >= items.size() ? null : items.get(i);
    }

    public synchronized T free(int handle) {
      int i = index(handle, type);
      if (i < 0 || i >= items.size()) {
        return null;
      }
      return items.set(i, null);
    }

    /** Snapshot of live items, in handle order. */
    public synchronized java.util.List<T> items() {
      java.util.List<T> out = new java.util.ArrayList<>();
      for (T t : items) {
        if (t != null) {
          out.add(t);
        }
      }
      return out;
    }

    public synchronized void forEach(BiConsumer<Integer, T> fn) {
      for (int i = 0; i < items.size(); i++) {
        if (items.get(i) != null) {
          fn.accept(make(i, type), items.get(i));
        }
      }
    }
  }
}
