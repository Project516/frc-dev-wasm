package dev.project516.frcdevwasm.sim;

public final class LongValue extends SimValue {
  private volatile long value;

  public LongValue(String name, long initial) {
    super(name);
    value = initial;
  }

  public LongValue(String name) {
    this(name, 0);
  }

  public long get() {
    return value;
  }

  public void set(long next) {
    synchronized (this) {
      if (value == next) {
        return;
      }
      value = next;
    }
    notifyChanged();
  }

  /** Drops callbacks and sets the value without notifying. */
  public void reset(long next) {
    clearCallbacks();
    value = next;
  }

  @Override
  public int type() {
    return LONG;
  }

  @Override
  protected long nativeLong() {
    return value;
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
    set(dev.project516.frcdevwasm.bridge.Json.toLong(json));
  }
}
