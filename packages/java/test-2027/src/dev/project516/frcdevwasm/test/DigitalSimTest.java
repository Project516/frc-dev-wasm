package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import org.wpilib.hardware.hal.AddressableLEDJNI;
import org.wpilib.hardware.hal.DIOJNI;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.PWMJNI;
import org.wpilib.hardware.hal.util.AllocationException;
import org.wpilib.hardware.hal.util.HalHandleException;
import org.wpilib.hardware.hal.util.UncleanStatusException;
import org.wpilib.hardware.led.AddressableLED;
import org.wpilib.hardware.led.AddressableLEDBuffer;
import org.wpilib.hardware.discrete.DigitalInput;
import org.wpilib.hardware.discrete.DigitalOutput;
import org.wpilib.simulation.AddressableLEDSim;
import org.wpilib.simulation.DIOSim;
import org.wpilib.simulation.DigitalPWMSim;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** DIO, digital PWM, relay, counter stubs and addressable LEDs through the real wpilibj classes. */
public final class DigitalSimTest {
  private DigitalSimTest() {}

  private static void expectThrows(Class<? extends Throwable> type, String contains, Tests.Case c) {
    try {
      c.run();
    } catch (Throwable t) {
      Tests.check(type.isInstance(t), "expected " + type.getSimpleName() + " but got " + t);
      Tests.check(
          contains == null || String.valueOf(t.getMessage()).contains(contains),
          "message <" + t.getMessage() + "> lacks <" + contains + ">");
      return;
    }
    throw new AssertionError("expected " + type.getSimpleName());
  }

