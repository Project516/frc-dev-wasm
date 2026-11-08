package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import edu.wpi.first.hal.AddressableLEDJNI;
import edu.wpi.first.hal.DIOJNI;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.hal.PWMJNI;
import edu.wpi.first.hal.RelayJNI;
import edu.wpi.first.hal.util.AllocationException;
import edu.wpi.first.hal.util.HalHandleException;
import edu.wpi.first.hal.util.UncleanStatusException;
import edu.wpi.first.wpilibj.AddressableLED;
import edu.wpi.first.wpilibj.AddressableLEDBuffer;
import edu.wpi.first.wpilibj.DigitalInput;
import edu.wpi.first.wpilibj.DigitalOutput;
import edu.wpi.first.wpilibj.Relay;
import edu.wpi.first.wpilibj.motorcontrol.PWMSparkMax;
import edu.wpi.first.wpilibj.simulation.AddressableLEDSim;
import edu.wpi.first.wpilibj.simulation.DIOSim;
import edu.wpi.first.wpilibj.simulation.DigitalPWMSim;
import edu.wpi.first.wpilibj.simulation.RelaySim;
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
    HAL.initialize(500, 0);

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

    t.test("setting an input channel throws and direction can change", () -> {
      try (DigitalInput in = new DigitalInput(5)) {
        int handle = in.getPortHandleForRouting();
        expectThrows(
            UncleanStatusException.class,
            "Cannot set output of an input channel",
            () -> DIOJNI.setDIO(handle, true));
        Tests.check(DIOJNI.getDIODirection(handle), "input");
        DIOJNI.setDIODirection(handle, false);
        Tests.check(!DIOJNI.getDIODirection(handle), "output now");
        DIOJNI.setDIO(handle, false);
        Tests.check(!DIOJNI.getDIO(handle), "value set");
        Tests.check(!new DIOSim(5).getIsInput(), "sim sees direction");
      }
    });

    t.test("dio handle errors", () -> {
      expectThrows(HalHandleException.class, null, () -> DIOJNI.getDIO(0x01000005));
      expectThrows(HalHandleException.class, null, () -> DIOJNI.setDIO(0, true));
      DIOJNI.freeDIOPort(0);
      DIOJNI.freeDIOPort(0x01000005);
      Tests.check(DIOJNI.checkDIOChannel(0), "channel 0");
      Tests.check(DIOJNI.checkDIOChannel(30), "channel 30");
      Tests.check(!DIOJNI.checkDIOChannel(31), "channel 31");
      Tests.check(!DIOJNI.checkDIOChannel(-1), "channel -1");
      Tests.eq((short) 40, DIOJNI.getLoopTiming(), "loop timing");
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

    t.test("dio and pwm share channels", () -> {
      try (DigitalInput in = new DigitalInput(10)) {
        expectThrows(AllocationException.class, "previously allocated", () -> new DigitalOutput(10).close());
        expectThrows(AllocationException.class, "PWM or DIO", () -> new PWMSparkMax(10).close());
      }
      try (PWMSparkMax motor = new PWMSparkMax(11)) {
        expectThrows(AllocationException.class, "previously allocated", () -> new DigitalInput(11).close());
      }
      try (DigitalInput in = new DigitalInput(10)) {
        Tests.check(in.get(), "channel usable again after free");
      }
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

    t.test("relay both directions", () -> {
      RelaySim sim = new RelaySim(1);
      sim.resetData();
      try (Relay relay = new Relay(1)) {
        Tests.check(sim.getInitializedForward(), "forward initialized");
        Tests.check(sim.getInitializedReverse(), "reverse initialized");
        relay.set(Relay.Value.kOn);
        Tests.check(sim.getForward() && sim.getReverse(), "on");
        Tests.eq(Relay.Value.kOn, relay.get(), "get on");
        relay.set(Relay.Value.kForward);
        Tests.check(sim.getForward() && !sim.getReverse(), "forward");
        Tests.eq(Relay.Value.kForward, relay.get(), "get forward");
        relay.set(Relay.Value.kReverse);
        Tests.check(!sim.getForward() && sim.getReverse(), "reverse");
        Tests.eq(Relay.Value.kReverse, relay.get(), "get reverse");
        relay.set(Relay.Value.kOff);
        Tests.check(!sim.getForward() && !sim.getReverse(), "off");
        Tests.eq(Relay.Value.kOff, relay.get(), "get off");
      }
      Tests.check(!sim.getInitializedForward() && !sim.getInitializedReverse(), "freed");
    });

    t.test("relay forward only and sim callbacks", () -> {
      RelaySim sim = new RelaySim(2);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      var cb = sim.registerForwardCallback((name, value) -> calls.incrementAndGet(), false);
      try (Relay relay = new Relay(2, Relay.Direction.kForward)) {
        Tests.check(sim.getInitializedForward(), "forward initialized");
        Tests.check(!sim.getInitializedReverse(), "reverse not initialized");
        relay.set(Relay.Value.kOn);
        relay.set(Relay.Value.kOn);
        relay.set(Relay.Value.kOff);
        Tests.eq(2, calls.get(), "forward callbacks");
      } finally {
        cb.close();
      }
    });

    t.test("relay allocated twice throws and directions are independent", () -> {
      try (Relay fwd = new Relay(0, Relay.Direction.kForward)) {
        try (Relay rev = new Relay(0, Relay.Direction.kReverse)) {
          expectThrows(AllocationException.class, "previously allocated", () -> new Relay(0).close());
        }
        expectThrows(AllocationException.class, "previously allocated", () -> new Relay(0, Relay.Direction.kForward).close());
      }
    });

    t.test("relay handle errors and channel checks", () -> {
      expectThrows(HalHandleException.class, null, () -> RelayJNI.setRelay(0, true));
      expectThrows(HalHandleException.class, null, () -> RelayJNI.getRelay(0));
      RelayJNI.freeRelayPort(0);
      Tests.check(RelayJNI.checkRelayChannel(3), "channel 3");
      Tests.check(!RelayJNI.checkRelayChannel(4), "channel 4");
      Tests.check(!RelayJNI.checkRelayChannel(-1), "channel -1");
      expectThrows(AllocationException.class, null, () -> RelayJNI.initializeRelayPort(0, true));
    });

    t.test("counter is a stub", () -> {
      java.nio.IntBuffer index = java.nio.ByteBuffer.allocateDirect(4).asIntBuffer();
      int handle = edu.wpi.first.hal.CounterJNI.initializeCounter(0, index);
      Tests.eq(0, handle, "handle");
      Tests.eq(0, edu.wpi.first.hal.CounterJNI.getCounter(handle), "count");
      Tests.eq(0.0, edu.wpi.first.hal.CounterJNI.getCounterPeriod(handle), "period");
      Tests.check(!edu.wpi.first.hal.CounterJNI.getCounterStopped(handle), "stopped");
      Tests.check(!edu.wpi.first.hal.CounterJNI.getCounterDirection(handle), "direction");
      Tests.eq(0, edu.wpi.first.hal.CounterJNI.getCounterSamplesToAverage(handle), "samples");
      edu.wpi.first.hal.CounterJNI.setCounterUpSource(handle, 0, 0);
      edu.wpi.first.hal.CounterJNI.resetCounter(handle);
      edu.wpi.first.hal.CounterJNI.freeCounter(handle);
    });

    t.test("addressable led strip", () -> {
      try (AddressableLED led = new AddressableLED(3)) {
        AddressableLEDSim sim = new AddressableLEDSim(led);
        Tests.check(sim.getInitialized(), "initialized");
        Tests.eq(3, sim.getOutputPort(), "output port");
        Tests.eq(1, sim.getLength(), "default length");
        Tests.check(!sim.getRunning(), "not running");
        Tests.eq(3, AddressableLEDSim.createForChannel(3).getOutputPort(), "createForChannel");

        led.setLength(4);
        Tests.eq(4, sim.getLength(), "length");

        List<byte[]> received = new ArrayList<>();
        var cb = sim.registerDataCallback((name, buffer, count) -> {
          Tests.eq(buffer.length, count, "count");
          received.add(buffer);
        });
        AddressableLEDBuffer buffer = new AddressableLEDBuffer(4);
        buffer.setRGB(0, 255, 10, 20);
        buffer.setRGB(3, 1, 2, 3);
        led.setData(buffer);
        Tests.eq(1, received.size(), "data callback fired");
        byte[] data = sim.getData();
        Tests.eq(16, data.length, "data length");
        Tests.eq(255, data[2] & 0xff, "red of led 0");
        Tests.eq(10, data[1] & 0xff, "green of led 0");
        Tests.eq(20, data[0] & 0xff, "blue of led 0");
        Tests.eq(1, data[14] & 0xff, "red of led 3");
        Tests.check(java.util.Arrays.equals(data, received.get(0)), "callback data");
        cb.close();
        led.setData(buffer);
        Tests.eq(1, received.size(), "data callback cancelled");

        led.start();
        Tests.check(sim.getRunning(), "running");
        led.stop();
        Tests.check(!sim.getRunning(), "stopped");
        led.setBitTiming(400, 900, 900, 600);
        led.setSyncTime(280);
        led.setColorOrder(AddressableLED.ColorOrder.kRGB);

        expectThrows(UncleanStatusException.class, "Data length", () -> led.setData(new AddressableLEDBuffer(5)));
        expectThrows(UncleanStatusException.class, "LED length", () -> led.setLength(5461));
        expectThrows(UncleanStatusException.class, "LED length", () -> led.setLength(-1));
        sim.resetData();
      }
    });

    t.test("addressable led sim sets data directly and frees on close", () -> {
      AddressableLEDSim sim = AddressableLEDSim.createForIndex(0);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      var cb = sim.registerLengthCallback((name, value) -> calls.incrementAndGet(), false);
      var run = sim.registerRunningCallback((name, value) -> calls.incrementAndGet(), false);
      try (AddressableLED led = new AddressableLED(9)) {
        led.setLength(2);
        led.start();
        Tests.eq(2, calls.get(), "callbacks");
        sim.setLength(3);
        sim.setData(new byte[] {1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9, 0});
        byte[] data = sim.getData();
        Tests.eq(12, data.length, "data length follows length");
        Tests.eq(6, data[6] & 0xff, "data byte");
      } finally {
        cb.close();
        run.close();
      }
      Tests.check(!sim.getInitialized(), "freed");
      Tests.check(!sim.getRunning(), "stopped on free");
    });

    t.test("addressable led channel and resource errors", () -> {
      try (DigitalInput in = new DigitalInput(14)) {
        expectThrows(
            UncleanStatusException.class,
            "only supported on PWM",
            () -> AddressableLEDJNI.initialize(in.getPortHandleForRouting()));
      }
      int mxp = PWMJNI.initializePWMPort(HAL.getPort((byte) 10));
      try {
        expectThrows(UncleanStatusException.class, "only supported on PWM", () -> AddressableLEDJNI.initialize(mxp));
      } finally {
        PWMJNI.freePWMPort(mxp);
      }
      expectThrows(HalHandleException.class, null, () -> AddressableLEDJNI.initialize(0));
      expectThrows(HalHandleException.class, null, () -> AddressableLEDJNI.start(0));
      expectThrows(HalHandleException.class, null, () -> AddressableLEDJNI.setLength(0, 1));
      AddressableLEDJNI.free(0);

      try (AddressableLED led = new AddressableLED(8)) {
        int second = PWMJNI.initializePWMPort(HAL.getPort((byte) 7));
        try {
          expectThrows(UncleanStatusException.class, "No available resources", () -> AddressableLEDJNI.initialize(second));
        } finally {
          PWMJNI.freePWMPort(second);
        }
      }
    });

    t.test("ws messages for dio, dpwm, relay and led", () -> {
      LocalTransport local = BridgeLoop.local();
      DIOSim sim = new DIOSim(13);
      sim.resetData();
      local.clearSent();
      try (DigitalInput in = new DigitalInput(13)) {
        BridgeLoop.flush();
        Tests.check(sentKey(local, "DIO", "13", "<init", true), "dio init sent");
        Tests.check(sentKey(local, "DIO", "13", "<input", true), "dio input sent");
        Tests.check(sentKey(local, "DIO", "13", "<>value", true), "dio value sent");

        Map<String, Object> page = new LinkedHashMap<>();
        page.put(">ignored", 1);
        page.put("<>value", false);
        local.inject("DIO", "13", page);
        waitFor(() -> !sim.getValue(), "page value");
        local.clearSent();
        sim.setValue(true);
        BridgeLoop.flush();
        Tests.check(sentKey(local, "DIO", "13", "<>value", true), "sim change sent");
      }
      sim.resetData();

      local.clearSent();
      try (DigitalOutput out = new DigitalOutput(15)) {
        out.enablePWM(0.5);
        BridgeLoop.flush();
        Tests.check(sentKey(local, "dPWM", "0", "<init", true) || sentKey(local, "dPWM", "1", "<init", true)
            || sentKey(local, "dPWM", "2", "<init", true), "dpwm init sent");
        Tests.check(sentKey(local, "DIO", "15", "<input", false), "output direction sent");
        out.disablePWM();
      }

      local.clearSent();
      try (Relay relay = new Relay(3, Relay.Direction.kForward)) {
        relay.set(Relay.Value.kOn);
        BridgeLoop.flush();
        Tests.check(sentKey(local, "Relay", "3", "<init_fwd", true), "relay init sent");
        Tests.check(sentKey(local, "Relay", "3", "<fwd", true), "relay forward sent");
      }

      local.clearSent();
      try (AddressableLED led = new AddressableLED(2)) {
        led.setLength(2);
        AddressableLEDBuffer buffer = new AddressableLEDBuffer(2);
        buffer.setRGB(1, 9, 8, 7);
        led.setData(buffer);
        BridgeLoop.flush();
        Tests.check(sentKey(local, "AddressableLED", "0", "<init", true), "led init sent");
        Tests.check(sentKey(local, "AddressableLED", "0", "<length", 2), "led length sent");
        Tests.check(sentKey(local, "AddressableLED", "0", "<output_port", 2), "led port sent");
        boolean found = false;
        for (Map<String, Object> m : sent(local, "AddressableLED", "0")) {
          @SuppressWarnings("unchecked")
          Object data = ((Map<String, Object>) m.get("data")).get("<data");
          if (data instanceof List<?> leds && leds.size() == 2) {
            Map<?, ?> color = (Map<?, ?>) leds.get(1);
            Tests.eq(9.0, ((Number) color.get("r")).doubleValue(), "led 1 red");
            Tests.eq(8.0, ((Number) color.get("g")).doubleValue(), "led 1 green");
            Tests.eq(7.0, ((Number) color.get("b")).doubleValue(), "led 1 blue");
            found = true;
          }
        }
        Tests.check(found, "led data sent");
      }
    });
  }
}
