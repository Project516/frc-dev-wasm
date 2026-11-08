package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Child JVM entry point that runs a robot program on the in-process bridge, plays a scripted driver
 * station and prints RESULT lines at exit. Args: main class, teleop milliseconds.
 */
public final class RobotHarness {
  private RobotHarness() {}

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
    AtomicBoolean exited = new AtomicBoolean();
    AtomicInteger exitCode = new AtomicInteger(-1);
    AtomicInteger pwmMessages = new AtomicInteger();
    AtomicInteger consoleMessages = new AtomicInteger();
    AtomicInteger probeLines = new AtomicInteger();
    AtomicInteger ntAnnounces = new AtomicInteger();
    AtomicInteger ntValues = new AtomicInteger();
    t.onSend(m -> {
      String type = (String) m.get("type");
      Map<?, ?> data = (Map<?, ?>) m.get("data");
      switch (type) {
        case "console" -> {
          consoleMessages.incrementAndGet();
          String text = String.valueOf(data.get("text"));
          if (text.contains("Robot program starting")) {
            started.set(true);
          }
          if (text.contains("PROBE")) {
            probeLines.incrementAndGet();
          }
        }
        case "PWM" -> {
          if ("0".equals(m.get("device")) && data.containsKey("<speed")) {
            pwmMessages.incrementAndGet();
          }
        }
        case "exit" -> {
          exitCode.set(((Number) data.get("code")).intValue());
          exited.set(true);
        }
        case "nt.announce" -> ntAnnounces.incrementAndGet();
        case "nt.value" -> ntValues.incrementAndGet();
        default -> {}
      }
    });

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      for (int i = 0; i < 100 && !exited.get(); i++) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          break;
        }
      }
      System.out.println("RESULT exit=" + exitCode.get());
      System.out.println("RESULT pwm=" + pwmMessages.get());
      System.out.println("RESULT probe=" + probeLines.get());
      System.out.println("RESULT console=" + consoleMessages.get());
      System.out.println("RESULT ntAnnounce=" + ntAnnounces.get());
      System.out.println("RESULT ntValue=" + ntValues.get());
    }));

    Thread script = new Thread(() -> {
      try {
        for (int i = 0; i < 3000 && !started.get(); i++) {
          Thread.sleep(10);
        }
        Thread.sleep(500);
        t.inject("{\"type\":\"hello\",\"device\":\"\",\"data\":{}}");
        t.inject("DriverStation", "", map(">ds", true, ">enabled", false, ">autonomous", false, ">new_data", true));
        Thread.sleep(300);
        t.inject("DriverStation", "", map(">enabled", true, ">autonomous", false, ">test", false, ">new_data", true));
        Thread.sleep(teleopMs);
        t.inject("{\"type\":\"stop\",\"device\":\"\",\"data\":{}}");
      } catch (InterruptedException e) {
        // ends the script
      }
    }, "harness-script");
    script.setDaemon(true);
    script.start();

    try {
      Class.forName(mainClass).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
    } catch (InvocationTargetException e) {
      e.getCause().printStackTrace();
      System.exit(1);
    }
  }
}
