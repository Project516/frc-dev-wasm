package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.DriverStationData;
import dev.project516.frcdevwasm.sim.DriverStationData.Stick;
import dev.project516.frcdevwasm.sim.DsErrors;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.MatchInfoData;
import org.wpilib.hardware.hal.OpModeOption;

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
    Sim.setProgramStarted(true);
  }

  public static void observeUserProgram(long word) {
    Sim.setProgramState(word);
  }

  public static long nativeGetControlWord() {
    return ds().controlWord();
  }

  public static long nativeGetUncachedControlWord() {
    return ds().uncachedControlWord();
  }

  public static void setOpModeOptions(OpModeOption[] options) {
    ds().setOpModeOptions(options);
  }

  public static int nativeGetAllianceStation() {
    return ds().currentAllianceStation();
  }

  /**
   * Fills the arrays as HAL_GetAllJoystickData does. Touchpad data is the x and y of each finger and
   * a metadata word per touchpad: finger 0 down 1, finger 1 down 2, finger count at bit 2.
   */
  public static void getAllJoystickData(
      int stick,
      float[] axesArray,
      short[] rawAxesArray,
      byte[] povsArray,
      float[] touchpadFingersArray,
      long[] buttonsAndMetadata) {
    Stick s = valid(stick) ? ds().currentStick(stick) : new Stick();
    System.arraycopy(s.axes, 0, axesArray, 0, DriverStationData.MAX_AXES);
    System.arraycopy(s.raw, 0, rawAxesArray, 0, DriverStationData.MAX_AXES);
    System.arraycopy(s.povs, 0, povsArray, 0, DriverStationData.MAX_POVS);
    System.arraycopy(s.fingerXy, 0, touchpadFingersArray, 0, s.fingerXy.length);
    buttonsAndMetadata[0] = s.axesAvailable;
    buttonsAndMetadata[1] = s.povsAvailable;
    buttonsAndMetadata[2] = s.buttonsAvailable;
    buttonsAndMetadata[3] = s.buttons;
    buttonsAndMetadata[4] = s.touchpadCount;
    for (int i = 0; i < DriverStationData.MAX_TOUCHPADS; i++) {
      buttonsAndMetadata[5 + i] =
          (s.fingerDown[i * 2] ? 1 : 0) | (s.fingerDown[i * 2 + 1] ? 2 : 0) | (s.fingerCount[i] << 2);
    }
  }

  public static int setJoystickRumble(
      byte joystickNum, int leftRumble, int rightRumble, int leftTriggerRumble, int rightTriggerRumble) {
    ds().setJoystickRumbles(joystickNum, leftRumble, rightRumble, leftTriggerRumble, rightTriggerRumble);
    return 0;
  }

  public static int setJoystickLeds(byte joystickNum, int leds) {
    ds().setJoystickLeds(joystickNum, leds);
    return 0;
  }

  public static int getJoystickIsGamepad(byte joystickNum) {
    return valid(joystickNum) && ds().currentDescriptor(joystickNum).isGamepad ? 1 : 0;
  }

  public static int getJoystickGamepadType(byte joystickNum) {
    return valid(joystickNum) ? ds().currentDescriptor(joystickNum).gamepadType : -1;
  }

  public static int getJoystickSupportedOutputs(byte joystickNum) {
    return valid(joystickNum) ? ds().currentDescriptor(joystickNum).supportedOutputs : -1;
  }

  public static String getJoystickName(byte joystickNum) {
    return valid(joystickNum) ? ds().currentDescriptor(joystickNum).name : "";
  }

  public static double getMatchTime() {
    return ds().currentMatchTime();
  }

  public static int getMatchInfo(MatchInfoData info) {
    DriverStationData.MatchInfo m = ds().currentMatchInfo();
    info.setData(m.eventName, m.matchNumber, m.replayNumber, m.matchType);
    return 0;
  }

  /** Returns the existing string when the game data is unchanged, so callers can skip work. */
  public static String getGameData(String existing) {
    String data = ds().currentGameData();
    return data.equals(existing) ? existing : data;
  }

  public static int sendError(
      boolean isError,
      int errorCode,
      String details,
      String location,
      String callStack,
      boolean printMsg) {
    return DsErrors.send(isError, errorCode, details, location, callStack, printMsg);
  }

  public static int sendConsoleLine(String line) {
    return DsErrors.sendConsoleLine(line);
  }

  public static int sendProgramCrash(String details, String location, String callStack) {
    return DsErrors.sendProgramCrash(details, location, callStack);
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

  /** The sim has no display, as upstream without a registered handler. */
  public static void writeDisplayAnsi(String data) {}
}
