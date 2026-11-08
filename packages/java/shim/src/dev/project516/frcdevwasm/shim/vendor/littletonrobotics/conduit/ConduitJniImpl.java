package dev.project516.frcdevwasm.shim.vendor.littletonrobotics.conduit;

import edu.wpi.first.hal.DriverStationJNI;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.hal.HALUtil;
import edu.wpi.first.hal.MatchInfoData;
import edu.wpi.first.hal.PowerDistributionJNI;
import edu.wpi.first.hal.PowerJNI;
import edu.wpi.first.hal.can.CANJNI;
import edu.wpi.first.hal.can.CANStatus;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.littletonrobotics.conduit.schema.CoreInputs;
import org.littletonrobotics.conduit.schema.DSData;
import org.littletonrobotics.conduit.schema.Joystick;
import org.littletonrobotics.conduit.schema.PDPData;
import org.littletonrobotics.conduit.schema.SystemData;

/**
 * Port of AdvantageKit's conduit natives (wpilibio.cc and the ds, pdp and system readers, as built
 * for simulation): a CoreInputs struct in a shared buffer, filled from the HAL on each capture.
 */
public final class ConduitJniImpl {
  private static final int BUF_SIZE = 100000;
  private static final int JOYSTICKS = 6;
  private static final int AXES = 12;
  private static final int POVS = 12;
  private static final int CHANNELS = 24;

