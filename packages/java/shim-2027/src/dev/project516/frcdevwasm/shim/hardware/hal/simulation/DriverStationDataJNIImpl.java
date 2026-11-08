package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.DriverStationData;
import dev.project516.frcdevwasm.sim.DsErrors;
import dev.project516.frcdevwasm.sim.Sim;
import java.util.function.BiConsumer;
import org.wpilib.hardware.hal.OpModeOption;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM driver station data access (DriverStationDataJNI.cpp). */
public final class DriverStationDataJNIImpl {
  private DriverStationDataJNIImpl() {}

  private static DriverStationData d() {
    Sim.ensureInitialized();
    return DriverStationData.get();
  }

  public static int registerEnabledCallback(NotifyCallback callback, boolean initialNotify) {
    return d().enabled.register(callback, initialNotify);
  }

  public static void cancelEnabledCallback(int uid) {
    d().enabled.cancel(uid);
  }

  public static boolean getEnabled() {
    return d().enabled.get();
  }

  public static void setEnabled(boolean value) {
    d().enabled.set(value);
  }

  public static int registerRobotModeCallback(NotifyCallback callback, boolean initialNotify) {
    return d().robotMode.register(callback, initialNotify);
  }

  public static void cancelRobotModeCallback(int uid) {
    d().robotMode.cancel(uid);
  }

  public static int nativeGetRobotMode() {
    return d().robotMode.get();
  }

  public static void nativeSetRobotMode(int value) {
    d().robotMode.set(value);
  }

  public static int registerEStopCallback(NotifyCallback callback, boolean initialNotify) {
    return d().eStop.register(callback, initialNotify);
  }

  public static void cancelEStopCallback(int uid) {
    d().eStop.cancel(uid);
  }

  public static boolean getEStop() {
    return d().eStop.get();
  }

  public static void setEStop(boolean value) {
    d().eStop.set(value);
  }

  public static int registerFmsAttachedCallback(NotifyCallback callback, boolean initialNotify) {
    return d().fmsAttached.register(callback, initialNotify);
  }

  public static void cancelFmsAttachedCallback(int uid) {
    d().fmsAttached.cancel(uid);
  }

  public static boolean getFmsAttached() {
    return d().fmsAttached.get();
  }

  public static void setFmsAttached(boolean value) {
    d().fmsAttached.set(value);
  }

  public static int registerDsAttachedCallback(NotifyCallback callback, boolean initialNotify) {
    return d().dsAttached.register(callback, initialNotify);
  }

  public static void cancelDsAttachedCallback(int uid) {
    d().dsAttached.cancel(uid);
  }

  public static boolean getDsAttached() {
    return d().dsAttached.get();
  }

  public static void setDsAttached(boolean value) {
    d().dsAttached.set(value);
  }

  public static int registerAllianceStationIdCallback(NotifyCallback callback, boolean initialNotify) {
    return d().allianceStationId.register(callback, initialNotify);
  }

  public static void cancelAllianceStationIdCallback(int uid) {
    d().allianceStationId.cancel(uid);
  }

  public static int getAllianceStationId() {
    return d().allianceStationId.get();
  }

  public static void setAllianceStationId(int value) {
    d().allianceStationId.set(value);
  }

  public static int registerMatchTimeCallback(NotifyCallback callback, boolean initialNotify) {
    return d().matchTime.register(callback, initialNotify);
  }

  public static void cancelMatchTimeCallback(int uid) {
    d().matchTime.cancel(uid);
  }

  public static double getMatchTime() {
    return d().matchTime.get();
  }

  public static void setMatchTime(double value) {
    d().matchTime.set(value);
  }

  public static int registerOpModeCallback(NotifyCallback callback, boolean initialNotify) {
    return d().opMode.register(callback, initialNotify);
  }

  public static void cancelOpModeCallback(int uid) {
    d().opMode.cancel(uid);
  }

  public static long getOpMode() {
    return d().opMode.get();
  }

  public static void setOpMode(long value) {
    d().opMode.set(value);
  }

  public static int registerOpModeOptionsCallback(
      BiConsumer<String, OpModeOption[]> callback, boolean initialNotify) {
    return d().registerOpModeOptionsCallback(callback, initialNotify);
  }

