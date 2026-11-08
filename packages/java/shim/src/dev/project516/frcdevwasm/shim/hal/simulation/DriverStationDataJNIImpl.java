package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.DriverStationData;
import dev.project516.frcdevwasm.sim.DsErrors;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.NotifyCallback;

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

  public static int registerAutonomousCallback(NotifyCallback callback, boolean initialNotify) {
    return d().autonomous.register(callback, initialNotify);
  }

  public static void cancelAutonomousCallback(int uid) {
    d().autonomous.cancel(uid);
  }

  public static boolean getAutonomous() {
    return d().autonomous.get();
  }

  public static void setAutonomous(boolean value) {
    d().autonomous.set(value);
  }

  public static int registerTestCallback(NotifyCallback callback, boolean initialNotify) {
    return d().test.register(callback, initialNotify);
  }

  public static void cancelTestCallback(int uid) {
    d().test.cancel(uid);
  }

  public static boolean getTest() {
    return d().test.get();
  }

  public static void setTest(boolean value) {
    d().test.set(value);
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

  public static int registerAllianceStationIdCallback(
      NotifyCallback callback, boolean initialNotify) {
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

  public static void registerAllCallbacks(NotifyCallback callback, boolean initialNotify) {
    DriverStationData ds = d();
    ds.enabled.register(callback, initialNotify);
    ds.autonomous.register(callback, initialNotify);
    ds.test.register(callback, initialNotify);
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

  public static void setJoystickPOV(int stick, int pov, int value) {
    d().setJoystickPov(stick, pov, value);
  }

  public static void setJoystickButtonsValue(int stick, int buttons) {
    d().setJoystickButtonsValue(stick, buttons);
  }

  public static void setJoystickAxisCount(int stick, int count) {
    d().setJoystickAxisCount(stick, count);
  }

  public static void setJoystickPOVCount(int stick, int count) {
    d().setJoystickPovCount(stick, count);
  }

  public static void setJoystickButtonCount(int stick, int count) {
    d().setJoystickButtonCount(stick, count);
  }

  public static void setJoystickIsXbox(int stick, boolean isXbox) {
    d().setJoystickIsXbox(stick, isXbox);
  }

  public static void setJoystickType(int stick, int type) {
    d().setJoystickType(stick, type);
  }

  public static void setJoystickName(int stick, String name) {
    d().setJoystickName(stick, name);
  }

  public static void setJoystickAxisType(int stick, int axis, int type) {
    d().setJoystickAxisType(stick, axis, type);
  }

  public static void setGameSpecificMessage(String message) {
    d().setGameSpecificMessage(message);
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

  public static void setMatchInfo(
      String eventName, String gameSpecificMessage, int matchNumber, int replayNumber, int matchType) {
    d().setMatchInfo(eventName, gameSpecificMessage, matchNumber, replayNumber, matchType);
  }

  public static void setJoystickAxes(byte joystickNum, float[] axesArray) {
    int count = axesArray == null ? 0 : axesArray.length;
    d().setJoystickAxes(joystickNum, axesArray, count);
  }

  public static void setJoystickPOVs(byte joystickNum, short[] povsArray) {
    int count = povsArray == null ? 0 : povsArray.length;
    d().setJoystickPovs(joystickNum, povsArray, count);
  }

  public static void setJoystickButtons(byte joystickNum, int buttons, int count) {
    d().setJoystickButtons(joystickNum, buttons, count);
  }

  public static long getJoystickOutputs(int stick) {
    return d().stick(stick).outputs;
  }

  public static int getJoystickRumble(int stick, int rumbleNum) {
    DriverStationData.Stick s = d().stick(stick);
    return rumbleNum == 0 ? s.leftRumble : s.rightRumble;
  }
}
