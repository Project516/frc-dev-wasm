package dev.project516.frcdevwasm.sim;

public final class DoubleValue extends SimValue {
  private volatile double value;

  public DoubleValue(String name, double initial) {
    super(name);
    value = initial;
  }

  public DoubleValue(String name) {
    this(name, 0);
  }

  public double get() {
    return value;
  }

  public void set(double next) {
    synchronized (this) {
      if (value == next) {
        return;
      }
      value = next;
    }
    notifyChanged();
  }

  /** Drops callbacks and sets the value without notifying. */
  public void reset(double next) {
    clearCallbacks();
    value = next;
  }

  @Override
  public int type() {
    return DOUBLE;
  }

  @Override
  protected long nativeLong() {
    return 0;
  }

  @Override
  protected double nativeDouble() {
    return value;
  }

  @Override
  public Object toJson() {
    return value;
  }

  @Override
  public void fromJson(Object json) {
    double d = dev.project516.frcdevwasm.bridge.Json.toDouble(json);
    if (!Double.isNaN(d)) {
      set(d);
    }
  }
}
