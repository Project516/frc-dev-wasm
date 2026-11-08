package dev.project516.frcdevwasm.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Events, semaphores and signal objects, port of wpiutil Synchronization.cpp. Any handle can be a
 * signal object (ntcore and cscore signal their pollers this way) and be waited on.
 */
public final class Sync {
  public static final int HANDLE_TYPE_EVENT = 1;
  public static final int HANDLE_TYPE_SEMAPHORE = 2;
  public static final int HANDLE_TYPE_CS_BASE = 3;
  public static final int HANDLE_TYPE_NT_BASE = 16;
  public static final int HANDLE_TYPE_HAL_BASE = 48;
  public static final int HANDLE_TYPE_USER_BASE = 80;

  private static final int ERROR_BIT = 0x80000000;

  private static final class State {
    int signaled;
    boolean autoReset;
    final List<Waiter> waiters = new ArrayList<>(2);
  }

  private static final Object LOCK = new Object();
  private static final Map<Integer, State> STATES = new HashMap<>();
  private static final List<Integer> EVENT_IDS = new ArrayList<>();
  private static final List<Integer> SEMAPHORE_MAX = new ArrayList<>();

  private Sync() {}

  private static int allocate(List<Integer> ids, int value) {
    for (int i = 0; i < ids.size(); i++) {
      if (ids.get(i) == null) {
        ids.set(i, value);
        return i;
      }
    }
    ids.add(value);
    return ids.size() - 1;
  }

  public static int createEvent(boolean manualReset, boolean initialState) {
    synchronized (LOCK) {
      int handle = (HANDLE_TYPE_EVENT << 24) | (allocate(EVENT_IDS, 0) & 0xffffff);
      State s = new State();
      s.signaled = initialState ? 1 : 0;
      s.autoReset = !manualReset;
      STATES.put(handle, s);
      return handle;
    }
  }

  public static void destroyEvent(int handle) {
    if ((handle >> 24) != HANDLE_TYPE_EVENT) {
      return;
    }
    destroySignalObject(handle);
    synchronized (LOCK) {
      int i = handle & 0xffffff;
      if (i < EVENT_IDS.size()) {
        EVENT_IDS.set(i, null);
      }
    }
  }

  public static void setEvent(int handle) {
    if ((handle >> 24) == HANDLE_TYPE_EVENT) {
      setSignalObject(handle);
    }
  }

  public static void resetEvent(int handle) {
    if ((handle >> 24) == HANDLE_TYPE_EVENT) {
      resetSignalObject(handle);
    }
  }

  public static int createSemaphore(int initialCount, int maximumCount) {
    synchronized (LOCK) {
      int handle = (HANDLE_TYPE_SEMAPHORE << 24) | (allocate(SEMAPHORE_MAX, maximumCount) & 0xffffff);
      State s = new State();
      s.signaled = initialCount;
      s.autoReset = true;
      STATES.put(handle, s);
      return handle;
    }
  }

  public static void destroySemaphore(int handle) {
    if ((handle >> 24) != HANDLE_TYPE_SEMAPHORE) {
      return;
    }
    destroySignalObject(handle);
    synchronized (LOCK) {
      int i = handle & 0xffffff;
      if (i < SEMAPHORE_MAX.size()) {
        SEMAPHORE_MAX.set(i, null);
      }
    }
  }

  public static boolean releaseSemaphore(int handle, int releaseCount) {
    if ((handle >> 24) != HANDLE_TYPE_SEMAPHORE || releaseCount <= 0) {
      return false;
    }
    synchronized (LOCK) {
      State s = STATES.get(handle);
      if (s == null) {
        return false;
      }
      Integer max = SEMAPHORE_MAX.get(handle & 0xffffff);
      if (max == null || max - s.signaled < releaseCount) {
        return false;
      }
      s.signaled += releaseCount;
      wake(s.waiters, false);
      return true;
    }
  }

