package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.DriverStationData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Sim;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Port of hal/src/main/native/sim/HAL.cpp and Main.cpp. */
public final class HALImpl {
  private static final Object EXIT_LOCK = new Object();
  private static boolean exited;

  private HALImpl() {}

  public static boolean initialize(int timeout, int mode) {
    return Sim.initialize();
  }

  public static void shutdown() {}

  public static boolean hasMain() {
    return false;
  }

  /** Default main: sleeps until exitMain. */
  public static void runMain() {
    synchronized (EXIT_LOCK) {
      while (!exited) {
        try {
          EXIT_LOCK.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  public static void exitMain() {
    synchronized (EXIT_LOCK) {
      exited = true;
      EXIT_LOCK.notifyAll();
    }
  }

  public static void terminate() {}

  /** Callbacks of HALSIM_RegisterSimPeriodic{Before,After}Callback. */
  public static final List<Runnable> SIM_PERIODIC_BEFORE = new CopyOnWriteArrayList<>();

  public static final List<Runnable> SIM_PERIODIC_AFTER = new CopyOnWriteArrayList<>();

  public static void simPeriodicBeforeNative() {
    for (Runnable r : SIM_PERIODIC_BEFORE) {
      r.run();
    }
  }

  public static void simPeriodicAfterNative() {
    for (Runnable r : SIM_PERIODIC_AFTER) {
      r.run();
    }
  }

  public static boolean getBrownedOut() {
    return false;
  }

  public static boolean getSystemActive() {
    Sim.ensureInitialized();
    return DriverStationData.get().enabled.get();
  }

  public static boolean getRSLState() {
    return false;
  }

  public static int getCommsDisableCount() {
    return 0;
  }

  public static boolean getSystemTimeValid() {
    return true;
  }

  public static int getPortWithModule(byte module, byte channel) {
    return Handles.port(module, channel);
  }

  public static int getPort(byte channel) {
    return Handles.port(1, channel);
  }
}
