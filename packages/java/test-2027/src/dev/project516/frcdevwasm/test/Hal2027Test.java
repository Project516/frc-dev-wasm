package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import dev.project516.frcdevwasm.sim.CanData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.wpilib.hardware.bus.CANPort;
import org.wpilib.hardware.discrete.AnalogInput;
import org.wpilib.hardware.discrete.DigitalInput;
import org.wpilib.hardware.discrete.PWM;
import org.wpilib.hardware.hal.CANAPIJNI;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.HALUtil;
import org.wpilib.hardware.hal.IMUJNI;
import org.wpilib.hardware.hal.PowerJNI;
import org.wpilib.hardware.hal.can.CANReceiveMessage;
import org.wpilib.hardware.hal.util.AllocationException;
import org.wpilib.hardware.hal.util.UncleanStatusException;
import org.wpilib.hardware.imu.OnboardIMU;
import org.wpilib.hardware.led.AddressableLED;
import org.wpilib.hardware.led.AddressableLEDBuffer;
import org.wpilib.hardware.rotation.DutyCycle;
import org.wpilib.hardware.rotation.Encoder;
import org.wpilib.simulation.AddressableLEDSim;
import org.wpilib.simulation.AnalogInputSim;
import org.wpilib.simulation.DutyCycleSim;
import org.wpilib.simulation.EncoderSim;
import org.wpilib.simulation.OnboardIMUSim;
import org.wpilib.simulation.PWMSim;
import org.wpilib.simulation.RoboRioSim;
import org.wpilib.simulation.SimHooks;
import org.wpilib.system.Notifier;

/** The HAL of 2027: channel based devices, nanosecond time, the notifier, IMU, CAN and the page messages. */
public final class Hal2027Test {
  private Hal2027Test() {}

  @SuppressWarnings("unchecked")
  private static Map<String, Object> last(List<Map<String, Object>> sent, String type, String device, String key, int value) {
    synchronized (sent) {
      for (int i = sent.size() - 1; i >= 0; i--) {
        Map<String, Object> m = sent.get(i);
        if (type.equals(m.get("type")) && device.equals(m.get("device"))
            && ((Map<String, Object>) m.get("data")).get(key) instanceof Number n && n.intValue() == value) {
          return m;
        }
      }
    }
    return null;
  }

