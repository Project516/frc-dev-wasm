package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import org.wpilib.hardware.hal.CTREPCMJNI;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.HALUtil;
import org.wpilib.hardware.hal.PortsJNI;
import org.wpilib.hardware.hal.PowerDistributionJNI;
import org.wpilib.hardware.hal.PowerDistributionVersion;
import org.wpilib.hardware.hal.REVPHJNI;
import org.wpilib.hardware.hal.REVPHVersion;
import org.wpilib.hardware.hal.ThreadsJNI;
import org.wpilib.hardware.hal.util.AllocationException;
import org.wpilib.hardware.hal.util.HalHandleException;
import org.wpilib.hardware.hal.util.UncleanStatusException;
import org.wpilib.hardware.pneumatic.Compressor;
import org.wpilib.hardware.pneumatic.CompressorConfigType;
import org.wpilib.hardware.pneumatic.DoubleSolenoid;
import org.wpilib.hardware.pneumatic.PneumaticHub;
import org.wpilib.hardware.pneumatic.PneumaticsControlModule;
import org.wpilib.hardware.pneumatic.PneumaticsModuleType;
import org.wpilib.hardware.power.PowerDistribution;
import org.wpilib.system.RobotController;
import org.wpilib.hardware.pneumatic.Solenoid;
import org.wpilib.simulation.CTREPCMSim;
import org.wpilib.simulation.CallbackStore;
import org.wpilib.simulation.PDPSim;
import org.wpilib.simulation.REVPHSim;
import org.wpilib.simulation.RoboRioSim;
import org.wpilib.simulation.SolenoidSim;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** HALUtil, ports, power, power distribution and pneumatics through the real WPILib classes. */
public final class PowerSimTest {
  private PowerSimTest() {}

  private static <T extends Throwable> T expect(Class<T> type, Tests.Case body) {
    try {
      body.run();
    } catch (Throwable e) {
      if (type.isInstance(e)) {
        return type.cast(e);
      }
      throw new AssertionError("expected " + type.getSimpleName() + " but got " + e, e);
    }
    throw new AssertionError("expected " + type.getSimpleName());
  }

