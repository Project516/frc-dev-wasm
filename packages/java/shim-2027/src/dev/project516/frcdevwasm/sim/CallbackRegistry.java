package dev.project516.frcdevwasm.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Callback list without a value, like C++ hal::SimCallbackRegistry. Uids start at 1; a freed slot
 * is reused by the next registration.
 */
public final class CallbackRegistry<T> {
  /** Callback name passed to every callback, as in the C++ sim ("Read", "Write", ...). */
  public final String name;

  private final List<T> slots = new ArrayList<>();
  private final List<Integer> free = new ArrayList<>();
  private Object[] snapshot = new Object[0];

  public CallbackRegistry(String name) {
    this.name = name;
  }

  /** Returns the uid for {@link #cancel}, or -1 for a null callback. */
  public synchronized int register(T callback) {
    if (callback == null) {
      return -1;
    }
    int slot;
    if (free.isEmpty()) {
      slot = slots.size();
      slots.add(callback);
    } else {
      slot = free.remove(free.size() - 1);
      slots.set(slot, callback);
    }
    rebuild();
    return slot + 1;
  }

  public synchronized void cancel(int uid) {
    int slot = uid - 1;
    if (uid > 0 && slot < slots.size() && slots.get(slot) != null) {
      slots.set(slot, null);
      free.add(slot);
      rebuild();
    }
  }

  /** Drops all callbacks. Part of every resetData. */
  public synchronized void reset() {
    slots.clear();
    free.clear();
    rebuild();
  }

  private void rebuild() {
    snapshot = slots.stream().filter(c -> c != null).toArray();
  }

  /** Calls fn for every callback in registration slot order. */
  @SuppressWarnings("unchecked")
  public void invoke(Consumer<T> fn) {
    Object[] cbs;
    synchronized (this) {
      cbs = snapshot;
    }
    for (Object cb : cbs) {
      fn.accept((T) cb);
    }
  }

  /** Like {@link #invoke}, but prints and drops an exception from a callback, as the JNI stores do. */
  public void invokeCatching(Consumer<T> fn) {
    invoke(
        cb -> {
          try {
            fn.accept(cb);
          } catch (RuntimeException | Error e) {
            e.printStackTrace();
          }
        });
  }
}
