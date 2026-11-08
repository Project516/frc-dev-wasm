package dev.project516.frcdevwasm.shim.vendor.littletonrobotics.conduit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.littletonrobotics.conduit.schema.CoreInputs;
import org.littletonrobotics.conduit.schema.DSData;
import org.littletonrobotics.conduit.schema.Joystick;
import org.littletonrobotics.conduit.schema.JoystickTouchpad;
import org.littletonrobotics.conduit.schema.JoystickTouchpadFinger;
import org.littletonrobotics.conduit.schema.PDPData;
import org.littletonrobotics.conduit.schema.SystemData;
import org.wpilib.hardware.hal.DriverStationJNI;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.HALUtil;
import org.wpilib.hardware.hal.MatchInfoData;
import org.wpilib.hardware.hal.PowerDistributionJNI;
import org.wpilib.hardware.hal.PowerJNI;

/**
 * Port of AdvantageKit's conduit natives, as built for simulation: a CoreInputs struct in a shared
 * buffer, filled from the HAL on each capture. Fields that a simulated robot has no source for stay
 * zero.
 */
public final class ConduitJniImpl {
  private static final int BUF_SIZE = 100000;
  private static final int JOYSTICKS = DriverStationJNI.MAX_JOYSTICKS;
  private static final int AXES = DriverStationJNI.MAX_JOYSTICK_AXES;
  private static final int POVS = DriverStationJNI.MAX_JOYSTICK_POVS;
  private static final int TOUCHPADS = DriverStationJNI.MAX_JOYSTICK_TOUCHPADS;
  private static final int FINGERS = DriverStationJNI.MAX_JOYSTICK_TOUCHPAD_FINGERS;
  private static final int CHANNELS = 24;
  private static final int JOYSTICK_NAME_BYTES = 256;
  private static final int TEXT_BYTES = 65;

  private static ByteBuffer buffer;
  private static CoreInputs inputs;
  private static int pdHandle;

  private ConduitJniImpl() {}

  public static synchronized ByteBuffer getBuffer() {
    if (buffer == null) {
      buffer = ByteBuffer.allocateDirect(BUF_SIZE).order(ByteOrder.LITTLE_ENDIAN);
      inputs = new CoreInputs();
      inputs.__init(0, buffer);
    }
    return buffer;
  }

  public static void start() {}

  public static synchronized void capture() {
    getBuffer();
    inputs.mutateTimestamp(HALUtil.getMonotonicTime() / 1000);
    readDs(inputs.ds());
    readPdp(inputs.pdp());
    readSystem(inputs.sys());
  }

  public static synchronized void configurePowerDistribution(int bus, int module, int type) {
    getBuffer();
    PDPData pdp = inputs.pdp();
    try {
      pdHandle = PowerDistributionJNI.initialize(bus, module, type);
      pdp.mutateModuleId(PowerDistributionJNI.getModuleNumber(pdHandle));
      pdp.mutateType(PowerDistributionJNI.getType(pdHandle));
    } catch (RuntimeException e) {
      // The native reader ignores HAL errors in simulation.
    }
    pdp.mutateHandle(pdHandle);
    pdp.mutateChannelCount(CHANNELS);
  }

