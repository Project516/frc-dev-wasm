package dev.project516.frcdevwasm.shim.util;

import java.util.ArrayList;
import java.util.List;
import org.wpilib.util.AlertDataJNI.AlertInfo;
import org.wpilib.util.AlertException;

/** The alert store of wpiutil (Alert.cpp). */
final class Alerts {
  private static final int ALERT_ERROR = -1;
  private static final int ALERT_ALREADY_ALLOCATED = -2;
  private static final int HANDLE_TYPE = 23;
  private static final int INDEX_MASK = 0xffff;
  private static final int GENERATION_MASK = 0xff;

  private static final class Alert {
    final String group;
    final String id;
    final int level;
    final int generation;
    String text;
    long activeStartTime;

    Alert(String group, String id, String text, int level, int generation) {
      this.group = group;
      this.id = id;
      this.text = text;
      this.level = level;
      this.generation = generation;
    }
  }

  private static final List<Alert> ALERTS = new ArrayList<>();
  private static int nextGeneration;

  private Alerts() {}

  static synchronized int create(String group, String id, String text, int level) {
    for (Alert a : ALERTS) {
      if (a != null && a.group.equals(group) && a.id.equals(id) && a.level == level) {
        throw new AlertException("Alert already allocated", ALERT_ALREADY_ALLOCATED);
      }
    }
    int generation = nextGeneration++ & GENERATION_MASK;
    int index = ALERTS.indexOf(null);
    Alert alert = new Alert(group, id, text, level, generation);
    if (index < 0) {
      index = ALERTS.size();
      ALERTS.add(alert);
    } else {
      ALERTS.set(index, alert);
    }
    if (index > INDEX_MASK) {
      ALERTS.set(index, null);
      throw new AlertException("Alert operation failed", ALERT_ERROR);
    }
    return (HANDLE_TYPE << 24) | (generation << 16) | index;
  }

  static synchronized void destroy(int handle) {
    Alert a = find(handle);
    if (a != null) {
      ALERTS.set(handle & INDEX_MASK, null);
    }
  }

  private static Alert find(int handle) {
    if (handle >>> 24 != HANDLE_TYPE) {
      return null;
    }
    int index = handle & INDEX_MASK;
    if (index >= ALERTS.size()) {
      return null;
    }
    Alert a = ALERTS.get(index);
    return a != null && a.generation == ((handle >>> 16) & GENERATION_MASK) ? a : null;
  }

  private static Alert get(int handle) {
    Alert a = find(handle);
    if (a == null) {
      throw new AlertException("Alert operation failed", ALERT_ERROR);
    }
    return a;
  }

  static synchronized void setActive(int handle, boolean active, long now) {
    Alert a = get(handle);
    if (!active) {
      a.activeStartTime = 0;
    } else if (a.activeStartTime == 0) {
      a.activeStartTime = now == 0 ? 1 : now;
    }
  }

  static synchronized boolean isActive(int handle) {
    return get(handle).activeStartTime != 0;
  }

  static synchronized void setText(int handle, String text) {
    get(handle).text = text;
  }

  static synchronized String getText(int handle) {
    return get(handle).text;
  }

  static synchronized AlertInfo[] all() {
    List<AlertInfo> out = new ArrayList<>();
    for (Alert a : ALERTS) {
      if (a != null) {
        out.add(new AlertInfo(a.group, a.id, a.text, a.activeStartTime, a.level));
      }
    }
    return out.toArray(new AlertInfo[0]);
  }

  static synchronized void reset() {
    ALERTS.clear();
  }
}
