package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.DriverStationData;
import dev.project516.frcdevwasm.sim.DriverStationData.Stick;
import dev.project516.frcdevwasm.sim.DsErrors;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.MatchInfoData;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Port of sim/DriverStation.cpp and the DriverStationJNI wrappers. */
public final class DriverStationJNIImpl {
  private DriverStationJNIImpl() {}

  private static DriverStationData ds() {
    Sim.ensureInitialized();
    return DriverStationData.get();
  }

  private static boolean valid(int stick) {
    return stick >= 0 && stick < DriverStationData.JOYSTICKS;
  }

  public static void observeUserProgramStarting() {
    Sim.setProgramStarted();
  }

  public static void observeUserProgramDisabled() {}

  public static void observeUserProgramAutonomous() {}

  public static void observeUserProgramTeleop() {}

  public static void observeUserProgramTest() {}

  public static int report(int resource, int instanceNumber, int context, String feature) {
    return 0;
  }

  public static int nativeGetControlWord() {
    return ds().controlWord();
  }

  public static int nativeGetAllianceStation() {
    return ds().currentAllianceStation();
  }

  private static void tooSmall(int nativeSize, int javaSize) {
    throw new IllegalArgumentException(
        "Native array size larger then passed in java array size\nNative Size: "
            + nativeSize
            + " Java Size: "
            + javaSize);
  }

  public static int getJoystickAxes(byte joystickNum, float[] axesArray) {
    Stick s = valid(joystickNum) ? ds().currentStick(joystickNum) : new Stick();
    if (s.axesCount > axesArray.length) {
      tooSmall(s.axesCount, axesArray.length);
    }
    System.arraycopy(s.axes, 0, axesArray, 0, s.axesCount);
    return s.axesCount;
  }

  public static int getJoystickAxesRaw(byte joystickNum, int[] rawAxesArray) {
    Stick s = valid(joystickNum) ? ds().currentStick(joystickNum) : new Stick();
    if (s.axesCount > rawAxesArray.length) {
      tooSmall(s.axesCount, rawAxesArray.length);
    }
    for (int i = 0; i < s.axesCount; i++) {
      rawAxesArray[i] = s.raw[i] & 0xff;
    }
    return s.axesCount;
  }

  public static int getJoystickPOVs(byte joystickNum, short[] povsArray) {
    Stick s = valid(joystickNum) ? ds().currentStick(joystickNum) : new Stick();
    if (s.povCount > povsArray.length) {
      tooSmall(s.povCount, povsArray.length);
    }
    System.arraycopy(s.povs, 0, povsArray, 0, s.povCount);
    return s.povCount;
  }

  /** Returns the button bits and stores the button count in the first byte of count. */
  public static int getJoystickButtons(byte joystickNum, ByteBuffer count) {
    Stick s = valid(joystickNum) ? ds().currentStick(joystickNum) : new Stick();
    count.put(0, (byte) s.buttonCount);
    return s.buttons;
  }

  public static void getAllJoystickData(
      float[] axesArray, byte[] rawAxesArray, short[] povsArray, long[] buttonsAndMetadata) {
    DriverStationData d = ds();
    for (int i = 0; i < DriverStationData.JOYSTICKS; i++) {
      Stick s = d.currentStick(i);
      System.arraycopy(s.axes, 0, axesArray, i * DriverStationData.MAX_AXES, DriverStationData.MAX_AXES);
      System.arraycopy(
          s.raw, 0, rawAxesArray, i * DriverStationData.MAX_AXES, DriverStationData.MAX_AXES);
      System.arraycopy(s.povs, 0, povsArray, i * DriverStationData.MAX_POVS, DriverStationData.MAX_POVS);
      buttonsAndMetadata[i * 4] = s.axesCount;
      buttonsAndMetadata[i * 4 + 1] = s.povCount;
      buttonsAndMetadata[i * 4 + 2] = s.buttonCount;
      buttonsAndMetadata[i * 4 + 3] = s.buttons & 0xffffffffL;
    }
  }

  public static int setJoystickOutputs(byte joystickNum, int outputs, int leftRumble, int rightRumble) {
    ds().setJoystickOutputs(joystickNum, outputs, leftRumble, rightRumble);
    return 0;
  }

  public static int getJoystickIsXbox(byte joystickNum) {
    return valid(joystickNum) && ds().currentDescriptor(joystickNum).isXbox ? 1 : 0;
  }

  public static int getJoystickType(byte joystickNum) {
    return valid(joystickNum) ? ds().currentDescriptor(joystickNum).type & 0xff : -1;
  }

  public static String getJoystickName(byte joystickNum) {
    return valid(joystickNum) ? ds().currentDescriptor(joystickNum).name : "";
  }

  public static int getJoystickAxisType(byte joystickNum, byte axis) {
    if (!valid(joystickNum)) {
      return -1;
    }
    return ds().currentDescriptor(joystickNum).axisTypes[axis] & 0xff;
  }

  public static double getMatchTime() {
    return ds().currentMatchTime();
  }

  public static int getMatchInfo(MatchInfoData info) {
    DriverStationData.MatchInfo m = ds().currentMatchInfo();
    info.setData(
        m.eventName,
        new String(m.gameSpecificMessage, StandardCharsets.UTF_8),
        m.matchNumber,
        m.replayNumber,
        m.matchType);
    return 0;
  }

  public static int sendError(
      boolean isError,
      int errorCode,
      boolean isLVCode,
      String details,
      String location,
      String callStack,
      boolean printMsg) {
    return DsErrors.send(isError, errorCode, isLVCode, details, location, callStack, printMsg);
  }

  public static int sendConsoleLine(String line) {
    return DsErrors.sendConsoleLine(line);
  }

  public static boolean refreshDSData() {
    return ds().refresh();
  }

  public static void provideNewDataEventHandle(int handle) {
    ds().provideNewDataEventHandle(handle);
  }

  public static void removeNewDataEventHandle(int handle) {
    ds().removeNewDataEventHandle(handle);
  }

  public static boolean getOutputsActive() {
    return ds().outputsEnabled();
  }
}
