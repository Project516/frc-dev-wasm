package dev.project516.frcdevwasm.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * HAL notifiers with alarms driven by sim time. Port of hal/src/main/native/sim/Notifier.cpp. Times
 * are nanoseconds. A notifier handle is a signal object (see {@link Sync}) that its Java thread waits
 * on.
 */
public final class Notifiers {
  private static final long NO_ALARM = Long.MAX_VALUE;
  private static final long MAX_WAIT_MILLIS = 1_000_000_000L;

  private static final class Notifier {
    String name = "";
    long alarmTime = NO_ALARM;
    long intervalTime;
    int userOverrunCount;
    int overrunCount;
    boolean handlerSignaled;
  }

  private record Entry(int handle, Notifier notifier) {}

  private static final Object LOCK = new Object();
  private static final Handles.Unlimited<Notifier> HANDLES =
      new Handles.Unlimited<>(Handles.NOTIFIER);
  private static final List<Entry> QUEUE = new ArrayList<>();
  private static boolean paused;
  private static boolean threadStarted;
  private static long alarmSetCount;

  private Notifiers() {}

  public static int create() {
    Sim.ensureInitialized();
    Notifier n = new Notifier();
    int handle = HANDLES.allocate(n);
    synchronized (LOCK) {
      Sync.createSignalObject(handle, false, false);
      if (!threadStarted) {
        threadStarted = true;
        Thread t = new Thread(Notifiers::run, "frcdevwasm-notifier");
        t.setDaemon(true);
        t.start();
      }
    }
    return handle;
  }

  public static void setName(int handle, String name) {
    Notifier n = HANDLES.get(handle);
    if (n != null) {
      synchronized (LOCK) {
        n.name = name;
      }
    }
  }

  public static void destroy(int handle) {
    Sync.destroySignalObject(handle);
    Notifier n = HANDLES.free(handle);
    if (n != null) {
      synchronized (LOCK) {
        QUEUE.removeIf(e -> e.notifier() == n);
        LOCK.notifyAll();
      }
    }
  }

  public static void setAlarm(
      int handle, long alarmTime, long intervalTime, boolean absolute, boolean ack) {
    Notifier n = HANDLES.get(handle);
    if (n == null) {
      return;
    }
    synchronized (LOCK) {
      if (ack) {
        acknowledgeLocked(handle, n);
      }
      if (!absolute) {
        alarmTime += SimTime.now();
      }
      QUEUE.removeIf(e -> e.notifier() == n);
      n.alarmTime = alarmTime;
      n.intervalTime = intervalTime;
      n.overrunCount = 0;
      QUEUE.add(new Entry(handle, n));
      alarmSetCount++;
      LOCK.notifyAll();
    }
  }

  public static void cancelAlarm(int handle, boolean ack) {
    Notifier n = HANDLES.get(handle);
    if (n == null) {
      return;
    }
    synchronized (LOCK) {
      if (ack) {
        acknowledgeLocked(handle, n);
      }
      QUEUE.removeIf(e -> e.notifier() == n);
      n.alarmTime = NO_ALARM;
    }
  }

  public static void acknowledge(int handle) {
    Notifier n = HANDLES.get(handle);
    if (n != null) {
      synchronized (LOCK) {
        acknowledgeLocked(handle, n);
      }
    }
  }

  private static void acknowledgeLocked(int handle, Notifier n) {
    n.handlerSignaled = false;
    Sync.resetSignalObject(handle);
    LOCK.notifyAll();
  }

  public static int overrun(int handle) {
    Notifier n = HANDLES.get(handle);
    if (n == null) {
      return -1;
    }
    synchronized (LOCK) {
      return n.userOverrunCount;
    }
  }

  private static void run() {
    synchronized (LOCK) {
      while (true) {
        Entry top = top();
        if (paused || top == null) {
          waitLock(0);
          continue;
        }
        long cur = SimTime.now();
        if (top.notifier().alarmTime > cur) {
          long nanos = top.notifier().alarmTime - cur;
          waitLock(Math.min((nanos + 999_999) / 1_000_000, MAX_WAIT_MILLIS));
        }
        if (paused) {
          continue;
        }
        processAlarms(null);
      }
    }
  }

