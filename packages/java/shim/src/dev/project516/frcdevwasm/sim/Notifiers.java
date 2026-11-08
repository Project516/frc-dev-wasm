package dev.project516.frcdevwasm.sim;

/**
 * HAL notifiers with alarms driven by sim time. Port of hal/src/main/native/sim/Notifier.cpp.
 * Trigger times are unsigned microseconds; -1 stands for UINT64_MAX (no alarm).
 */
public final class Notifiers {
  private static final class Notifier {
    String name = "";
    long waitTime = -1;
    boolean active = true;
    boolean waitTimeValid;
    boolean waitingForAlarm;
    long waitCount;
  }

  public record Info(int handle, String name, long timeout, boolean waitTimeValid) {}

  private static final Handles.Unlimited<Notifier> HANDLES = new Handles.Unlimited<>(Handles.NOTIFIER);
  private static final Object WAITERS = new Object();
  private static final long MAX_WAIT_MICROS = 1_000_000_000L;
  private static volatile boolean paused;

  private Notifiers() {}

  public static int initialize() {
    Sim.ensureInitialized();
    return HANDLES.allocate(new Notifier());
  }

  public static void setName(int handle, String name) {
    Notifier n = HANDLES.get(handle);
    if (n != null) {
      synchronized (n) {
        n.name = name;
      }
    }
  }

  public static void stop(int handle) {
    Notifier n = HANDLES.get(handle);
    if (n != null) {
      deactivate(n);
    }
  }

  public static void clean(int handle) {
    Notifier n = HANDLES.free(handle);
    if (n != null) {
      deactivate(n);
    }
  }

  private static void deactivate(Notifier n) {
    synchronized (n) {
      n.active = false;
      n.waitTimeValid = false;
      n.notifyAll();
    }
  }

  public static void updateAlarm(int handle, long triggerTime) {
    Notifier n = HANDLES.get(handle);
    if (n != null) {
      synchronized (n) {
        n.waitTime = triggerTime;
        n.waitTimeValid = triggerTime != -1;
        n.notifyAll();
      }
    }
  }

  public static void cancelAlarm(int handle) {
    Notifier n = HANDLES.get(handle);
    if (n != null) {
      synchronized (n) {
        n.waitTimeValid = false;
      }
    }
  }

  /** Blocks until the alarm time is reached in sim time. Returns the time, or 0 if stopped. */
  public static long waitForAlarm(int handle) {
    Notifier n = HANDLES.get(handle);
    if (n == null) {
      return 0;
    }
    synchronized (WAITERS) {
      synchronized (n) {
        n.waitingForAlarm = true;
        n.waitCount++;
      }
      WAITERS.notifyAll();
    }
    synchronized (n) {
      try {
        boolean waited = false;
        while (n.active) {
          long cur = SimTime.fpgaTime();
          if (n.waitTimeValid && Long.compareUnsigned(cur, n.waitTime) >= 0) {
            // CheerpJ threads only switch when one blocks, so an overrunning loop must still block
            // once or the program's other threads never run.
            if (!waited) {
              waited = true;
              n.wait(1);
              continue;
            }
            n.waitTimeValid = false;
            n.waitingForAlarm = false;
            return cur;
          }
          long micros = n.waitTime - cur;
          if (!n.waitTimeValid || paused || micros <= 0 || micros > MAX_WAIT_MICROS) {
            micros = MAX_WAIT_MICROS;
          }
          n.wait((micros + 999) / 1000);
          waited = true;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      n.waitingForAlarm = false;
      return 0;
    }
  }

  public static void pause() {
    paused = true;
  }

  public static void resume() {
    paused = false;
    wakeup();
  }

  public static void wakeup() {
    for (Notifier n : HANDLES.items()) {
      synchronized (n) {
        n.notifyAll();
      }
    }
  }

  /** Waits until every active notifier is blocked in waitForAlarm. */
  public static void waitAll() {
    synchronized (WAITERS) {
      java.util.List<Notifier> waiters = new java.util.ArrayList<>();
      for (Notifier n : HANDLES.items()) {
        synchronized (n) {
          if (n.active && !n.waitingForAlarm) {
            waiters.add(n);
          }
        }
      }
      while (true) {
        waiters.removeIf(
            n -> {
              synchronized (n) {
                return !(n.active && !n.waitingForAlarm);
              }
            });
        if (waiters.isEmpty()) {
          return;
        }
        waitOn(WAITERS);
      }
    }
  }

  /** Wakes notifiers whose alarm expired, then waits until each re-enters waitForAlarm. */
  public static void wakeupWaitAll() {
    synchronized (WAITERS) {
      long cur = SimTime.fpgaTime();
      java.util.List<Notifier> waiters = new java.util.ArrayList<>();
      java.util.List<Long> counts = new java.util.ArrayList<>();
      for (Notifier n : HANDLES.items()) {
        synchronized (n) {
          if (n.active && n.waitTimeValid && Long.compareUnsigned(cur, n.waitTime) >= 0) {
            waiters.add(n);
            counts.add(n.waitCount);
            n.notifyAll();
          }
        }
      }
      while (true) {
        for (int i = waiters.size() - 1; i >= 0; i--) {
          Notifier n = waiters.get(i);
          synchronized (n) {
            if (!(n.active && n.waitCount == counts.get(i))) {
              waiters.remove(i);
              counts.remove(i);
            }
          }
        }
        if (waiters.isEmpty()) {
          return;
        }
        waitOn(WAITERS);
      }
    }
  }

  private static void waitOn(Object monitor) {
    try {
      monitor.wait(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Earliest alarm of all active notifiers, or -1 (UINT64_MAX) if none. */
  public static long nextTimeout() {
    long timeout = -1;
    for (Notifier n : HANDLES.items()) {
      synchronized (n) {
        if (n.active && n.waitTimeValid && Long.compareUnsigned(timeout, n.waitTime) > 0) {
          timeout = n.waitTime;
        }
      }
    }
    return timeout;
  }

  public static int count() {
    int count = 0;
    for (Notifier n : HANDLES.items()) {
      synchronized (n) {
        if (n.active) {
          count++;
        }
      }
    }
    return count;
  }

  public static java.util.List<Info> info() {
    java.util.List<Info> out = new java.util.ArrayList<>();
    HANDLES.forEach(
        (handle, n) -> {
          synchronized (n) {
            if (n.active) {
              String name = n.name.isEmpty() ? "Notifier" + Handles.index(handle, Handles.NOTIFIER) : n.name;
              out.add(new Info(handle, name, n.waitTime, n.waitTimeValid));
            }
          }
        });
    return out;
  }

  /** HALSIM_StepTiming: advance sim time, running notifiers in timeout order. */
  public static void step(long delta) {
    waitAll();
    while (delta > 0) {
      long cur = SimTime.fpgaTime();
      long next = nextTimeout();
      long untilNext = next - cur;
      long step = Long.compareUnsigned(delta, untilNext) < 0 ? delta : untilNext;
      SimTime.advance(step);
      delta -= step;
      wakeupWaitAll();
    }
  }

  /** HALSIM_StepTimingAsync. */
  public static void stepAsync(long delta) {
    SimTime.advance(delta);
    wakeup();
  }
}
