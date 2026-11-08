package dev.project516.frcdevwasm.sim;

/** An int value. Use {@link #asEnum} for values the C++ sim reports as HAL_ENUM. */
public final class IntValue extends SimValue {
  private final int type;
  private volatile int value;

  private IntValue(String name, int initial, int type) {
    super(name);
    this.value = initial;
    this.type = type;
  }

  public IntValue(String name, int initial) {
    this(name, initial, INT);
  }

  public IntValue(String name) {
    this(name, 0, INT);
  }

  public static IntValue asEnum(String name, int initial) {
    return new IntValue(name, initial, ENUM);
  }

  public int get() {
    return value;
  }

  public void set(int next) {
    synchronized (this) {
      if (value == next) {
        return;
      }
      value = next;
    }
    notifyChanged();
  }

  /** Drops callbacks and sets the value without notifying. */
  public void reset(int next) {
    clearCallbacks();
    value = next;
  }

  @Override
  public int type() {
    return type;
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
    set((int) dev.project516.frcdevwasm.bridge.Json.toLong(json));
  }
}