  private static Entry top() {
    Entry best = null;
    for (Entry e : QUEUE) {
      if (best == null || e.notifier().alarmTime < best.notifier().alarmTime) {
        best = e;
      }
    }
    return best;
  }

  private static void waitLock(long millis) {
    try {
      LOCK.wait(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void processAlarms(List<Entry> signaled) {
    long cur = SimTime.now();
    for (Entry alarm = top();
        alarm != null && alarm.notifier().alarmTime <= cur;
        alarm = top()) {
      Notifier n = alarm.notifier();
      QUEUE.remove(alarm);
      if (n.intervalTime > 0) {
        n.alarmTime += n.intervalTime;
        if (cur >= n.alarmTime) {
          int missed = (int) ((cur - n.alarmTime) / n.intervalTime) + 1;
          n.overrunCount += missed;
          n.alarmTime += missed * n.intervalTime;
        }
        QUEUE.add(alarm);
      } else {
        n.alarmTime = NO_ALARM;
      }
      if (!n.handlerSignaled) {
        n.handlerSignaled = true;
        if (signaled != null) {
          signaled.add(alarm);
        }
        n.userOverrunCount = n.overrunCount;
        n.overrunCount = 0;
        Sync.setSignalObject(alarm.handle());
      }
    }
  }

  public static void pause() {
    synchronized (LOCK) {
      paused = true;
    }
  }

  public static void resume() {
    synchronized (LOCK) {
      paused = false;
      LOCK.notifyAll();
    }
  }

  /** WakeupNotifiers: processes the alarms that are due without waiting for the handlers. */
  public static void wakeup() {
    synchronized (LOCK) {
      processAlarms(null);
    }
  }

  /** WaitNotifiers: waits until every signaled notifier acknowledged its last alarm. */
  public static void waitAll() {
    synchronized (LOCK) {
      List<Entry> signaled = new ArrayList<>();
      HANDLES.forEach(
          (handle, n) -> {
            if (n.handlerSignaled) {
              signaled.add(new Entry(handle, n));
            }
          });
      waitForAcknowledge(signaled);
    }
  }

  /** WakeupWaitNotifiers: processes due alarms, then waits for their handlers to acknowledge. */
  public static void wakeupWaitAll() {
    synchronized (LOCK) {
      List<Entry> signaled = new ArrayList<>();
      processAlarms(signaled);
      waitForAcknowledge(signaled);
    }
  }

  private static void waitForAcknowledge(List<Entry> signaled) {
    while (true) {
      signaled.removeIf(e -> HANDLES.get(e.handle()) == null || !e.notifier().handlerSignaled);
      if (signaled.isEmpty()) {
        return;
      }
      waitLock(1);
    }
  }

  /** Earliest alarm of all notifiers, or Long.MAX_VALUE if none. */
  public static long nextTimeout() {
    synchronized (LOCK) {
      Entry top = top();
      return top == null ? NO_ALARM : top.notifier().alarmTime;
    }
  }

  public static int count() {
    int[] count = new int[1];
    HANDLES.forEach((handle, n) -> count[0]++);
    return count[0];
  }

  public static long alarmSetCount() {
    synchronized (LOCK) {
      return alarmSetCount;
    }
  }

  /** HALSIM_StepTiming: advance sim time, running notifiers in timeout order. */
  public static void step(long delta) {
    waitAll();
    while (delta > 0) {
      long cur = SimTime.now();
      long next = nextTimeout();
      long step = next <= cur ? 0 : Math.min(delta, next - cur);
      SimTime.advance(step);
      delta -= step;
      wakeupWaitAll();
      if (step == 0 && nextTimeout() <= cur) {
        break;
      }
    }
  }

  /** HALSIM_StepTimingAsync. */
  public static void stepAsync(long delta) {
    SimTime.advance(delta);
    wakeup();
  }
}
