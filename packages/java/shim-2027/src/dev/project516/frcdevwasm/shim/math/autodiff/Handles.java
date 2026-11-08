package dev.project516.frcdevwasm.shim.math.autodiff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Maps the long handles WPILib keeps to Java objects. A handle is its slot plus one. */
public final class Handles {
  private Handles() {}

  private static volatile Object[] objects = new Object[256];
  private static int[] free = new int[256];
  private static int freeCount;
  private static int used;

  public static synchronized long add(Object o) {
    int slot;
    if (freeCount > 0) {
      slot = free[--freeCount];
    } else {
      if (used == objects.length) {
        objects = Arrays.copyOf(objects, used * 2);
      }
      slot = used++;
    }
    objects[slot] = o;
    return slot + 1L;
  }

  public static Object get(long handle) {
    return objects[(int) handle - 1];
  }

  public static Expr expr(long handle) {
    return (Expr) objects[(int) handle - 1];
  }

  public static synchronized void remove(long handle) {
    int slot = (int) handle - 1;
    if (objects[slot] == null) {
      return;
    }
    objects[slot] = null;
    if (freeCount == free.length) {
      free = Arrays.copyOf(free, freeCount * 2);
    }
    free[freeCount++] = slot;
  }

  /** Every live object, for memory accounting. */
  static synchronized List<Object> all() {
    List<Object> out = new ArrayList<>();
    for (int i = 0; i < used; i++) {
      if (objects[i] != null) {
        out.add(objects[i]);
      }
    }
    return out;
  }

  public static long[] addAll(Expr[] exprs) {
    long[] out = new long[exprs.length];
    for (int i = 0; i < exprs.length; i++) {
      out[i] = add(exprs[i]);
    }
    return out;
  }

  public static Expr[] exprs(long[] handles) {
    Expr[] out = new Expr[handles.length];
    for (int i = 0; i < handles.length; i++) {
      out[i] = expr(handles[i]);
    }
    return out;
  }
}