  private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("timed out waiting for " + what);
  }

  private static List<Map<String, Object>> sent(LocalTransport local, String type, String device) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> m : local.sent()) {
      if (type.equals(m.get("type")) && device.equals(m.get("device"))) {
        out.add(m);
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static boolean sentKey(LocalTransport local, String type, String device, String key, Object value) {
    for (Map<String, Object> m : sent(local, type, device)) {
      Map<String, Object> data = (Map<String, Object>) m.get("data");
      Object actual = data.get(key);
      if (actual instanceof Number a && value instanceof Number v) {
        if (a.doubleValue() == v.doubleValue()) {
          return true;
        }
      } else if (data.containsKey(key) && java.util.Objects.equals(actual, value)) {
        return true;
      }
    }
    return false;
  }

  public static void run(Tests t) {
    HAL.initialize();

    t.test("digital input reads the sim value", () -> {
      DIOSim sim = new DIOSim(3);
      sim.resetData();
      try (DigitalInput in = new DigitalInput(3)) {
        Tests.check(sim.getInitialized(), "initialized");
        Tests.check(sim.getIsInput(), "is input");
        Tests.check(in.get(), "default value is true");
        sim.setValue(false);
        Tests.check(!in.get(), "follows sim");
        sim.setValue(true);
        Tests.check(in.get(), "follows sim again");
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("digital output writes the sim value and callbacks fire on change", () -> {
      DIOSim sim = new DIOSim(4);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      var cb = sim.registerValueCallback((name, value) -> calls.incrementAndGet(), false);
      try (DigitalOutput out = new DigitalOutput(4)) {
        Tests.check(sim.getInitialized(), "initialized");
        Tests.check(!sim.getIsInput(), "is output");
        out.set(false);
        Tests.check(!sim.getValue(), "value written");
        Tests.check(!out.get(), "read back");
        out.set(false);
        out.set(true);
        Tests.check(sim.getValue(), "value written true");
        Tests.eq(2, calls.get(), "callbacks on change only");
      } finally {
        cb.close();
      }
    });

    t.test("dio resetData restores defaults and drops callbacks", () -> {
      DIOSim sim = new DIOSim(8);
      AtomicInteger calls = new AtomicInteger();
      var cb = sim.registerValueCallback((name, value) -> calls.incrementAndGet(), false);
      sim.setValue(false);
      sim.setPulseLength(2.5);
      sim.setFilterIndex(2);
      Tests.eq(1, calls.get(), "callback fired");
      sim.resetData();
      Tests.check(sim.getValue(), "value default");
      Tests.eq(0.0, sim.getPulseLength(), "pulse length default");
      Tests.eq(-1, sim.getFilterIndex(), "filter index default");
      Tests.check(sim.getIsInput(), "input default");
      sim.setValue(false);
      Tests.eq(1, calls.get(), "callback dropped");
      sim.resetData();
      cb.close();
    });

    t.test("dio initial notify and cancel", () -> {
      DIOSim sim = new DIOSim(9);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      var cb = sim.registerInitializedCallback((name, value) -> calls.incrementAndGet(), true);
      Tests.eq(1, calls.get(), "initial notify");
      cb.close();
      sim.setInitialized(true);
      Tests.eq(1, calls.get(), "cancelled");
      sim.resetData();
    });

    t.test("dio out of range channel", () -> {
      expectThrows(Throwable.class, null, () -> new DigitalInput(31).close());
      expectThrows(Throwable.class, null, () -> new DigitalOutput(-1).close());
    });

    t.test("digital pwm generator", () -> {
      try (DigitalOutput out = new DigitalOutput(6)) {
        out.enablePWM(0.25);
        DigitalPWMSim sim = DigitalPWMSim.createForChannel(6);
        Tests.check(sim.getInitialized(), "initialized");
        Tests.eq(6, sim.getPin(), "pin");
        Tests.near(0.25, sim.getDutyCycle(), 1e-9, "duty cycle");
        out.updateDutyCycle(2.0);
        Tests.near(1.0, sim.getDutyCycle(), 1e-9, "clamped high");
        out.updateDutyCycle(-1.0);
        Tests.near(0.0, sim.getDutyCycle(), 1e-9, "clamped low");
        out.enablePPS(0.5);
        out.setPWMRate(200);
        out.disablePWM();
        Tests.check(!sim.getInitialized(), "freed");
      }
    });

    t.test("digital pwm callbacks and ports exhaust", () -> {
      DigitalPWMSim sim = DigitalPWMSim.createForIndex(0);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      var cb = sim.registerDutyCycleCallback((name, value) -> calls.incrementAndGet(), false);
      int[] handles = new int[6];
      try {
        for (int i = 0; i < handles.length; i++) {
          handles[i] = DIOJNI.allocateDigitalPWM();
        }
        Tests.check(sim.getInitialized(), "first generator initialized");
        DIOJNI.setDigitalPWMDutyCycle(handles[0], 0.5);
        DIOJNI.setDigitalPWMDutyCycle(handles[0], 0.5);
        DIOJNI.setDigitalPWMPPS(handles[0], 0.75);
        Tests.eq(2, calls.get(), "callbacks");
        DIOJNI.setDigitalPWMOutputChannel(handles[0], 12);
        Tests.eq(12, DigitalPWMSim.createForChannel(12).getPin(), "findForChannel");
        expectThrows(UncleanStatusException.class, null, DIOJNI::allocateDigitalPWM);
        expectThrows(HalHandleException.class, null, () -> DIOJNI.setDigitalPWMDutyCycle(0, 0.5));
        expectThrows(HalHandleException.class, null, () -> DIOJNI.setDigitalPWMOutputChannel(0, 1));
      } finally {
        cb.close();
        for (int h : handles) {
          DIOJNI.freeDigitalPWM(h);
        }
        DIOJNI.freeDigitalPWM(0);
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("pulse is accepted but never pulsing", () -> {
      try (DigitalOutput out = new DigitalOutput(12)) {
        out.pulse(0.1);
        Tests.check(!out.isPulsing(), "not pulsing");
        DIOJNI.pulseMultiple(0b1010, 0.1);
        Tests.check(!DIOJNI.isAnyPulsing(), "any pulsing");
        expectThrows(HalHandleException.class, null, () -> DIOJNI.pulse(0, 0.1));
        expectThrows(HalHandleException.class, null, () -> DIOJNI.isPulsing(0));
      }
    });

  }
}
