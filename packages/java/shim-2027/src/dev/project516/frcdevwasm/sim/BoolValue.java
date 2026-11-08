package dev.project516.frcdevwasm.sim;

public final class BoolValue extends SimValue {
  private volatile boolean value;

  public BoolValue(String name, boolean initial) {
    super(name);
    value = initial;
  }

  public BoolValue(String name) {
    this(name, false);
  }

  public boolean get() {
    return value;
  }

  public void set(boolean next) {
    synchronized (this) {
      if (value == next) {
        return;
      }
      value = next;
    }
    notifyChanged();
  }

  /** Drops callbacks and sets the value without notifying. */
  public void reset(boolean next) {
    clearCallbacks();
    value = next;
  }

  @Override
  public int type() {
    return BOOLEAN;
  }

  @Override
  protected long nativeLong() {
    return value ? 1 : 0;
  }

  @Override
  protected double nativeDouble() {
    return 0;
  }

  @Override
  public Object toJson() {
    return value;
  }

  @Override
  public void fromJson(Object json) {
    set(dev.project516.frcdevwasm.bridge.Json.toBool(json));
  }
}