  /** The newest message of a device that sets key to value. The full state sent at init comes before it. */
  private static Map<String, Object> await(List<Map<String, Object>> sent, String type, String device, String key, int value)
      throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      Map<String, Object> m = last(sent, type, device, key, value);
      if (m != null) {
        return m;
      }
      Thread.sleep(10);
    }
    return null;
  }

  /** The `<data` lists sent for an AddressableLED since the first `from` messages, as r,g,b per LED. */
  @SuppressWarnings("unchecked")
  private static List<List<Integer>> ledMessages(String device, int from) {
    BridgeLoop.flush();
    List<List<Integer>> out = new ArrayList<>();
    List<Map<String, Object>> all = BridgeLoop.local().sent();
    for (Map<String, Object> m : all.subList(from, all.size())) {
      Map<String, Object> data = (Map<String, Object>) m.get("data");
      if ("AddressableLED".equals(m.get("type")) && device.equals(m.get("device")) && data.containsKey("<data")) {
        List<Integer> flat = new ArrayList<>();
        for (Map<String, Object> led : (List<Map<String, Object>>) data.get("<data")) {
          flat.add(((Number) led.get("r")).intValue());
          flat.add(((Number) led.get("g")).intValue());
          flat.add(((Number) led.get("b")).intValue());
        }
        out.add(flat);
      }
    }
    return out;
  }

  private static int ledMark() {
    BridgeLoop.flush();
    return BridgeLoop.local().sent().size();
  }

  /** Page messages are applied on the bridge thread. */
  private static void awaitNear(double expected, java.util.function.DoubleSupplier actual, String message)
      throws InterruptedException {
    for (int i = 0; i < 200 && Math.abs(expected - actual.getAsDouble()) > 1e-12; i++) {
      Thread.sleep(10);
    }
    Tests.near(expected, actual.getAsDouble(), 1e-12, message);
  }

  @SuppressWarnings("unchecked")
  public static void run(Tests t) {
    HAL.initialize();

    t.test("pwm channel allocates, writes the pulse and period, and frees", () -> {
      PWMSim sim = new PWMSim(3);
      sim.resetData();
      try (PWM pwm = new PWM(3)) {
        Tests.check(sim.getInitialized(), "initialized");
        pwm.setPulseTimeMicroseconds(1750);
        Tests.eq(1750, sim.getPulseMicrosecond(), "pulse");
        pwm.setOutputPeriod(10);
        Tests.eq(1, sim.getOutputPeriod(), "10 ms is scale 1");
        try {
          new PWM(3).close();
          throw new AssertionError("expected AllocationException");
        } catch (AllocationException expected) {
          Tests.check(expected.getMessage().contains("PWM 34 previously allocated"), expected.getMessage());
        }
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("pwm and dio share a channel table", () -> {
      try (PWM pwm = new PWM(0)) {
        try (DigitalInput in = new DigitalInput(31 - 21)) {
          Tests.check(in.get(), "idle input is high");
        }
      }
    });

    t.test("analog input channel validates, converts and reads the sim voltage", () -> {
      AnalogInputSim sim = new AnalogInputSim(2);
      sim.resetData();
      try (AnalogInput ai = new AnalogInput(2)) {
        sim.setVoltage(1.65);
        Tests.eq(2047, ai.getValue(), "raw");
        Tests.near(1.65, ai.getVoltage(), 1e-12, "volts");
        try {
          new AnalogInput(2).close();
          throw new AssertionError("expected AllocationException");
        } catch (AllocationException expected) {
          Tests.check(expected.getMessage().contains("Analog 2 previously allocated"), expected.getMessage());
        }
      }
      try {
        new AnalogInput(8).close();
        throw new AssertionError("expected AllocationException");
      } catch (AllocationException expected) {
        Tests.check(expected.getMessage().contains("Invalid Index for Analog Input"), expected.getMessage());
      }
    });

    t.test("duty cycle is created from a channel and reports frequency, output and high time", () -> {
      DutyCycleSim sim = DutyCycleSim.createForChannel(1);
      sim.resetData();
      try (DutyCycle dc = new DutyCycle(1)) {
        Tests.check(sim.getInitialized(), "initialized");
        sim.setFrequency(1000);
        sim.setOutput(0.25);
        Tests.near(1000, dc.getFrequency(), 1e-9, "frequency");
        Tests.near(0.25, dc.getOutput(), 1e-9, "output");
        Tests.eq(250_000, dc.getHighTimeNanoseconds(), "high time ns");
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("encoder on channels keeps the rate and checks the rate window", () -> {
      EncoderSim sim = EncoderSim.createForIndex(0);
      sim.resetData();
      try (Encoder enc = new Encoder(4, 5)) {
        Tests.check(sim.getInitialized(), "initialized");
        enc.setDistancePerPulse(0.5);
        sim.setCount(10);
        sim.setRate(3.0);
        Tests.near(5.0, enc.getDistance(), 1e-12, "distance");
        Tests.near(3.0, enc.getRate(), 1e-12, "rate");
        Tests.check(!enc.getStopped(), "moving");
        sim.setRate(0.0);
        Tests.check(enc.getStopped(), "stopped");
        enc.reset();
        Tests.eq(0, enc.get(), "reset");
      }
    });

    t.test("onboard imu returns the sim angles, rates, accelerations and yaw", () -> {
      OnboardIMUSim.setAngleX(0.1);
      OnboardIMUSim.setAngleZ(0.3);
      OnboardIMUSim.setGyroRateY(0.5);
      OnboardIMUSim.setAccelZ(9.8);
      OnboardIMUSim.setYaw(1.25);
      double[] angles = new double[3];
      IMUJNI.getIMUEulerAnglesFlat(angles);
      Tests.near(0.1, angles[0], 1e-12, "x");
      Tests.near(0.3, angles[2], 1e-12, "z");
      double[] rates = new double[3];
      IMUJNI.getIMUGyroRates(rates);
      Tests.near(0.5, rates[1], 1e-12, "rate y");
      double[] accel = new double[3];
      IMUJNI.getIMUAcceleration(accel);
      Tests.near(9.8, accel[2], 1e-12, "accel z");
      Tests.near(1.25, new OnboardIMU(OnboardIMU.MountOrientation.FLAT).getYawRadians(), 1e-12, "yaw");
      double[] quat = {1, 1, 1, 1};
      IMUJNI.getIMUQuaternion(quat);
      Tests.near(0.0, quat[0], 0, "quaternion is zero");
      try {
        IMUJNI.getIMUAcceleration(new double[2]);
        throw new AssertionError("expected IllegalArgumentException");
      } catch (IllegalArgumentException expected) {
        Tests.check(expected.getMessage().contains("accel array too small"), expected.getMessage());
      }
    });

    t.test("brownout voltages are checked and stored", () -> {
      PowerJNI.setBrownoutVoltages(6.0, 7.0);
      Tests.near(6.0, RoboRioSim.getBrownoutVoltage(), 1e-12, "brownout");
      Tests.near(7.0, RoboRioSim.getBrownoutRecoveryVoltage(), 1e-12, "recovery");
      try {
        PowerJNI.setBrownoutVoltages(6.0, 6.2);
        throw new AssertionError("expected UncleanStatusException");
      } catch (UncleanStatusException expected) {
        Tests.check(expected.getMessage().contains("out of range"), expected.getMessage());
      }
      RoboRioSim.resetData();
    });

    t.test("time is in nanoseconds and steps with the notifier", () -> {
      SimHooks.pauseTiming();
      try {
        long before = HALUtil.getMonotonicTime();
        AtomicInteger fired = new AtomicInteger();
        try (Notifier n = new Notifier(fired::incrementAndGet)) {
          n.startPeriodic(0.02);
          SimHooks.stepTiming(0.1);
          Tests.eq(5, fired.get(), "alarms in 100 ms");
          n.stop();
          SimHooks.stepTiming(0.1);
          Tests.eq(5, fired.get(), "stopped");
        }
        Tests.eq(200_000_000L, HALUtil.getMonotonicTime() - before, "elapsed ns");
      } finally {
        SimHooks.resumeTiming();
      }
    });

    t.test("addressable led keeps start and length and passes written data to callbacks", () -> {
      AddressableLEDSim sim = new AddressableLEDSim(0);
      sim.resetData();
      List<byte[]> writes = new ArrayList<>();
      try (var cb = AddressableLEDSim.registerDataCallback((name, buffer, count) -> writes.add(java.util.Arrays.copyOf(buffer, count)));
          AddressableLED led = new AddressableLED(0)) {
        Tests.check(sim.getInitialized(), "initialized");
        led.setLength(3);
        Tests.eq(3, sim.getLength(), "length");
        AddressableLEDBuffer buf = new AddressableLEDBuffer(3);
        buf.setRGB(1, 10, 20, 30);
        led.setData(buf);
        Tests.eq(1, writes.size(), "one write");
        Tests.eq(9, writes.get(0).length, "three leds of three bytes");
        Tests.eq(10, writes.get(0)[3] & 0xff, "red of led 1");
        Tests.eq(30, writes.get(0)[5] & 0xff, "blue of led 1");
      }
    });

    t.test("can api sends through the callbacks of the bus and reads what a simulator answers", () -> {
      List<int[]> sends = new ArrayList<>();
      int send = CanData.sendMessage.register((name, busId, messageId, message, periodMs, status) -> sends.add(new int[] {busId, messageId, message.dataSize, periodMs}));
      int recv = CanData.receiveMessage.register((name, busId, messageId, message, status) -> {
        message.timeStamp = 123;
        message.message.dataSize = 2;
        message.message.data[0] = 7;
        message.message.data[1] = 8;
      });
      int handle = CANAPIJNI.initializeCAN(CANPort.CAN_S1.value, 8, 5, 10);
      try {
        CANAPIJNI.writeCANPacketRepeating(handle, 3, new byte[] {1, 2, 3}, 3, 0, 20);
        Tests.eq(1, sends.size(), "one send");
        Tests.eq(CANPort.CAN_S1.value, sends.get(0)[0], "bus");
        Tests.eq((10 << 24) | (8 << 16) | (3 << 6) | 5, sends.get(0)[1], "arbitration id");
        Tests.eq(20, sends.get(0)[3], "period");
        CANReceiveMessage in = new CANReceiveMessage();
        Tests.check(CANAPIJNI.readCANPacketNew(handle, 4, in), "received");
        Tests.eq(2, in.length, "length");
        Tests.eq(8, (int) in.data[1], "data");
        Tests.eq(123L, in.timestamp, "timestamp");
        try {
          CANAPIJNI.writeCANPacket(handle, 1, new byte[9], 9, 0);
          throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
          Tests.check(expected.getMessage().contains("Non FD frame"), expected.getMessage());
        }
      } finally {
        CANAPIJNI.cleanCAN(handle);
        CanData.sendMessage.cancel(send);
        CanData.receiveMessage.cancel(recv);
      }
    });

    t.test("a receive no simulator answers is not found", () -> {
      CanData.reset();
      int handle = CANAPIJNI.initializeCAN(0, 4, 1, 2);
      try {
        Tests.check(!CANAPIJNI.readCANPacketNew(handle, 1, new CANReceiveMessage()), "not found");
      } finally {
        CANAPIJNI.cleanCAN(handle);
      }
    });

    t.test("addressable leds send the colors of their own range of the shared buffer", () -> {
      new AddressableLEDSim(0).resetData();
      new AddressableLEDSim(1).resetData();
      try (AddressableLED a = new AddressableLED(0); AddressableLED b = new AddressableLED(1)) {
        a.setStart(0);
        a.setLength(3);
        b.setStart(3);
        b.setLength(2);
        AddressableLEDBuffer three = new AddressableLEDBuffer(3);
        AddressableLEDBuffer two = new AddressableLEDBuffer(2);
        for (int i = 0; i < 3; i++) {
          three.setRGB(i, 10 + i, 20 + i, 30 + i);
        }
        for (int i = 0; i < 2; i++) {
          two.setRGB(i, 40 + i, 50 + i, 60 + i);
        }

        int mark = ledMark();
        a.setData(three);
        Tests.eq(List.of(List.of(10, 20, 30, 11, 21, 31, 12, 22, 32)), ledMessages("0", mark), "output 0 gets its LEDs");
        Tests.eq(List.of(), ledMessages("1", mark), "output 1 gets nothing from a write outside its range");

        mark = ledMark();
        b.setData(two);
        Tests.eq(List.of(List.of(40, 50, 60, 41, 51, 61)), ledMessages("1", mark), "output 1 gets its LEDs");
        Tests.eq(List.of(), ledMessages("0", mark), "output 0 gets nothing from a write outside its range");

        mark = ledMark();
        AddressableLEDBuffer five = new AddressableLEDBuffer(5);
        for (int i = 0; i < 5; i++) {
          five.setRGB(i, i, i + 100, i + 200);
        }
        AddressableLED.setGlobalData(0, AddressableLED.ColorOrder.RGB, five);
        Tests.eq(List.of(List.of(0, 100, 200, 1, 101, 201, 2, 102, 202)), ledMessages("0", mark), "output 0 of a global write");
        Tests.eq(List.of(List.of(3, 103, 203, 4, 104, 204)), ledMessages("1", mark), "output 1 of a global write");

        mark = ledMark();
        a.setData(three);
        a.setData(three);
        Tests.eq(9, ledMessages("0", mark).get(0).size(), "writes in one flush keep the whole range");
      }
    });

    t.test("page messages use the 2027 keys", () -> {
      LocalTransport transport = BridgeLoop.local();
      List<Map<String, Object>> sent = new ArrayList<>();
      transport.onSend(m -> {
        synchronized (sent) {
          sent.add(m);
        }
      });
      try (PWM pwm = new PWM(5)) {
        transport.inject("{\"type\":\"hello\",\"device\":\"\",\"data\":{}}");
        pwm.setPulseTimeMicroseconds(1234);
        Map<String, Object> m = await(sent, "PWM", "5", "<raw", 1234);
        Tests.check(m != null, "PWM message with <raw 1234");
        Map<String, Object> data = (Map<String, Object>) m.get("data");
        Tests.eq(1234, ((Number) data.get("<raw")).intValue(), "<raw");
        Tests.check(!data.containsKey("<speed") && !data.containsKey("<position"), "no speed keys");
      }
      EncoderSim.createForIndex(0).resetData();
      try (Encoder enc = new Encoder(6, 7)) {
        String id = String.valueOf(enc.getFPGAIndex());
        transport.inject("Encoder", id, Map.of(">rate", 2.5, ">count", 4));
        awaitNear(2.5, enc::getRate, "rate from page");
        awaitNear(4, enc::get, "count from page");
        transport.inject("Encoder", id, Map.of(">period", 0.5));
        awaitNear(2.0, enc::getRate, "period from an old client");
      }
      transport.inject("RoboRIO", "", Map.of(">3v3_voltage", 3.0, ">vin_voltage", 11.5));
      awaitNear(11.5, RoboRioSim::getVInVoltage, "vin");
      awaitNear(3.0, RoboRioSim::getUserVoltage3V3, "3v3");
      RoboRioSim.resetData();
    });
  }
}
