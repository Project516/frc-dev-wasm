package dev.project516.frcdevwasm.sim;

import org.wpilib.hardware.hal.simulation.NotifyCallback;

/**
 * A simulation data value with change callbacks, like C++ hal::SimDataValue. Subclasses hold a
 * typed value; callbacks fire only when the value changes.
 */
public abstract class SimValue {
  private record Reg(int uid, NotifyCallback callback) {}

  private static final Reg[] NONE = new Reg[0];

  /** HALValue type constants. */
  public static final int BOOLEAN = 0x01;

  public static final int DOUBLE = 0x02;
  public static final int ENUM = 0x04;
  public static final int INT = 0x08;
  public static final int LONG = 0x10;

  /** Callback name, as in the C++ sim ("Initialized", "Speed", ...). */
  public final String name;

  private Reg[] callbacks = NONE;
  private int nextUid = 1;

  protected SimValue(String name) {
    this.name = name;
  }

  /** HALValue type of this value. */
  public abstract int type();

  protected abstract long nativeLong();

  protected abstract double nativeDouble();

  /** Value as it appears in a JSON message. */
  public abstract Object toJson();

  /** Sets the value from a JSON number or boolean. */
  public abstract void fromJson(Object json);

  /** Registers a callback. Returns its uid for {@link #cancel}. */
  public int register(NotifyCallback callback, boolean initialNotify) {
    int uid;
    synchronized (this) {
      uid = nextUid++;
      Reg[] next = new Reg[callbacks.length + 1];
      System.arraycopy(callbacks, 0, next, 0, callbacks.length);
      next[callbacks.length] = new Reg(uid, callback);
      callbacks = next;
    }
    if (initialNotify) {
      callback.callbackNative(name, type(), nativeLong(), nativeDouble());
    }
    return uid;
  }

  public synchronized void cancel(int uid) {
    for (int i = 0; i < callbacks.length; i++) {
      if (callbacks[i].uid == uid) {
        Reg[] next = new Reg[callbacks.length - 1];
        System.arraycopy(callbacks, 0, next, 0, i);
        System.arraycopy(callbacks, i + 1, next, i, next.length - i);
        callbacks = next;
        return;
      }
    }
  }

  /** Drops all callbacks. Part of every resetData, as in the C++ sim. */
  public synchronized void clearCallbacks() {
    callbacks = NONE;
  }

  /** Calls every callback with the current value. Call after the value changed. */
  protected final void notifyChanged() {
    Reg[] regs;
    synchronized (this) {
      regs = callbacks;
    }
    if (regs.length > 0) {
      int t = type();
      long l = nativeLong();
      double d = nativeDouble();
      for (Reg r : regs) {
        r.callback.callbackNative(name, t, l, d);
      }
    }
  }
}