  private static void wake(List<Waiter> waiters, boolean first) {
    for (Waiter w : waiters) {
      synchronized (w) {
        w.notified = true;
        w.notifyAll();
      }
      if (first) {
        break;
      }
    }
  }

  public static void createSignalObject(int handle, boolean manualReset, boolean initialState) {
    synchronized (LOCK) {
      State s = STATES.computeIfAbsent(handle, h -> new State());
      s.signaled = initialState ? 1 : 0;
      s.autoReset = !manualReset;
    }
  }

  public static void setSignalObject(int handle) {
    synchronized (LOCK) {
      State s = STATES.get(handle);
      if (s != null) {
        s.signaled = 1;
        wake(s.waiters, s.autoReset);
      }
    }
  }

  public static void resetSignalObject(int handle) {
    synchronized (LOCK) {
      State s = STATES.get(handle);
      if (s != null) {
        s.signaled = 0;
      }
    }
  }

  public static void destroySignalObject(int handle) {
    synchronized (LOCK) {
      State s = STATES.remove(handle);
      if (s != null) {
        // A waiter must not mistake a later object with the same handle for the destroyed one.
        for (Waiter w : s.waiters) {
          w.destroyed.add(handle);
        }
        wake(s.waiters, false);
      }
    }
  }

  private static final class Waiter {
    boolean notified;
    final Set<Integer> destroyed = new HashSet<>();
  }

  /** Result of a wait: the signaled handles (error bit set for unknown ones) and the timeout flag. */
  public record WaitResult(int[] signaled, boolean timedOut) {}

  /**
   * WaitForObjects. timeoutSeconds below 0 waits forever. A handle that does not exist counts as
   * signaled with the error bit set (0x80000000).
   */
  public static WaitResult waitForObjects(int[] handles, double timeoutSeconds) {
    Waiter waiter = new Waiter();
    boolean added = false;
    boolean timedOut = false;
    int[] signaled = new int[handles.length];
    int count;
    long deadline =
        timeoutSeconds < 0 ? Long.MAX_VALUE : System.nanoTime() + (long) (timeoutSeconds * 1e9);
    try {
      while (true) {
        synchronized (LOCK) {
          count = 0;
          for (int handle : handles) {
            State s = STATES.get(handle);
            if (s == null || waiter.destroyed.contains(handle)) {
              signaled[count++] = handle | ERROR_BIT;
            } else if (s.signaled > 0) {
              signaled[count++] = handle;
              if (s.autoReset) {
                s.signaled = Math.max(0, s.signaled - 1);
              }
            }
          }
          if (timedOut || count != 0) {
            break;
          }
          if (timeoutSeconds == 0) {
            timedOut = true;
            break;
          }
          if (!added) {
            added = true;
            for (int handle : handles) {
              STATES.computeIfAbsent(handle, h -> new State()).waiters.add(waiter);
            }
          }
        }
        synchronized (waiter) {
          try {
            while (!waiter.notified) {
              if (timeoutSeconds < 0) {
                waiter.wait();
              } else {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                  timedOut = true;
                  break;
                }
                waiter.wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new WaitResult(new int[0], false);
          }
          waiter.notified = false;
        }
      }
    } finally {
      if (added) {
        synchronized (LOCK) {
          for (int handle : handles) {
            State s = STATES.get(handle);
            if (s != null) {
              s.waiters.remove(waiter);
            }
          }
        }
      }
    }
    return new WaitResult(java.util.Arrays.copyOf(signaled, count), timedOut);
  }

  /** WaitForObject: true if the object was signaled and exists. */
  public static boolean waitForObject(int handle, double timeoutSeconds, boolean[] timedOut) {
    WaitResult r = waitForObjects(new int[] {handle}, timeoutSeconds);
    if (timedOut != null) {
      timedOut[0] = r.timedOut();
    }
    return r.signaled().length != 0 && (r.signaled()[0] & ERROR_BIT) == 0;
  }
}