  public static void cancelOpModeOptionsCallback(int uid) {
    d().cancelOpModeOptionsCallback(uid);
  }

  public static OpModeOption[] getOpModeOptions() {
    return d().opModeOptions();
  }

  public static void registerAllCallbacks(NotifyCallback callback, boolean initialNotify) {
    DriverStationData ds = d();
    ds.enabled.register(callback, initialNotify);
    ds.robotMode.register(callback, initialNotify);
    ds.eStop.register(callback, initialNotify);
    ds.fmsAttached.register(callback, initialNotify);
    ds.dsAttached.register(callback, initialNotify);
    ds.allianceStationId.register(callback, initialNotify);
    ds.matchTime.register(callback, initialNotify);
  }

  public static void notifyNewData() {
    d().notifyNewData();
  }

  public static void resetData() {
    d().reset();
  }

  public static void setSendError(boolean shouldSend) {
    DsErrors.setSendErrors(shouldSend);
  }

  public static void setSendConsoleLine(boolean shouldSend) {
    DsErrors.setSendConsoleLine(shouldSend);
  }

  public static void setJoystickButton(int stick, int button, boolean state) {
    d().setJoystickButton(stick, button, state);
  }

  public static void setJoystickAxis(int stick, int axis, double value) {
    d().setJoystickAxis(stick, axis, value);
  }

  public static void setJoystickPOV(int stick, int pov, byte value) {
    d().setJoystickPov(stick, pov, value);
  }

  public static void setJoystickButtonsValue(int stick, long buttons) {
    d().setJoystickButtonsValue(stick, buttons);
  }

  public static void setJoystickAxesAvailable(int stick, int available) {
    d().setJoystickAxesAvailable(stick, available);
  }

  public static void setJoystickPOVsAvailable(int stick, int available) {
    d().setJoystickPovsAvailable(stick, available);
  }

  public static void setJoystickButtonsAvailable(int stick, long available) {
    d().setJoystickButtonsAvailable(stick, available);
  }

  public static void setTouchpadCounts(int stick, int touchpadCount, int[] fingerCounts) {
    d().setTouchpadCounts(stick, touchpadCount, fingerCounts);
  }

  public static void setTouchpadFinger(
      int stick, int touchpadIndex, int fingerIndex, boolean down, float x, float y) {
    d().setTouchpadFinger(stick, touchpadIndex, fingerIndex, down, x, y);
  }

  public static void setJoystickIsGamepad(int stick, boolean isGamepad) {
    d().setJoystickIsGamepad(stick, isGamepad);
  }

  public static void setJoystickGamepadType(int stick, int type) {
    d().setJoystickGamepadType(stick, type);
  }

  public static void setJoystickSupportedOutputs(int stick, int supportedOutputs) {
    d().setJoystickSupportedOutputs(stick, supportedOutputs);
  }

  public static void setJoystickName(int stick, String name) {
    d().setJoystickName(stick, name);
  }

  public static void setGameData(String gameData) {
    d().setGameData(gameData);
  }

  public static void setEventName(String name) {
    d().setEventName(name);
  }

  public static void setMatchType(int type) {
    d().setMatchType(type);
  }

  public static void setMatchNumber(int matchNumber) {
    d().setMatchNumber(matchNumber);
  }

  public static void setReplayNumber(int replayNumber) {
    d().setReplayNumber(replayNumber);
  }

  public static void setMatchInfo(String eventName, int matchNumber, int replayNumber, int matchType) {
    d().setMatchInfo(eventName, matchNumber, replayNumber, matchType);
  }

  public static void setJoystickAxes(byte joystickNum, float[] axesArray, short availableAxes) {
    d().setJoystickAxes(joystickNum, axesArray, availableAxes);
  }

  public static void setJoystickPOVs(byte joystickNum, byte[] povsArray, short availablePovs) {
    d().setJoystickPovs(joystickNum, povsArray, availablePovs);
  }

  public static void setJoystickButtons(byte joystickNum, long buttons, long availableButtons) {
    d().setJoystickButtons(joystickNum, buttons, availableButtons);
  }

  public static int getJoystickLeds(int stick) {
    return d().joystickLeds(stick);
  }

  public static int getJoystickRumble(int stick, int rumbleNum) {
    return d().joystickRumble(stick, rumbleNum);
  }
}
