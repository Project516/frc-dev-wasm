package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Notifiers;

/** Notifier natives; alarms are driven by sim time (see sim/Notifiers). */
public final class NotifierJNIImpl {
  private NotifierJNIImpl() {}

  public static int initializeNotifier() {
    int handle = Notifiers.initialize();
    return handle == Handles.INVALID ? 0 : handle;
  }

  public static boolean setHALThreadPriority(boolean realTime, int priority) {
    return true;
  }

  public static void setNotifierName(int notifierHandle, String name) {
    Notifiers.setName(notifierHandle, name);
  }

  public static void stopNotifier(int notifierHandle) {
    Notifiers.stop(notifierHandle);
  }

  public static void cleanNotifier(int notifierHandle) {
    if (notifierHandle != Handles.INVALID) {
      Notifiers.clean(notifierHandle);
    }
  }

  public static void updateNotifierAlarm(int notifierHandle, long triggerTime) {
    Notifiers.updateAlarm(notifierHandle, triggerTime);
  }

  public static void cancelNotifierAlarm(int notifierHandle) {
    Notifiers.cancelAlarm(notifierHandle);
  }

  public static long waitForNotifierAlarm(int notifierHandle) {
    return Notifiers.waitForAlarm(notifierHandle);
  }
}
