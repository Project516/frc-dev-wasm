package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import dev.project516.frcdevwasm.sim.DigitalPorts;
import dev.project516.frcdevwasm.sim.DsErrors;
import dev.project516.frcdevwasm.sim.Handles;
import org.wpilib.hardware.hal.DutyCycleJNI;
import org.wpilib.hardware.hal.EncoderJNI;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.util.AllocationException;
import org.wpilib.hardware.hal.util.HalHandleException;
import org.wpilib.hardware.hal.util.UncleanStatusException;
import org.wpilib.hardware.discrete.AnalogInput;
import org.wpilib.hardware.discrete.CounterBase.EncodingType;
import org.wpilib.hardware.rotation.DutyCycle;
import org.wpilib.hardware.rotation.Encoder;
import org.wpilib.simulation.AnalogInputSim;
import org.wpilib.simulation.DutyCycleSim;
import org.wpilib.simulation.EncoderSim;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Analog inputs and outputs, triggers, gyro, accelerometers, encoders and duty cycle inputs. */
public final class AnalogSimTest {
  private AnalogSimTest() {}

  private static final int K4X = 2;

  public static void run(Tests t) {
    HAL.initialize();
    analogInput(t);
    encoder(t);
  }

  private static void analogInput(Tests t) {

    t.test("analog input raw value is the voltage on a 3.3 V, 12 bit scale", () -> {
      AnalogInputSim sim = new AnalogInputSim(1);
      sim.resetData();
      try (AnalogInput ai = new AnalogInput(1)) {
        sim.setVoltage(3.3);
        Tests.eq(4095, ai.getValue(), "full scale");
        sim.setVoltage(7.0);
        Tests.eq(8686, ai.getValue(), "not clamped");
        sim.setVoltage(-2.0);
        Tests.eq(-2481, ai.getValue(), "negative");
        Tests.eq(-2.0, ai.getVoltage(), "voltage");
      }
    });

    t.test("analog input callbacks fire on change only", () -> {
      AnalogInputSim sim = new AnalogInputSim(4);
      sim.resetData();
      AtomicInteger voltage = new AtomicInteger();
      AtomicInteger init = new AtomicInteger();
      var vcb = sim.registerVoltageCallback((name, value) -> voltage.incrementAndGet(), false);
      var icb = sim.registerInitializedCallback((name, value) -> init.incrementAndGet(), true);
      try {
        Tests.eq(1, init.get(), "initial notify");
        try (AnalogInput ai = new AnalogInput(4)) {
          Tests.eq(2, init.get(), "init true");
          sim.setVoltage(1.0);
          sim.setVoltage(1.0);
          sim.setVoltage(2.0);
          Tests.eq(2, voltage.get(), "voltage callbacks");
        }
        Tests.eq(3, init.get(), "init false");
      } finally {
        vcb.close();
        icb.close();
      }
      sim.setVoltage(3.0);
      Tests.eq(2, voltage.get(), "no callback after cancel");
    });

  }

  /** Handle of the analog input on the channel; the channel must be allocated. */
  private static int analogHandle(int channel) {
    return Handles.make(channel, Handles.ANALOG_INPUT);
  }

  private static void encoder(Tests t) {

    t.test("encoder is found by channel and freed", () -> {
      EncoderSim sim = EncoderSim.createForIndex(0);
      sim.resetData();
      try (Encoder enc = new Encoder(0, 1)) {
        Tests.check(sim.getInitialized(), "initialized");
        EncoderSim.createForChannel(0).setCount(7);
        Tests.eq(7, sim.getCount(), "found the same encoder");
        Tests.eq(7, EncoderSim.createForChannel(1).getCount(), "found by the b channel");
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

  }

  private static int encoderHandle(Encoder enc) {
    return Handles.make(enc.getFPGAIndex(), Handles.ENCODER);
  }

  private static LocalTransport transport() {
    return BridgeLoop.local();
  }

  private static void waitFor(BooleanSupplier condition, String message) {
    long end = System.nanoTime() + 3_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out: " + message);
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
  }

  /** Last value sent for a key of a device, or null. */
  @SuppressWarnings("unchecked")
  private static Object sentValue(String type, String device, String key) {
    BridgeLoop.flush();
    Object found = null;
    List<Map<String, Object>> all = new ArrayList<>(transport().sent());
    for (Map<String, Object> m : all) {
      if (type.equals(m.get("type")) && device.equals(m.get("device"))) {
        Map<String, Object> data = (Map<String, Object>) m.get("data");
        if (data.containsKey(key)) {
          found = data.get(key);
        }
      }
    }
    return found;
  }

  private static double number(Object o) {
    return ((Number) o).doubleValue();
  }

}