  private static void readDs(DSData ds) {
    float[] axes = new float[AXES];
    short[] rawAxes = new short[AXES];
    byte[] povs = new byte[POVS];
    float[] fingerXy = new float[TOUCHPADS * FINGERS * 2];
    long[] metadata = new long[5 + TOUCHPADS];
    Joystick stick = new Joystick();
    JoystickTouchpad touchpad = new JoystickTouchpad();
    JoystickTouchpadFinger finger = new JoystickTouchpadFinger();
    for (int i = 0; i < JOYSTICKS; i++) {
      byte port = (byte) i;
      ds.joysticks(stick, i);
      putBytes(DriverStationJNI.getJoystickName(port), JOYSTICK_NAME_BYTES, stick::mutateName);
      stick.mutateType(DriverStationJNI.getJoystickGamepadType(port));
      stick.mutateSupportedOutputs(DriverStationJNI.getJoystickSupportedOutputs(port));
      stick.mutateIsGamepad(DriverStationJNI.getJoystickIsGamepad(port) != 0);
      DriverStationJNI.getAllJoystickData(i, axes, rawAxes, povs, fingerXy, metadata);
      for (int a = 0; a < AXES; a++) {
        stick.mutateAxisValues(a, axes[a]);
        stick.mutateAxisRaw(a, rawAxes[a]);
      }
      for (int p = 0; p < POVS; p++) {
        stick.mutatePovValues(p, povs[p]);
      }
      stick.mutateAxesAvailable((int) metadata[0]);
      stick.mutatePovsAvailable((int) metadata[1]);
      stick.mutateButtonsAvailable(metadata[2]);
      stick.mutateButtons(metadata[3]);
      stick.mutateTouchpadCount((int) metadata[4]);
      for (int t = 0; t < TOUCHPADS; t++) {
        stick.touchpads(touchpad, t);
        long state = metadata[5 + t];
        touchpad.mutateFingerCount((int) (state >> 2) & 0xFF);
        for (int f = 0; f < FINGERS; f++) {
          touchpad.fingers(finger, f);
          finger.mutateDown((int) (state >> f) & 1);
          finger.mutateX(fingerXy[(t * FINGERS + f) * 2]);
          finger.mutateY(fingerXy[(t * FINGERS + f) * 2 + 1]);
        }
      }
    }

    MatchInfoData match = new MatchInfoData();
    DriverStationJNI.getMatchInfo(match);
    ds.mutateControlWord(DriverStationJNI.nativeGetControlWord());
    ds.mutateAllianceStation(DriverStationJNI.getAllianceStation().ordinal());
    ds.mutateMatchNumber(match.matchNumber);
    ds.mutateReplayNumber(match.replayNumber);
    ds.mutateMatchType(match.matchType);
    ds.mutateMatchTime(DriverStationJNI.getMatchTime());
    putBytes(DriverStationJNI.getGameData(""), TEXT_BYTES - 1, ds::mutateGameData);
    putBytes(match.eventName, TEXT_BYTES - 1, ds::mutateEventName);
  }

  private static void readPdp(PDPData pdp) {
    if (pdHandle == 0) {
      return;
    }
    try {
      double[] currents = PowerDistributionJNI.getAllCurrents(pdHandle);
      for (int i = 0; i < CHANNELS; i++) {
        pdp.mutateChannelCurrent(i, i < currents.length ? currents[i] : 0);
      }
      pdp.mutateTemperature(PowerDistributionJNI.getTemperature(pdHandle));
      pdp.mutateVoltage(PowerDistributionJNI.getVoltage(pdHandle));
      pdp.mutateTotalCurrent(PowerDistributionJNI.getTotalCurrent(pdHandle));
      pdp.mutateTotalPower(PowerDistributionJNI.getTotalPower(pdHandle));
      pdp.mutateTotalEnergy(PowerDistributionJNI.getTotalEnergy(pdHandle));
      pdp.mutateFaults(PowerDistributionJNI.getFaultsNative(pdHandle) & 0xFFFFFFFFL);
      pdp.mutateStickyFaults(PowerDistributionJNI.getStickyFaultsNative(pdHandle) & 0xFFFFFFFFL);
    } catch (RuntimeException e) {
      // As configurePowerDistribution.
    }
  }

  private static void readSystem(SystemData sys) {
    sys.mutateBatteryVoltage(PowerJNI.getVinVoltage());
    sys.mutateWatchdogActive(HAL.getSystemActive());
    sys.mutateTeamNumber(Integer.toUnsignedLong(HALUtil.getTeamNumber()));
    sys.mutateEpochTime(System.currentTimeMillis() * 1000);
    sys.mutateEpochTimeValid(HAL.getSystemTimeValid());
    sys.mutateFaultBrownout(HAL.getBrownedOut());
    sys.mutateFaultRsl(HAL.getRSLState());
    sys.mutateCpuTemp(PowerJNI.getCPUTemp());
    sys.mutateCurrent3v3(PowerJNI.getUserCurrent3V3());
  }

  private interface ByteSetter {
    void set(int index, int value);
  }

  /** Writes a string zero-padded to {@code size} bytes. */
  private static void putBytes(String s, int size, ByteSetter setter) {
    byte[] bytes = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < size; i++) {
      setter.set(i, i < bytes.length ? bytes[i] : 0);
    }
  }
}
