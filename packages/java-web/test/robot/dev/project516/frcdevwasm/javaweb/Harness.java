package dev.project516.frcdevwasm.javaweb;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a robot main class on the in-process bridge, enables it in teleop and stops it after a while, then prints
 * what it wrote to the console as CONSOLE lines. Args: main class, teleop milliseconds.
 */
public final class Harness {
  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  public static void main(String[] args) throws Exception {
    String mainClass = args[0];
    long teleopMs = Long.parseLong(args[1]);
    LocalTransport t = BridgeLoop.local();
    AtomicBoolean started = new AtomicBoolean();
    List<String> console = new ArrayList<>();
    t.onSend(m -> {
      if (!"console".equals(m.get("type"))) {
        return;
      }
      String text = String.valueOf(m.get("data"));
      synchronized (console) {
        console.add(text);
      }
      if (text.contains("Robot program starting")) {
        started.set(true);
      }
    });
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      synchronized (console) {
        console.forEach((c) -> System.out.println("CONSOLE " + c.replace("\n", "\\n")));
      }
    }));
    Thread script = new Thread(() -> {
      try {
        for (int i = 0; i < 3000 && !started.get(); i++) {
          Thread.sleep(10);
        }
        Thread.sleep(500);
        t.inject("{\"type\":\"hello\",\"device\":\"\",\"data\":{}}");
        t.inject("DriverStation", "", map(">ds", true, ">enabled", false, ">new_data", true));
        Thread.sleep(300);
        t.inject("DriverStation", "", map(">enabled", true, ">robotMode", 2, ">new_data", true));
        Thread.sleep(teleopMs);
        t.inject("{\"type\":\"stop\",\"device\":\"\",\"data\":{}}");
      } catch (InterruptedException e) {
        // ends the script
      }
    });
    script.setDaemon(true);
    script.start();
    Class.forName(mainClass).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
  }
}