  private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + 5_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(5);
    }
  }

  /** Last value of key sent for the device, or null. */
  private static Object sentValue(String type, String device, String key) {
    BridgeLoop.flush();
    Object found = null;
    for (Map<String, Object> m : BridgeLoop.local().sent()) {
      if (type.equals(m.get("type")) && device.equals(m.get("device"))) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) m.get("data");
        if (data.containsKey(key)) {
          found = data.get(key);
        }
      }
    }
    return found;
  }

  private static void inject(String type, String device, String key, Object value) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put(key, value);
    LocalTransport local = BridgeLoop.local();
    local.inject(type, device, data);
    BridgeLoop.poll();
  }

  public static void run(Tests t) {
    HAL.initialize();
    halUtil(t);
    ports(t);
    roboRio(t);
    powerDistribution(t);
    ctrePcm(t);
    revPh(t);
    ws(t);
  }

  private static void halUtil(Tests t) {

    t.test("halutil error messages", () -> {
      Tests.eq("", HALUtil.getHALErrorMessage(0), "success");
      Tests.eq("HAL: Resource already allocated", HALUtil.getHALErrorMessage(-1029), "allocated");
      Tests.eq(
          "HAL: A handle parameter was passed incorrectly",
          HALUtil.getHALErrorMessage(-1098),
          "handle");
      Tests.eq("Unknown error status", HALUtil.getHALErrorMessage(-9999), "unknown");
    });

  }

  private static void ports(Tests t) {

    t.test("ports report the power distribution and pneumatics counts", () -> {
      Tests.eq(63, PortsJNI.getNumCTREPCMModules(), "pcm modules");
      Tests.eq(8, PortsJNI.getNumCTRESolenoidChannels(), "pcm solenoids");
      Tests.eq(63, PortsJNI.getNumCTREPDPModules(), "pdp modules");
      Tests.eq(16, PortsJNI.getNumCTREPDPChannels(), "pdp channels");
      Tests.eq(63, PortsJNI.getNumREVPDHModules(), "pdh modules");
      Tests.eq(24, PortsJNI.getNumREVPDHChannels(), "pdh channels");
      Tests.eq(63, PortsJNI.getNumREVPHModules(), "ph modules");
      Tests.eq(16, PortsJNI.getNumREVPHChannels(), "ph channels");
    });
  }

  private static void roboRio(Tests t) {

    t.test("roborio serial number and comments are truncated", () -> {
      RoboRioSim.resetData();
      RoboRioSim.setSerialNumber("0123456789");
      Tests.eq("01234567", RoboRioSim.getSerialNumber(), "serial limited to 8");
      Tests.eq("01234567", RobotController.getSerialNumber(), "robot code serial");
      RoboRioSim.setComments("x".repeat(80));
      Tests.eq(64, RoboRioSim.getComments().length(), "comments limited to 64");
      Tests.eq(64, RobotController.getComments().length(), "robot code comments");
    });

    t.test("roborio brownout flag is never set", () -> {
      RoboRioSim.resetData();
      RoboRioSim.setVInVoltage(1.0);
      Tests.check(!RobotController.isBrownedOut(), "not browned out");
    });

    t.test("roborio callbacks fire on change with the value", () -> {
      RoboRioSim.resetData();
      List<String> names = new ArrayList<>();
      List<Double> values = new ArrayList<>();
      try (CallbackStore cb =
          RoboRioSim.registerVInVoltageCallback(
              (name, value) -> {
                names.add(name);
                values.add(value.getDouble());
              },
              true)) {
        Tests.eq(1, values.size(), "initial notify");
        Tests.eq(12.0, values.get(0), "initial value");
        RoboRioSim.setVInVoltage(12.0);
        Tests.eq(1, values.size(), "no callback without change");
        RoboRioSim.setVInVoltage(9.0);
        Tests.eq(2, values.size(), "callback on change");
        Tests.eq(9.0, values.get(1), "changed value");
        Tests.eq("VInVoltage", names.get(1), "callback name");
      }
      RoboRioSim.setVInVoltage(8.0);
      Tests.eq(2, values.size(), "no callback after cancel");
    });

    t.test("roborio resetData drops callbacks", () -> {
      RoboRioSim.resetData();
      AtomicInteger calls = new AtomicInteger();
      CallbackStore cb = RoboRioSim.registerCPUTempCallback((n, v) -> calls.incrementAndGet(), false);
      RoboRioSim.setCPUTemp(50);
      Tests.eq(1, calls.get(), "fired before reset");
      RoboRioSim.resetData();
      RoboRioSim.setCPUTemp(50);
      Tests.eq(1, calls.get(), "not fired after reset");
      cb.close();
    });
  }

  private static void powerDistribution(Tests t) {

    t.test("pdp sim callbacks fire per channel and reset to defaults", () -> {
      PDPSim sim = new PDPSim(0);
      sim.resetData();
      AtomicInteger channel2 = new AtomicInteger();
      AtomicInteger voltage = new AtomicInteger();
      List<Double> seen = new ArrayList<>();
      CallbackStore a = sim.registerCurrentCallback(2, (n, v) -> channel2.incrementAndGet(), false);
      CallbackStore b = sim.registerVoltageCallback(
          (n, v) -> {
            voltage.incrementAndGet();
            seen.add(v.getDouble());
          },
          false);
      sim.setCurrent(2, 1.0);
      sim.setCurrent(3, 1.0);
      sim.setCurrent(2, 1.0);
      sim.setVoltage(10.0);
      Tests.eq(1, channel2.get(), "channel 2 callbacks");
      Tests.eq(1, voltage.get(), "voltage callbacks");
      Tests.eq(10.0, seen.get(0), "voltage value");
      a.close();
      sim.setCurrent(2, 2.0);
      Tests.eq(1, channel2.get(), "cancelled");
      b.close();
      sim.resetData();
      Tests.eq(12.0, sim.getVoltage(), "voltage reset");
      Tests.eq(0.0, sim.getCurrent(2), "current reset");
    });
  }

  private static void ctrePcm(Tests t) {

    t.test("pcm resetData restores defaults and drops callbacks", () -> {
      CTREPCMSim sim = new CTREPCMSim(1);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      CallbackStore cb = sim.registerCompressorCurrentCallback((n, v) -> calls.incrementAndGet(), false);
      sim.setClosedLoopEnabled(false);
      sim.setCompressorCurrent(2.0);
      sim.setSolenoidOutput(3, true);
      sim.setPressureSwitch(true);
      sim.resetData();
      Tests.check(sim.getClosedLoopEnabled(), "closed loop reset to true");
      Tests.eq(0.0, sim.getCompressorCurrent(), "current reset");
      Tests.check(!sim.getSolenoidOutput(3), "solenoid reset");
      Tests.check(!sim.getPressureSwitch(), "pressure switch reset");
      sim.setCompressorCurrent(1.0);
      Tests.eq(1, calls.get(), "callback dropped by reset");
      cb.close();
      sim.resetData();
    });

    t.test("pcm register all callbacks", () -> {
      CTREPCMSim sim = new CTREPCMSim(2);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      org.wpilib.hardware.hal.simulation.CTREPCMDataJNI.registerAllNonSolenoidCallbacks(
          2, (n, v) -> calls.incrementAndGet(), false);
      org.wpilib.hardware.hal.simulation.CTREPCMDataJNI.registerAllSolenoidCallbacks(
          2, 1, (n, v) -> calls.incrementAndGet(), false);
      sim.setCompressorOn(true);
      sim.setPressureSwitch(true);
      sim.setCompressorCurrent(1.0);
      sim.setClosedLoopEnabled(false);
      sim.setInitialized(true);
      sim.setSolenoidOutput(1, true);
      sim.setSolenoidOutput(0, true);
      Tests.eq(6, calls.get(), "non solenoid callbacks plus solenoid 1");
      sim.resetData();
    });
  }

  private static void revPh(Tests t) {

    t.test("ph sim callbacks and reset", () -> {
      REVPHSim sim = new REVPHSim(4);
      sim.resetData();
      List<Integer> configs = new ArrayList<>();
      AtomicInteger solenoid = new AtomicInteger();
      CallbackStore a = sim.registerCompressorConfigTypeCallback((n, v) -> configs.add((int) v.getLong()), false);
      CallbackStore b = sim.registerSolenoidOutputCallback(9, (n, v) -> solenoid.incrementAndGet(), false);
      sim.setCompressorConfigType(2);
      sim.setCompressorConfigType(2);
      sim.setCompressorConfigType(3);
      sim.setSolenoidOutput(9, true);
      sim.setSolenoidOutput(8, true);
      Tests.eq(List.of(2, 3), configs, "config callbacks");
      Tests.eq(1, solenoid.get(), "solenoid callbacks");
      a.close();
      b.close();
      sim.resetData();
      Tests.eq(0, sim.getCompressorConfigType(), "config reset");
      Tests.check(!sim.getSolenoidOutput(9), "solenoid reset");
    });

    t.test("ph register all callbacks", () -> {
      REVPHSim sim = new REVPHSim(5);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      org.wpilib.hardware.hal.simulation.REVPHDataJNI.registerAllNonSolenoidCallbacks(
          5, (n, v) -> calls.incrementAndGet(), false);
      sim.setCompressorOn(true);
      sim.setPressureSwitch(true);
      sim.setCompressorCurrent(1.0);
      sim.setCompressorConfigType(2);
      sim.setInitialized(true);
      Tests.eq(5, calls.get(), "non solenoid callbacks");
      sim.resetData();
    });
  }

  private static void ws(Tests t) {

    t.test("ws RoboRIO full state is resent on hello", () -> {
      RoboRioSim.resetData();
      RoboRioSim.setUserCurrent3V3(1.25);
      BridgeLoop.flush();
      BridgeLoop.local().clearSent();
      BridgeLoop.local().inject("{\"type\":\"hello\"}");
      BridgeLoop.poll();
      waitFor("resend", () -> sentValue("RoboRIO", "", ">3v3_current") != null);
      Object v = sentValue("RoboRIO", "", ">3v3_current");
      Tests.check(v instanceof Number n && n.doubleValue() == 1.25, "3v3 current: " + v);
      Tests.check(sentValue("RoboRIO", "", ">vin_voltage") != null, "vin voltage resent");
      RoboRioSim.resetData();
    });

    t.test("ws CTREPCM inputs work before the robot creates the pcm", () -> {
      CTREPCMSim sim = new CTREPCMSim(9);
      sim.resetData();
      inject("CTREPCM", "9", ">pressure_switch", true);
      waitFor("pressure switch", sim::getPressureSwitch);
      sim.resetData();
    });

  }
}
