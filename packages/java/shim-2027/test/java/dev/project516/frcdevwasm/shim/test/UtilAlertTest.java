package dev.project516.frcdevwasm.shim.test;

import org.wpilib.simulation.AlertSim;
import org.wpilib.util.Alert;
import org.wpilib.util.AlertException;
import org.wpilib.util.WPIUtilJNI;
import org.wpilib.util.WPIUtilJNIWrapper;

/** Alerts through WPIUtilJNI and AlertDataJNI, and wpiutil time. */
public final class UtilAlertTest {
  static {
    WPIUtilJNIWrapper.Helper.setExtractOnStaticLoad(false);
  }

  public static void testAlertLifecycle() {
    AlertSim.resetData();
    try (Alert a = new Alert("grp", "id1", "first", Alert.Level.MEDIUM)) {
      T.check(!a.get(), "starts inactive");
      T.eq("first", a.getText());
      T.eq(1, AlertSim.getCount());
      T.eq(0, AlertSim.getActive().length);

      a.set(true);
      T.check(a.get(), "active");
      var active = AlertSim.getActive();
      T.eq(1, active.length);
      T.eq("grp", active[0].group);
      T.eq("id1", active[0].id);
      T.eq(Alert.Level.MEDIUM, active[0].level);
      T.check(active[0].isActive(), "isActive");
      long start = active[0].activeStartTime;
      T.check(start != 0, "start time set");

      a.set(true);
      T.eq(start, AlertSim.getActive()[0].activeStartTime, "setting again keeps the start time");

      a.setText("second");
      T.eq("second", a.getText());
      T.eq("second", AlertSim.getAll()[0].text);

      a.set(false);
      T.check(!a.get(), "inactive");
      T.eq(0L, AlertSim.getAll()[0].activeStartTime);
    }
    T.eq(0, AlertSim.getCount());
  }

  public static void testDuplicateAlertThrows() {
    AlertSim.resetData();
    try (Alert a = new Alert("grp", "dup", "x", Alert.Level.LOW)) {
      AlertException e = T.throwsA(AlertException.class, () -> new Alert("grp", "dup", "y", Alert.Level.LOW));
      T.eq(-2, e.getReason());
      // another level is another alert
      new Alert("grp", "dup", "z", Alert.Level.HIGH).close();
    }
  }

  public static void testResetDropsAlerts() {
    AlertSim.resetData();
    Alert a = new Alert("grp", "gone", "x", Alert.Level.LOW);
    T.eq(1, AlertSim.getCount());
    AlertSim.resetData();
    T.eq(0, AlertSim.getCount());
    T.throwsA(AlertException.class, () -> a.set(true));
  }

  public static void testMockTime() {
    WPIUtilJNI.enableMockTime();
    try {
      T.eq(0L, WPIUtilJNI.now());
      T.eq(0L, WPIUtilJNI.getProgramStartTime());
      WPIUtilJNI.setMockTime(5_000L);
      T.eq(5_000L, WPIUtilJNI.now());
      AlertSim.resetData();
      try (Alert a = new Alert("grp", "mock", "x", Alert.Level.LOW)) {
        a.set(true);
        T.eq(5_000L, AlertSim.getActive()[0].activeStartTime);
      }
    } finally {
      WPIUtilJNI.disableMockTime();
    }
  }

  public static void testSystemTimeIsNanosecondsSinceEpoch() {
    long ns = WPIUtilJNI.getSystemTime();
    long ms = System.currentTimeMillis();
    T.check(Math.abs(ns / 1_000_000 - ms) < 1000, "system time " + ns);
  }
}
