package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.Notifiers;

/** Notifier natives; alarms are driven by sim time (see sim/Notifiers). */
public final class NotifierJNIImpl {
  private NotifierJNIImpl() {}

  public static int createNotifier() {
    return Notifiers.create();
  }

  public static void setNotifierName(int notifierHandle, String name) {
    Notifiers.setName(notifierHandle, name);
  }

  public static void destroyNotifier(int notifierHandle) {
    Notifiers.destroy(notifierHandle);
  }

  public static void setNotifierAlarm(
      int notifierHandle, long alarmTime, long intervalTime, boolean absolute, boolean ack) {
    Notifiers.setAlarm(notifierHandle, alarmTime, intervalTime, absolute, ack);
  }

  public static void cancelNotifierAlarm(int notifierHandle, boolean ack) {
    Notifiers.cancelAlarm(notifierHandle, ack);
  }

  public static void acknowledgeNotifierAlarm(int notifierHandle) {
    Notifiers.acknowledge(notifierHandle);
  }

  public static int getNotifierOverrun(int notifierHandle) {
    return Notifiers.overrun(notifierHandle);
  }
}