  private static ByteBuffer buffer;
  private static CoreInputs inputs;
  private static int pdHandle;
  private static int cycleCount;

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
    getBuffer().order(ByteOrder.LITTLE_ENDIAN);
    inputs.mutateTimestamp(HALUtil.getFPGATime());
    readDs(inputs.ds());
    readPdp(inputs.pdp());
    readSystem(inputs.sys());
  }

  public static synchronized void configurePowerDistribution(int module, int type) {
    getBuffer();
    PDPData pdp = inputs.pdp();
    try {
      pdHandle = PowerDistributionJNI.initialize(module, type);
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
    short[] povs = new short[POVS];
    ByteBuffer buttonCount = ByteBuffer.allocateDirect(1);
    Joystick stick = new Joystick();
    for (int i = 0; i < JOYSTICKS; i++) {
      byte port = (byte) i;
      ds.joysticks(stick, i);
      putBytes(DriverStationJNI.getJoystickName(port), 256, stick::mutateName);
      stick.mutateType(DriverStationJNI.getJoystickType(port));
      for (int a = 0; a < AXES; a++) {
        stick.mutateAxisTypes(a, DriverStationJNI.getJoystickAxisType(port, (byte) a));
      }
      stick.mutateIsXbox(DriverStationJNI.getJoystickIsXbox(port) != 0);

      int axisCount = DriverStationJNI.getJoystickAxes(port, axes);
      for (int a = 0; a < AXES; a++) {
        stick.mutateAxisValues(a, axes[a]);
      }
      stick.mutateAxisCount((short) axisCount);

      int povCount = DriverStationJNI.getJoystickPOVs(port, povs);
      for (int p = 0; p < POVS; p++) {
        stick.mutatePovValues(p, povs[p]);
      }
      stick.mutatePovCount((short) povCount);

      stick.mutateButtons(DriverStationJNI.getJoystickButtons(port, buttonCount));
      stick.mutateButtonCount(buttonCount.get(0) & 0xFF);
    }

    MatchInfoData match = new MatchInfoData();
    DriverStationJNI.getMatchInfo(match);
    ds.mutateControlWord(DriverStationJNI.nativeGetControlWord());
    ds.mutateAllianceStation(DriverStationJNI.getAllianceStation().ordinal());
    ds.mutateMatchNumber(match.matchNumber);
    ds.mutateReplayNumber(match.replayNumber);
    ds.mutateMatchType(match.matchType);
    ds.mutateMatchTime(DriverStationJNI.getMatchTime());
    byte[] message = utf8(match.gameSpecificMessage);
    ds.mutateGameSpecificMessageSize(Math.min(message.length, 64));
    putBytes(match.gameSpecificMessage, 64, ds::mutateGameSpecificMessage);
    putBytes(match.eventName, 64, ds::mutateEventName);
  }

  private static void readPdp(PDPData pdp) {
    if (pdHandle == 0) {
      return;
    }
    try {
      double[] currents = new double[CHANNELS];
      PowerDistributionJNI.getAllCurrents(pdHandle, currents);
      for (int i = 0; i < CHANNELS; i++) {
        pdp.mutateChannelCurrent(i, currents[i]);
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
    if (cycleCount == 0) {
      sys.mutateFpgaVersion(HALUtil.getFPGAVersion());
      sys.mutateFpgaRevision(HALUtil.getFPGARevision());
      sys.mutateSerialNumberSize(putBytes(HALUtil.getSerialNumber(), 8, sys::mutateSerialNumber));
      sys.mutateCommentsSize(putBytes(HALUtil.getComments(), 64, sys::mutateComments));
      sys.mutateTeamNumber(HALUtil.getTeamNumber());
    }
    sys.mutateFpgaButton(HALUtil.getFPGAButton() ? 1 : 0);
    sys.mutateSystemActive(HAL.getSystemActive() ? 1 : 0);
    sys.mutateBrownedOut(HAL.getBrownedOut() ? 1 : 0);
    sys.mutateCommsDisableCount(HAL.getCommsDisableCount());
    sys.mutateRslState(HAL.getRSLState() ? 1 : 0);
    sys.mutateSystemTimeValid(HAL.getSystemTimeValid() ? 1 : 0);
    sys.mutateVoltageVin(PowerJNI.getVinVoltage());
    sys.mutateCurrentVin(PowerJNI.getVinCurrent());
    sys.mutateUserVoltage3v3(PowerJNI.getUserVoltage3V3());
    sys.mutateUserCurrent3v3(PowerJNI.getUserCurrent3V3());
    sys.mutateUserActive3v3(PowerJNI.getUserActive3V3() ? 1 : 0);
    sys.mutateUserCurrentFaults3v3(PowerJNI.getUserCurrentFaults3V3());
    sys.mutateUserVoltage5v(PowerJNI.getUserVoltage5V());
    sys.mutateUserCurrent5v(PowerJNI.getUserCurrent5V());
    sys.mutateUserActive5v(PowerJNI.getUserActive5V() ? 1 : 0);
    sys.mutateUserCurrentFaults5v(PowerJNI.getUserCurrentFaults5V());
    sys.mutateUserVoltage6v(PowerJNI.getUserVoltage6V());
    sys.mutateUserCurrent6v(PowerJNI.getUserCurrent6V());
    sys.mutateUserActive6v(PowerJNI.getUserActive6V() ? 1 : 0);
    sys.mutateUserCurrentFaults6v(PowerJNI.getUserCurrentFaults6V());
    sys.mutateBrownoutVoltage(PowerJNI.getBrownoutVoltage());
    sys.mutateCpuTemp(PowerJNI.getCPUTemp());
    if (cycleCount % 20 == 0) {
      CANStatus can = new CANStatus();
      CANJNI.getCANStatus(can);
      sys.canStatus().mutatePercentBusUtilization((float) can.percentBusUtilization);
      sys.canStatus().mutateBusOffCount(can.busOffCount);
      sys.canStatus().mutateTxFullCount(can.txFullCount);
      sys.canStatus().mutateReceiveErrorCount(can.receiveErrorCount);
      sys.canStatus().mutateTransmitErrorCount(can.transmitErrorCount);
    }
    sys.mutateEpochTime(System.currentTimeMillis() * 1000);
    cycleCount++;
  }

  private interface ByteSetter {
    void set(int index, int value);
  }

  /** Writes a string zero-padded to {@code size} bytes and returns how many bytes it took. */
  private static int putBytes(String s, int size, ByteSetter setter) {
    byte[] bytes = utf8(s);
    int length = Math.min(bytes.length, size);
    for (int i = 0; i < size; i++) {
      setter.set(i, i < length ? bytes[i] : 0);
    }
    return length;
  }

  private static byte[] utf8(String s) {
    return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
  }
}
