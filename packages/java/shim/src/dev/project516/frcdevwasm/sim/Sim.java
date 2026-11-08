package dev.project516.frcdevwasm.sim;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.shim.networktables.NtBridge;
import java.util.LinkedHashMap;
import java.util.Map;

/** HAL lifecycle: initialization, bridge wiring and simulation timing control. */
public final class Sim {
  private static volatile boolean initialized;
  private static volatile boolean programStarted;

  private Sim() {}

  public static void ensureInitialized() {
    if (!initialized) {
      initialize();
    }
  }

  /** HAL_Initialize. Idempotent. */
  public static synchronized boolean initialize() {
    if (initialized) {
      return true;
    }
    initialized = true;
    BridgeLoop.start();
    Deploy.extractIfRequested();
    registerWsTypes();
    SimDevicesWs.start();
    startNetworkTables();
    InterruptSources.current = new SimInterruptSources();
    BridgeLoop.onHello(Ws::resendAll);
    BridgeLoop.on("sim.timing", (type, device, data) -> timing(data));
    SimTime.restart();
    DriverStationData.get().reset();
    return true;
  }

  public static boolean isInitialized() {
    return initialized;
  }

  /** Message types the page can send before the robot touches the device. */
  private static void registerWsTypes() {
    Ws.type("PWM", PwmData::ws);
    WsDigital.register();
    WsAnalog.register();
    WsPower.register();
    WsBus.register();
    Ws.type("DriverStation", id -> DriverStationData.get().wsDriverStation());
    Ws.type("Joystick", id -> DriverStationData.get().wsJoystick(id));
  }

  /** Mirrors NetworkTables to the page. */
  private static void startNetworkTables() {
    NtBridge.useHalClock();
    NtBridge.setSink(BridgeLoop::sendJson);
    BridgeLoop.onHello(() -> NtBridge.handleMessage("hello", "{}"));
    BridgeLoop.on(
        "nt.",
        (type, device, data) ->
            NtBridge.handleMessage(type, dev.project516.frcdevwasm.bridge.Json.write(data)));
  }

  private static void timing(Map<String, Object> data) {
    Object op = data.get("op");
    if ("pause".equals(op)) {
      pauseTiming();
    } else if ("resume".equals(op)) {
      resumeTiming();
    } else if ("step".equals(op)) {
      long us = (long) dev.project516.frcdevwasm.bridge.Json.toDouble(data.get("us"));
      Thread t = new Thread(() -> stepTiming(us), "frcdevwasm-step");
      t.setDaemon(true);
      t.start();
    }
  }

  private static void sendPaused(boolean paused) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("paused", paused);
    BridgeLoop.send("sim.timing", "", data);
  }

  /** HALSIM_PauseTiming. */
  public static void pauseTiming() {
    boolean was = SimTime.isPaused();
    SimTime.pause();
    Notifiers.pause();
    if (!was) {
      sendPaused(true);
    }
  }

  /** HALSIM_ResumeTiming. */
  public static void resumeTiming() {
    boolean was = SimTime.isPaused();
    SimTime.resume();
    Notifiers.resume();
    if (was) {
      sendPaused(false);
    }
  }

  /** HALSIM_StepTiming: advance by delta microseconds, running notifiers in order. */
  public static void stepTiming(long delta) {
    Notifiers.step(delta);
  }

  /** HALSIM_StepTimingAsync. */
  public static void stepTimingAsync(long delta) {
    Notifiers.stepAsync(delta);
  }

  public static void setProgramStarted() {
    programStarted = true;
  }

  public static boolean getProgramStarted() {
    return programStarted;
  }

  /** HALSIM_WaitForProgramStart. */
  public static void waitForProgramStart() {
    int count = 0;
    while (!programStarted) {
      count++;
      System.out.println("Waiting for program start signal: " + count);
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
