package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import edu.wpi.first.hal.CTREPCMJNI;
import edu.wpi.first.hal.ConstantsJNI;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.hal.HALUtil;
import edu.wpi.first.hal.PortsJNI;
import edu.wpi.first.hal.PowerDistributionJNI;
import edu.wpi.first.hal.PowerDistributionVersion;
import edu.wpi.first.hal.REVPHJNI;
import edu.wpi.first.hal.REVPHVersion;
import edu.wpi.first.hal.ThreadsJNI;
import edu.wpi.first.hal.util.AllocationException;
import edu.wpi.first.hal.util.HalHandleException;
import edu.wpi.first.hal.util.UncleanStatusException;
import edu.wpi.first.wpilibj.Compressor;
import edu.wpi.first.wpilibj.CompressorConfigType;
import edu.wpi.first.wpilibj.DoubleSolenoid;
import edu.wpi.first.wpilibj.PneumaticHub;
import edu.wpi.first.wpilibj.PneumaticsControlModule;
import edu.wpi.first.wpilibj.PneumaticsModuleType;
import edu.wpi.first.wpilibj.PowerDistribution;
import edu.wpi.first.wpilibj.RobotController;
import edu.wpi.first.wpilibj.Solenoid;
import edu.wpi.first.wpilibj.simulation.CTREPCMSim;
import edu.wpi.first.wpilibj.simulation.CallbackStore;
import edu.wpi.first.wpilibj.simulation.PDPSim;
import edu.wpi.first.wpilibj.simulation.REVPHSim;
import edu.wpi.first.wpilibj.simulation.RoboRioSim;
import edu.wpi.first.wpilibj.simulation.SolenoidSim;
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
    HAL.initialize(500, 0);
    halUtil(t);
    ports(t);
    roboRio(t);
    powerDistribution(t);
    ctrePcm(t);
    revPh(t);
    ws(t);
  }

  private static void halUtil(Tests t) {
    t.test("halutil reports simulation runtime and fixed versions", () -> {
      Tests.eq(HALUtil.RUNTIME_SIMULATION, HALUtil.getHALRuntimeType(), "runtime type");
      Tests.eq(2, HALUtil.getHALRuntimeType(), "simulation is 2");
      Tests.eq((short) 2018, HALUtil.getFPGAVersion(), "fpga version");
      Tests.eq(0, HALUtil.getFPGARevision(), "fpga revision");
      Tests.eq(40, ConstantsJNI.getSystemClockTicksPerMicrosecond(), "clock ticks");
    });

    t.test("halutil fpga time follows the sim clock", () -> {
      long a = HALUtil.getFPGATime();
      Thread.sleep(10);
      long b = HALUtil.getFPGATime();
      Tests.check(b - a >= 5000, "time advanced by " + (b - a));
      Tests.check(RobotController.getFPGATime() >= b, "RobotController uses the same clock");
    });

    t.test("halutil reads roborio data", () -> {
      RoboRioSim.resetData();
      Tests.eq(0, HALUtil.getTeamNumber(), "default team");
      RoboRioSim.setTeamNumber(516);
      RoboRioSim.setSerialNumber("0123ABCD");
      RoboRioSim.setComments("bench rio");
      RoboRioSim.setFPGAButton(true);
      Tests.eq(516, HALUtil.getTeamNumber(), "team");
      Tests.eq("0123ABCD", HALUtil.getSerialNumber(), "serial");
      Tests.eq("bench rio", HALUtil.getComments(), "comments");
      Tests.check(HALUtil.getFPGAButton(), "fpga button");
      Tests.check(RobotController.getUserButton(), "user button");
      RoboRioSim.resetData();
      Tests.eq("", HALUtil.getSerialNumber(), "serial reset");
      Tests.check(!HALUtil.getFPGAButton(), "button reset");
    });

    t.test("halutil error messages", () -> {
      Tests.eq("", HALUtil.getHALErrorMessage(0), "success");
      Tests.eq("HAL: Resource already allocated", HALUtil.getHALErrorMessage(-1029), "allocated");
      Tests.eq(
          "HAL: A handle parameter was passed incorrectly",
          HALUtil.getHALErrorMessage(-1098),
          "handle");
      Tests.eq("Unknown error status", HALUtil.getHALErrorMessage(-9999), "unknown");
    });

    t.test("thread priority is a simulated no-op", () -> {
      Tests.eq(0, ThreadsJNI.getCurrentThreadPriority(), "priority");
      Tests.check(!ThreadsJNI.getCurrentThreadIsRealTime(), "not real time");
      Tests.check(ThreadsJNI.setCurrentThreadPriority(true, 40), "set priority");
    });
  }

  private static void ports(Tests t) {
    t.test("ports report the roborio resource counts", () -> {
      Tests.eq(2, PortsJNI.getNumAccumulators(), "accumulators");
      Tests.eq(8, PortsJNI.getNumAnalogInputs(), "analog inputs");
      Tests.eq(2, PortsJNI.getNumAnalogOutputs(), "analog outputs");
      Tests.eq(8, PortsJNI.getNumAnalogTriggers(), "analog triggers");
      Tests.eq(8, PortsJNI.getNumCounters(), "counters");
      Tests.eq(10, PortsJNI.getNumDigitalHeaders(), "digital headers");
      Tests.eq(31, PortsJNI.getNumDigitalChannels(), "digital channels");
      Tests.eq(6, PortsJNI.getNumDigitalPWMOutputs(), "digital pwm outputs");
      Tests.eq(8, PortsJNI.getNumEncoders(), "encoders");
      Tests.eq(8, PortsJNI.getNumInterrupts(), "interrupts");
      Tests.eq(10, PortsJNI.getNumPWMHeaders(), "pwm headers");
      Tests.eq(20, PortsJNI.getNumPWMChannels(), "pwm channels");
      Tests.eq(8, PortsJNI.getNumRelayChannels(), "relay channels");
      Tests.eq(4, PortsJNI.getNumRelayHeaders(), "relay headers");
    });

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
    t.test("roborio defaults", () -> {
      RoboRioSim.resetData();
      Tests.eq(12.0, RoboRioSim.getVInVoltage(), "vin voltage");
      Tests.eq(0.0, RoboRioSim.getVInCurrent(), "vin current");
      Tests.eq(6.0, RoboRioSim.getUserVoltage6V(), "6v");
      Tests.eq(5.0, RoboRioSim.getUserVoltage5V(), "5v");
      Tests.eq(3.3, RoboRioSim.getUserVoltage3V3(), "3v3");
      Tests.check(RoboRioSim.getUserActive6V(), "6v active");
      Tests.check(RoboRioSim.getUserActive5V(), "5v active");
      Tests.check(RoboRioSim.getUserActive3V3(), "3v3 active");
      Tests.eq(0, RoboRioSim.getUserFaults6V(), "6v faults");
      Tests.eq(6.75, RoboRioSim.getBrownoutVoltage(), "brownout voltage");
      Tests.eq(45.0, RoboRioSim.getCPUTemp(), "cpu temp");
      Tests.eq(0, RoboRioSim.getTeamNumber(), "team number");
      Tests.eq("", RoboRioSim.getSerialNumber(), "serial");
      Tests.eq("", RoboRioSim.getComments(), "comments");
    });

    t.test("roborio sim values reach RobotController", () -> {
      RoboRioSim.resetData();
      RoboRioSim.setVInVoltage(10.5);
      RoboRioSim.setVInCurrent(3.25);
      RoboRioSim.setUserVoltage6V(5.9);
      RoboRioSim.setUserCurrent6V(1.5);
      RoboRioSim.setUserVoltage5V(4.9);
      RoboRioSim.setUserCurrent5V(0.5);
      RoboRioSim.setUserVoltage3V3(3.2);
      RoboRioSim.setUserCurrent3V3(0.25);
      RoboRioSim.setCPUTemp(61.5);
      RoboRioSim.setBrownoutVoltage(7.5);
      Tests.eq(10.5, RobotController.getBatteryVoltage(), "battery");
      Tests.eq(10.5, RobotController.getInputVoltage(), "input voltage");
      Tests.eq(3.25, RobotController.getInputCurrent(), "input current");
      Tests.eq(5.9, RobotController.getVoltage6V(), "6v voltage");
      Tests.eq(1.5, RobotController.getCurrent6V(), "6v current");
      Tests.eq(4.9, RobotController.getVoltage5V(), "5v voltage");
      Tests.eq(0.5, RobotController.getCurrent5V(), "5v current");
      Tests.eq(3.2, RobotController.getVoltage3V3(), "3v3 voltage");
      Tests.eq(0.25, RobotController.getCurrent3V3(), "3v3 current");
      Tests.eq(61.5, RobotController.getCPUTemp(), "cpu temp");
      Tests.eq(7.5, RobotController.getBrownoutVoltage(), "brownout");
      RobotController.setBrownoutVoltage(6.0);
      Tests.eq(6.0, RoboRioSim.getBrownoutVoltage(), "brownout set from robot code");
    });

    t.test("roborio rail enable is a no-op and faults reset", () -> {
      RoboRioSim.resetData();
      RobotController.setEnabled5V(false);
      Tests.check(RobotController.getEnabled5V(), "5v stays active");
      RoboRioSim.setUserActive3V3(false);
      Tests.check(!RobotController.getEnabled3V3(), "3v3 active from sim");
      RoboRioSim.setUserFaults6V(2);
      RoboRioSim.setUserFaults5V(3);
      RoboRioSim.setUserFaults3V3(4);
      Tests.eq(2, RobotController.getFaultCount6V(), "6v faults");
      Tests.eq(3, RobotController.getFaultCount5V(), "5v faults");
      Tests.eq(4, RobotController.getFaultCount3V3(), "3v3 faults");
      RobotController.resetRailFaultCounts();
      Tests.eq(0, RoboRioSim.getUserFaults6V(), "6v faults cleared");
      Tests.eq(0, RoboRioSim.getUserFaults5V(), "5v faults cleared");
      Tests.eq(0, RoboRioSim.getUserFaults3V3(), "3v3 faults cleared");
    });

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

    t.test("roborio callbacks for team number, brownout and faults", () -> {
      RoboRioSim.resetData();
      AtomicInteger team = new AtomicInteger();
      AtomicInteger faults = new AtomicInteger();
      AtomicInteger brownout = new AtomicInteger();
      try (CallbackStore a =
              RoboRioSim.registerTeamNumberCallback((n, v) -> team.set((int) v.getLong()), false);
          CallbackStore b =
              RoboRioSim.registerUserFaults5VCallback((n, v) -> faults.set((int) v.getLong()), false);
          CallbackStore c =
              RoboRioSim.registerBrownoutVoltageCallback(
                  (n, v) -> brownout.incrementAndGet(), false)) {
        RoboRioSim.setTeamNumber(516);
        RoboRioSim.setUserFaults5V(9);
        RoboRioSim.setBrownoutVoltage(7.0);
        Tests.eq(516, team.get(), "team callback");
        Tests.eq(9, faults.get(), "faults callback");
        Tests.eq(1, brownout.get(), "brownout callback");
      }
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
    t.test("pdp default module is a CTRE PDP at module 0", () -> {
      PDPSim sim = new PDPSim(0);
      sim.resetData();
      try (PowerDistribution pd = new PowerDistribution()) {
        Tests.check(sim.getInitialized(), "initialized");
        Tests.eq(0, pd.getModule(), "module");
        Tests.eq(PowerDistribution.ModuleType.kCTRE, pd.getType(), "type");
        Tests.eq(24, pd.getNumChannels(), "channels");
        Tests.eq(12.0, pd.getVoltage(), "default voltage");
        Tests.eq(0.0, pd.getTemperature(), "default temperature");
      }
    });

    t.test("pdp reports channel and total current from the sim", () -> {
      PDPSim sim = new PDPSim(0);
      sim.resetData();
      try (PowerDistribution pd = new PowerDistribution(0, PowerDistribution.ModuleType.kCTRE)) {
        sim.setCurrent(3, 5.5);
        sim.setCurrent(7, 2.5);
        sim.setCurrent(15, 1.0);
        sim.setVoltage(11.5);
        sim.setTemperature(40.0);
        Tests.eq(5.5, pd.getCurrent(3), "channel 3");
        Tests.eq(2.5, pd.getCurrent(7), "channel 7");
        Tests.eq(9.0, pd.getTotalCurrent(), "total current");
        Tests.eq(11.5, pd.getVoltage(), "voltage");
        Tests.near(11.5 * 9.0, pd.getTotalPower(), 1e-9, "total power");
        Tests.eq(40.0, pd.getTemperature(), "temperature");
        Tests.eq(0.0, pd.getTotalEnergy(), "energy");
        double[] all = pd.getAllCurrents();
        Tests.eq(24, all.length, "all currents length");
        Tests.eq(5.5, all[3], "all currents 3");
        Tests.eq(1.0, all[15], "all currents 15");
        pd.resetTotalEnergy();
        pd.clearStickyFaults();
        Tests.check(!pd.getSwitchableChannel(), "switchable channel");
        pd.setSwitchableChannel(true);
        Tests.check(!pd.getSwitchableChannel(), "switchable channel unchanged");
      }
    });

    t.test("pdh module 2 uses its own sim data and reports REV numbering", () -> {
      PDPSim sim = new PDPSim(2);
      PDPSim other = new PDPSim(1);
      sim.resetData();
      other.resetData();
      try (PowerDistribution pd = new PowerDistribution(2, PowerDistribution.ModuleType.kRev)) {
        Tests.eq(2, pd.getModule(), "module");
        Tests.check(sim.getInitialized(), "module 2 initialized");
        Tests.check(!other.getInitialized(), "module 1 untouched");
        sim.setCurrent(20, 4.0);
        Tests.eq(4.0, pd.getCurrent(20), "channel 20");
        Tests.eq(0.0, other.getCurrent(20), "other module current");
      }
    });

    t.test("pdp module numbers are range checked per type", () -> {
      Tests.check(PowerDistributionJNI.checkModule(0, PowerDistributionJNI.CTRE_TYPE), "ctre 0");
      Tests.check(PowerDistributionJNI.checkModule(62, PowerDistributionJNI.CTRE_TYPE), "ctre 62");
      Tests.check(!PowerDistributionJNI.checkModule(63, PowerDistributionJNI.CTRE_TYPE), "ctre 63");
      Tests.check(!PowerDistributionJNI.checkModule(-1, PowerDistributionJNI.CTRE_TYPE), "ctre -1");
      Tests.check(!PowerDistributionJNI.checkModule(0, PowerDistributionJNI.REV_TYPE), "rev 0");
      Tests.check(PowerDistributionJNI.checkModule(1, PowerDistributionJNI.REV_TYPE), "rev 1");
      Tests.check(PowerDistributionJNI.checkModule(63, PowerDistributionJNI.REV_TYPE), "rev 63");
      Tests.check(!PowerDistributionJNI.checkModule(64, PowerDistributionJNI.REV_TYPE), "rev 64");

      AllocationException ctre =
          expect(AllocationException.class, () -> PowerDistributionJNI.initialize(63, 1));
      Tests.check(ctre.getMessage().contains("Invalid Index for CTRE PDP"), ctre.getMessage());
      Tests.check(ctre.getMessage().contains("Minimum: 0 Maximum: 62 Requested: 63"), ctre.getMessage());
      AllocationException rev =
          expect(AllocationException.class, () -> PowerDistributionJNI.initialize(0, 2));
      Tests.check(rev.getMessage().contains("Invalid Index for REV PDH"), rev.getMessage());
      Tests.check(rev.getMessage().contains("Minimum: 1 Maximum: 63 Requested: 0"), rev.getMessage());
    });

    t.test("pdp automatic type requires the default module", () -> {
      UncleanStatusException e =
          expect(UncleanStatusException.class, () -> PowerDistributionJNI.initialize(5, 0));
      Tests.check(
          e.getMessage().contains("Automatic PowerDistributionType must have default module"),
          e.getMessage());
      int handle = PowerDistributionJNI.initialize(PowerDistributionJNI.DEFAULT_MODULE, 0);
      Tests.eq(0, PowerDistributionJNI.getModuleNumber(handle), "automatic resolves to module 0");
      Tests.eq(PowerDistributionJNI.CTRE_TYPE, PowerDistributionJNI.getType(handle), "type");
      PowerDistributionJNI.free(handle);
    });

    t.test("pdp handle errors", () -> {
      new PDPSim(0).resetData();
      int handle = PowerDistributionJNI.initialize(0, PowerDistributionJNI.CTRE_TYPE);
      Tests.eq(24, PowerDistributionJNI.getNumChannels(handle), "channels");
      Tests.check(PowerDistributionJNI.checkChannel(handle, 23), "channel 23 valid");
      Tests.check(!PowerDistributionJNI.checkChannel(handle, 24), "channel 24 invalid");
      Tests.check(!PowerDistributionJNI.checkChannel(handle, -1), "channel -1 invalid");
      PowerDistributionVersion v = PowerDistributionJNI.getVersion(handle);
      Tests.eq(0, v.firmwareMajor, "version");
      Tests.eq(0, PowerDistributionJNI.getFaultsNative(handle), "faults");
      Tests.eq(0, PowerDistributionJNI.getStickyFaultsNative(handle), "sticky faults");
      PowerDistributionJNI.free(handle);
      expect(HalHandleException.class, () -> PowerDistributionJNI.getVoltage(handle));
      expect(HalHandleException.class, () -> PowerDistributionJNI.getTemperature(handle));
      expect(HalHandleException.class, () -> PowerDistributionJNI.getTotalCurrent(handle));
      expect(HalHandleException.class, () -> PowerDistributionJNI.getChannelCurrent(handle, 1));
      expect(HalHandleException.class, () -> PowerDistributionJNI.getModuleNumber(handle));
      expect(HalHandleException.class, () -> PowerDistributionJNI.getAllCurrents(handle, new double[24]));
      Tests.eq(0.0, PowerDistributionJNI.getVoltageNoError(handle), "no error voltage");
      Tests.eq(0.0, PowerDistributionJNI.getTotalCurrentNoError(handle), "no error total");
      Tests.eq(0.0, PowerDistributionJNI.getChannelCurrentNoError(handle, 1), "no error current");
      Tests.check(!PowerDistributionJNI.getSwitchableChannelNoError(handle), "no error switch");
      PowerDistributionJNI.free(0);
    });

    t.test("pdp keeps its sim initialized flag after free", () -> {
      PDPSim sim = new PDPSim(4);
      sim.resetData();
      PowerDistribution pd = new PowerDistribution(4, PowerDistribution.ModuleType.kCTRE);
      pd.close();
      Tests.check(sim.getInitialized(), "still initialized");
      sim.resetData();
      Tests.check(!sim.getInitialized(), "reset clears it");
    });

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
    t.test("pcm initializes with closed loop enabled", () -> {
      CTREPCMSim sim = new CTREPCMSim(0);
      sim.resetData();
      Tests.check(!sim.getInitialized(), "not initialized");
      try (PneumaticsControlModule pcm = new PneumaticsControlModule()) {
        Tests.check(sim.getInitialized(), "initialized");
        Tests.check(sim.getClosedLoopEnabled(), "closed loop");
        Tests.eq(0, pcm.getModuleNumber(), "module number");
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("pcm compressor state comes from the sim", () -> {
      CTREPCMSim sim = new CTREPCMSim(0);
      sim.resetData();
      try (PneumaticsControlModule pcm = new PneumaticsControlModule()) {
        Tests.check(!pcm.getCompressor(), "compressor off");
        sim.setCompressorOn(true);
        sim.setPressureSwitch(true);
        sim.setCompressorCurrent(7.25);
        Tests.check(pcm.getCompressor(), "compressor on");
        Tests.check(pcm.getPressureSwitch(), "pressure switch");
        Tests.eq(7.25, pcm.getCompressorCurrent(), "compressor current");
        Tests.check(!pcm.getCompressorCurrentTooHighFault(), "too high fault");
        Tests.check(!pcm.getCompressorShortedFault(), "shorted fault");
        Tests.check(!pcm.getCompressorNotConnectedFault(), "not connected fault");
        Tests.check(!pcm.getSolenoidVoltageFault(), "voltage fault");
        Tests.eq(0, pcm.getSolenoidDisabledList(), "disabled list");
      }
    });

    t.test("compressor enable and disable drive the sim closed loop flag", () -> {
      CTREPCMSim sim = new CTREPCMSim(0);
      sim.resetData();
      try (Compressor compressor = new Compressor(PneumaticsModuleType.CTREPCM)) {
        Tests.check(sim.getClosedLoopEnabled(), "enabled by default");
        compressor.disable();
        Tests.check(!sim.getClosedLoopEnabled(), "disabled");
        Tests.eq(CompressorConfigType.Disabled, compressor.getConfigType(), "disabled type");
        compressor.enableDigital();
        Tests.check(sim.getClosedLoopEnabled(), "enabled");
        Tests.eq(CompressorConfigType.Digital, compressor.getConfigType(), "digital type");
        Tests.check(!compressor.isEnabled(), "not running");
        sim.setCompressorOn(true);
        Tests.check(compressor.isEnabled(), "running");
        sim.setPressureSwitch(true);
        Tests.check(compressor.getPressureSwitchValue(), "pressure switch");
        sim.setCompressorCurrent(3.5);
        Tests.eq(3.5, compressor.getCurrent(), "current");
      }
    });

    t.test("solenoid output reaches the sim and the pcm mask", () -> {
      CTREPCMSim sim = new CTREPCMSim(0);
      sim.resetData();
      try (Solenoid s3 = new Solenoid(PneumaticsModuleType.CTREPCM, 3);
          Solenoid s5 = new Solenoid(PneumaticsModuleType.CTREPCM, 5)) {
        SolenoidSim s3sim = new SolenoidSim(PneumaticsModuleType.CTREPCM, 3);
        Tests.check(!s3.get(), "off");
        s3.set(true);
        Tests.check(sim.getSolenoidOutput(3), "sim output");
        Tests.check(s3sim.getOutput(), "solenoid sim output");
        Tests.check(s3.get(), "get");
        s5.set(true);
        s3.toggle();
        Tests.check(!sim.getSolenoidOutput(3), "toggled off");
        Tests.check(sim.getSolenoidOutput(5), "channel 5 on");
        s3sim.setOutput(true);
        Tests.check(s3.get(), "set from sim");
        Tests.eq(3, s3.getChannel(), "channel");
      }
    });

    t.test("double solenoid sets forward and reverse channels", () -> {
      CTREPCMSim sim = new CTREPCMSim(0);
      sim.resetData();
      try (DoubleSolenoid ds = new DoubleSolenoid(PneumaticsModuleType.CTREPCM, 1, 2)) {
        ds.set(DoubleSolenoid.Value.kForward);
        Tests.check(sim.getSolenoidOutput(1), "forward on");
        Tests.check(!sim.getSolenoidOutput(2), "reverse off");
        Tests.eq(DoubleSolenoid.Value.kForward, ds.get(), "get forward");
        ds.set(DoubleSolenoid.Value.kReverse);
        Tests.check(!sim.getSolenoidOutput(1), "forward off");
        Tests.check(sim.getSolenoidOutput(2), "reverse on");
        Tests.eq(DoubleSolenoid.Value.kReverse, ds.get(), "get reverse");
        ds.set(DoubleSolenoid.Value.kOff);
        Tests.check(!sim.getSolenoidOutput(1) && !sim.getSolenoidOutput(2), "both off");
        Tests.eq(DoubleSolenoid.Value.kOff, ds.get(), "get off");
        ds.set(DoubleSolenoid.Value.kReverse);
        ds.toggle();
        Tests.eq(DoubleSolenoid.Value.kForward, ds.get(), "toggle from reverse");
      }
    });

    t.test("solenoid channel conflicts and ranges", () -> {
      try (Solenoid s = new Solenoid(PneumaticsModuleType.CTREPCM, 2)) {
        expect(AllocationException.class, () -> new Solenoid(PneumaticsModuleType.CTREPCM, 2));
      }
      expect(IllegalArgumentException.class, () -> new Solenoid(PneumaticsModuleType.CTREPCM, 8));
      Tests.check(CTREPCMJNI.checkSolenoidChannel(7), "channel 7");
      Tests.check(!CTREPCMJNI.checkSolenoidChannel(8), "channel 8");
      Tests.check(!CTREPCMJNI.checkSolenoidChannel(-1), "channel -1");
    });

    t.test("pcm solenoid callbacks fire only for their channel", () -> {
      CTREPCMSim sim = new CTREPCMSim(0);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      List<Boolean> values = new ArrayList<>();
      try (CallbackStore cb =
              sim.registerSolenoidOutputCallback(
                  4,
                  (n, v) -> {
                    calls.incrementAndGet();
                    values.add(v.getBoolean());
                  },
                  false);
          Solenoid s4 = new Solenoid(PneumaticsModuleType.CTREPCM, 4);
          Solenoid s6 = new Solenoid(PneumaticsModuleType.CTREPCM, 6)) {
        s6.set(true);
        Tests.eq(0, calls.get(), "other channel");
        s4.set(true);
        s4.set(true);
        Tests.eq(1, calls.get(), "channel 4 changed once");
        s4.set(false);
        Tests.eq(2, calls.get(), "channel 4 changed again");
        Tests.eq(List.of(true, false), values, "values");
      }
    });

    t.test("pcm initialized and compressor callbacks", () -> {
      CTREPCMSim sim = new CTREPCMSim(0);
      sim.resetData();
      List<Boolean> init = new ArrayList<>();
      AtomicInteger loop = new AtomicInteger();
      CallbackStore a = sim.registerInitializedCallback((n, v) -> init.add(v.getBoolean()), false);
      CallbackStore b = sim.registerClosedLoopEnabledCallback((n, v) -> loop.incrementAndGet(), false);
      try (PneumaticsControlModule pcm = new PneumaticsControlModule()) {
        Tests.eq(List.of(true), init, "initialized true");
        Tests.eq(0, loop.get(), "closed loop already true");
        pcm.disableCompressor();
        Tests.eq(1, loop.get(), "closed loop changed");
      }
      Tests.eq(List.of(true, false), init, "initialized false after free");
      a.close();
      b.close();
    });

    t.test("pcm module range, double allocation and handle errors", () -> {
      new CTREPCMSim(5).resetData();
      AllocationException range = expect(AllocationException.class, () -> CTREPCMJNI.initialize(63));
      Tests.check(range.getMessage().contains("Invalid Index for CTRE PCM"), range.getMessage());
      Tests.check(range.getMessage().contains("Minimum: 0 Maximum: 62 Requested: 63"), range.getMessage());
      expect(AllocationException.class, () -> CTREPCMJNI.initialize(-1));

      int handle = CTREPCMJNI.initialize(5);
      AllocationException twice = expect(AllocationException.class, () -> CTREPCMJNI.initialize(5));
      Tests.check(twice.getMessage().contains("CTRE PCM 5 previously allocated"), twice.getMessage());

      CTREPCMJNI.setSolenoids(handle, 0x0F, 0x05);
      Tests.eq(0x05, CTREPCMJNI.getSolenoids(handle), "mask after set");
      Tests.check(new CTREPCMSim(5).getSolenoidOutput(0), "bit 0");
      Tests.check(!new CTREPCMSim(5).getSolenoidOutput(1), "bit 1");
      Tests.check(new CTREPCMSim(5).getSolenoidOutput(2), "bit 2");
      CTREPCMJNI.setSolenoids(handle, 0x01, 0x00);
      Tests.eq(0x04, CTREPCMJNI.getSolenoids(handle), "mask after clear");

      CTREPCMJNI.free(handle);
      Tests.check(!new CTREPCMSim(5).getInitialized(), "freed");
      expect(HalHandleException.class, () -> CTREPCMJNI.getCompressor(handle));
      expect(HalHandleException.class, () -> CTREPCMJNI.getSolenoids(handle));
      expect(HalHandleException.class, () -> CTREPCMJNI.setSolenoids(handle, 1, 1));
      expect(HalHandleException.class, () -> CTREPCMJNI.setClosedLoopControl(handle, true));
      expect(HalHandleException.class, () -> CTREPCMJNI.getCompressorCurrent(handle));
      CTREPCMJNI.free(handle);
      CTREPCMJNI.free(0);

      int again = CTREPCMJNI.initialize(5);
      CTREPCMJNI.free(again);
    });

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
      edu.wpi.first.hal.simulation.CTREPCMDataJNI.registerAllNonSolenoidCallbacks(
          2, (n, v) -> calls.incrementAndGet(), false);
      edu.wpi.first.hal.simulation.CTREPCMDataJNI.registerAllSolenoidCallbacks(
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
    t.test("ph initializes with the digital compressor config", () -> {
      REVPHSim sim = new REVPHSim(2);
      sim.resetData();
      Tests.eq(0, sim.getCompressorConfigType(), "default config");
      try (PneumaticHub ph = new PneumaticHub(2)) {
        Tests.check(sim.getInitialized(), "initialized");
        Tests.eq(2, ph.getModuleNumber(), "module number");
        Tests.eq(1, sim.getCompressorConfigType(), "digital config");
        Tests.eq(CompressorConfigType.Digital, ph.getCompressorConfigType(), "config type");
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("ph compressor config modes", () -> {
      REVPHSim sim = new REVPHSim(2);
      sim.resetData();
      try (PneumaticHub ph = new PneumaticHub(2)) {
        ph.disableCompressor();
        Tests.eq(0, sim.getCompressorConfigType(), "disabled");
        ph.enableCompressorAnalog(60, 120);
        Tests.eq(2, sim.getCompressorConfigType(), "analog");
        Tests.eq(CompressorConfigType.Analog, ph.getCompressorConfigType(), "analog enum");
        ph.enableCompressorHybrid(60, 120);
        Tests.eq(3, sim.getCompressorConfigType(), "hybrid");
        ph.enableCompressorDigital();
        Tests.eq(1, sim.getCompressorConfigType(), "digital");
      }
    });

    t.test("ph compressor state, solenoids and sim values", () -> {
      REVPHSim sim = new REVPHSim(3);
      sim.resetData();
      try (PneumaticHub ph = new PneumaticHub(3);
          Solenoid s = new Solenoid(3, PneumaticsModuleType.REVPH, 15);
          DoubleSolenoid ds = new DoubleSolenoid(3, PneumaticsModuleType.REVPH, 0, 1)) {
        Tests.check(!ph.getCompressor(), "compressor off");
        sim.setCompressorOn(true);
        sim.setPressureSwitch(true);
        sim.setCompressorCurrent(4.5);
        Tests.check(ph.getCompressor(), "compressor on");
        Tests.check(ph.getPressureSwitch(), "pressure switch");
        Tests.eq(4.5, ph.getCompressorCurrent(), "current");
        s.set(true);
        Tests.check(sim.getSolenoidOutput(15), "channel 15");
        Tests.eq(0x8000, ph.getSolenoids(), "mask");
        ds.set(DoubleSolenoid.Value.kReverse);
        Tests.check(sim.getSolenoidOutput(1) && !sim.getSolenoidOutput(0), "double reverse");
        Tests.eq(0x8002, ph.getSolenoids(), "mask with double solenoid");
        Tests.eq(0.0, ph.getInputVoltage(), "input voltage");
        Tests.eq(0.0, ph.get5VRegulatedVoltage(), "5v voltage");
        Tests.eq(0.0, ph.getSolenoidsTotalCurrent(), "solenoid current");
        Tests.eq(0.0, ph.getSolenoidsVoltage(), "solenoid voltage");
        Tests.eq(0.0, ph.getAnalogVoltage(0), "analog voltage");
        Tests.eq(0, ph.getSolenoidDisabledList(), "disabled list");
      }
    });

    t.test("ph module range, double allocation and handle errors", () -> {
      AllocationException low = expect(AllocationException.class, () -> REVPHJNI.initialize(0));
      Tests.check(low.getMessage().contains("Invalid Index for REV PH"), low.getMessage());
      Tests.check(low.getMessage().contains("Minimum: 1 Maximum: 63 Requested: 0"), low.getMessage());
      expect(AllocationException.class, () -> REVPHJNI.initialize(64));

      int handle = REVPHJNI.initialize(1);
      AllocationException twice = expect(AllocationException.class, () -> REVPHJNI.initialize(1));
      Tests.check(twice.getMessage().contains("REV PH 1 previously allocated"), twice.getMessage());
      Tests.check(REVPHJNI.checkSolenoidChannel(15), "channel 15");
      Tests.check(!REVPHJNI.checkSolenoidChannel(16), "channel 16");

      REVPHJNI.setSolenoids(handle, 0xFFFF, 0x1234);
      Tests.eq(0x1234, REVPHJNI.getSolenoids(handle), "mask");
      REVPHVersion v = REVPHJNI.getVersion(handle);
      Tests.eq(0, v.uniqueId, "version");
      Tests.eq(0, REVPHJNI.getFaultsNative(handle), "faults");
      Tests.eq(0, REVPHJNI.getStickyFaultsNative(handle), "sticky faults");

      REVPHJNI.free(handle);
      Tests.check(!new REVPHSim(1).getInitialized(), "freed");
      expect(HalHandleException.class, () -> REVPHJNI.getCompressor(handle));
      expect(HalHandleException.class, () -> REVPHJNI.getSolenoids(handle));
      expect(HalHandleException.class, () -> REVPHJNI.setClosedLoopControlDigital(handle));
      expect(HalHandleException.class, () -> REVPHJNI.getCompressorConfig(handle));
      REVPHJNI.free(handle);
      new REVPHSim(1).resetData();
    });

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
      edu.wpi.first.hal.simulation.REVPHDataJNI.registerAllNonSolenoidCallbacks(
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
    t.test("ws RoboRIO inputs from the page and changes to the page", () -> {
      RoboRioSim.resetData();
      inject("RoboRIO", "", ">vin_voltage", 9.5);
      waitFor("vin voltage", () -> RoboRioSim.getVInVoltage() == 9.5);
      inject("RoboRIO", "", ">6v_faults", 3);
      inject("RoboRIO", "", ">5v_active", false);
      waitFor("faults", () -> RoboRioSim.getUserFaults6V() == 3);
      waitFor("active", () -> !RoboRioSim.getUserActive5V());

      BridgeLoop.flush();
      BridgeLoop.local().clearSent();
      RoboRioSim.setVInCurrent(2.5);
      Object sent = sentValue("RoboRIO", "", ">vin_current");
      Tests.check(sent instanceof Number n && n.doubleValue() == 2.5, "vin current sent: " + sent);
      RoboRioSim.resetData();
    });

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

    t.test("ws CTREPCM device follows the pcm", () -> {
      CTREPCMSim sim = new CTREPCMSim(7);
      sim.resetData();
      BridgeLoop.flush();
      BridgeLoop.local().clearSent();
      try (PneumaticsControlModule pcm = new PneumaticsControlModule(7)) {
        Tests.eq(true, sentValue("CTREPCM", "7", "<init"), "init sent");
        Tests.eq(true, sentValue("CTREPCM", "7", "<closed_loop"), "closed loop sent");

        inject("CTREPCM", "7", ">on", true);
        inject("CTREPCM", "7", ">pressure_switch", true);
        inject("CTREPCM", "7", ">current", 6.5);
        waitFor("pcm inputs", () -> sim.getCompressorOn() && sim.getPressureSwitch());
        waitFor("pcm current", () -> sim.getCompressorCurrent() == 6.5);
        Tests.check(pcm.getCompressor(), "robot sees compressor");

        inject("CTREPCM", "7", "<closed_loop", false);
        BridgeLoop.poll();
        Tests.check(sim.getClosedLoopEnabled(), "robot outputs are not set by the page");

        BridgeLoop.local().clearSent();
        pcm.disableCompressor();
        Tests.eq(false, sentValue("CTREPCM", "7", "<closed_loop"), "closed loop change sent");
      }
      Tests.eq(false, sentValue("CTREPCM", "7", "<init"), "free sent");
    });

    t.test("ws CTREPCM inputs work before the robot creates the pcm", () -> {
      CTREPCMSim sim = new CTREPCMSim(9);
      sim.resetData();
      inject("CTREPCM", "9", ">pressure_switch", true);
      waitFor("pressure switch", sim::getPressureSwitch);
      sim.resetData();
    });

    t.test("ws Solenoid output is sent as module,channel", () -> {
      CTREPCMSim sim = new CTREPCMSim(6);
      sim.resetData();
      BridgeLoop.flush();
      BridgeLoop.local().clearSent();
      try (Solenoid s = new Solenoid(6, PneumaticsModuleType.CTREPCM, 2)) {
        s.set(true);
        Tests.eq(true, sentValue("Solenoid", "6,2", "<output"), "output sent");
        s.set(false);
        Tests.eq(false, sentValue("Solenoid", "6,2", "<output"), "output cleared");
        inject("Solenoid", "6,2", "<output", true);
        BridgeLoop.poll();
        Tests.check(!s.get(), "page cannot set solenoid outputs");
      }
    });

    t.test("ws REVPH device follows the hub, solenoids are sent as module,channel", () -> {
      REVPHSim sim = new REVPHSim(5);
      sim.resetData();
      BridgeLoop.flush();
      BridgeLoop.local().clearSent();
      try (PneumaticHub ph = new PneumaticHub(5)) {
        Tests.eq(true, sentValue("REVPH", "5", "<init"), "init sent");
        Tests.eq(1L, ((Number) sentValue("REVPH", "5", "<config")).longValue(), "config sent");

        inject("REVPH", "5", ">pressure_switch", true);
        inject("REVPH", "5", ">current", 3.5);
        waitFor("ph inputs", () -> sim.getPressureSwitch() && sim.getCompressorCurrent() == 3.5);

        inject("REVPH", "5", "<config", 0);
        BridgeLoop.poll();
        Tests.eq(1, sim.getCompressorConfigType(), "page cannot set the config");

        ph.setSolenoids(0xFF, 0x04);
        Tests.eq(true, sentValue("REVPHSolenoid", "5,2", "<output"), "solenoid sent");
      }
      Tests.eq(false, sentValue("REVPH", "5", "<init"), "free sent");
      sim.resetData();
    });

    t.test("ws PowerDistribution sends channel currents as module,channel", () -> {
      try (PowerDistribution pd = new PowerDistribution(1, PowerDistribution.ModuleType.kRev)) {
        Tests.eq(true, sentValue("PowerDistribution", "1", "<init"), "init sent");
        inject("PowerDistribution", "1", ">voltage", 11.5);
        inject("PDChannel", "1,3", ">current", 7.25);
        waitFor("pd inputs", () -> pd.getVoltage() == 11.5 && pd.getCurrent(3) == 7.25);
        Tests.check(true, "inputs applied");
      }
    });
  }
}
