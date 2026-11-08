package dev.project516.frcdevwasm.shim.hal.simulation;

import dev.project516.frcdevwasm.sim.RoboRioData;
import dev.project516.frcdevwasm.sim.Sim;
import edu.wpi.first.hal.simulation.NotifyCallback;

/** HALSIM RoboRIO data access (RoboRioDataJNI.cpp and mockdata/RoboRioData.cpp). */
public final class RoboRioDataJNIImpl {
  private RoboRioDataJNIImpl() {}

  private static RoboRioData d() {
    Sim.ensureInitialized();
    return RoboRioData.get();
  }

  public static int registerFPGAButtonCallback(NotifyCallback callback, boolean initialNotify) {
    return d().fpgaButton.register(callback, initialNotify);
  }

  public static void cancelFPGAButtonCallback(int uid) {
    d().fpgaButton.cancel(uid);
  }

  public static boolean getFPGAButton() {
    return d().fpgaButton.get();
  }

  public static void setFPGAButton(boolean value) {
    d().fpgaButton.set(value);
  }

  public static int registerVInVoltageCallback(NotifyCallback callback, boolean initialNotify) {
    return d().vInVoltage.register(callback, initialNotify);
  }

  public static void cancelVInVoltageCallback(int uid) {
    d().vInVoltage.cancel(uid);
  }

  public static double getVInVoltage() {
    return d().vInVoltage.get();
  }

  public static void setVInVoltage(double value) {
    d().vInVoltage.set(value);
  }

  public static int registerVInCurrentCallback(NotifyCallback callback, boolean initialNotify) {
    return d().vInCurrent.register(callback, initialNotify);
  }

  public static void cancelVInCurrentCallback(int uid) {
    d().vInCurrent.cancel(uid);
  }

  public static double getVInCurrent() {
    return d().vInCurrent.get();
  }

  public static void setVInCurrent(double value) {
    d().vInCurrent.set(value);
  }

  public static int registerUserVoltage6VCallback(NotifyCallback callback, boolean initialNotify) {
    return d().userVoltage6V.register(callback, initialNotify);
  }

  public static void cancelUserVoltage6VCallback(int uid) {
    d().userVoltage6V.cancel(uid);
  }

  public static double getUserVoltage6V() {
    return d().userVoltage6V.get();
  }

  public static void setUserVoltage6V(double value) {
    d().userVoltage6V.set(value);
  }

  public static int registerUserCurrent6VCallback(NotifyCallback callback, boolean initialNotify) {
    return d().userCurrent6V.register(callback, initialNotify);
  }

  public static void cancelUserCurrent6VCallback(int uid) {
    d().userCurrent6V.cancel(uid);
  }

  public static double getUserCurrent6V() {
    return d().userCurrent6V.get();
  }

  public static void setUserCurrent6V(double value) {
    d().userCurrent6V.set(value);
  }

  public static int registerUserActive6VCallback(NotifyCallback callback, boolean initialNotify) {
    return d().userActive6V.register(callback, initialNotify);
  }

  public static void cancelUserActive6VCallback(int uid) {
    d().userActive6V.cancel(uid);
  }

  public static boolean getUserActive6V() {
    return d().userActive6V.get();
  }

  public static void setUserActive6V(boolean value) {
    d().userActive6V.set(value);
  }

  public static int registerUserFaults6VCallback(NotifyCallback callback, boolean initialNotify) {
    return d().userFaults6V.register(callback, initialNotify);
  }

  public static void cancelUserFaults6VCallback(int uid) {
    d().userFaults6V.cancel(uid);
  }

  public static int getUserFaults6V() {
    return d().userFaults6V.get();
  }

  public static void setUserFaults6V(int value) {
    d().userFaults6V.set(value);
  }

  public static int registerUserVoltage5VCallback(NotifyCallback callback, boolean initialNotify) {
    return d().userVoltage5V.register(callback, initialNotify);
  }

  public static void cancelUserVoltage5VCallback(int uid) {
    d().userVoltage5V.cancel(uid);
  }

  public static double getUserVoltage5V() {
    return d().userVoltage5V.get();
  }

  public static void setUserVoltage5V(double value) {
    d().userVoltage5V.set(value);
  }

  public static int registerUserCurrent5VCallback(NotifyCallback callback, boolean initialNotify) {
    return d().userCurrent5V.register(callback, initialNotify);
  }

  public static void cancelUserCurrent5VCallback(int uid) {
    d().userCurrent5V.cancel(uid);
  }

  public static double getUserCurrent5V() {
    return d().userCurrent5V.get();
  }

  public static void setUserCurrent5V(double value) {
    d().userCurrent5V.set(value);
  }

  public static int registerUserActive5VCallback(NotifyCallback callback, boolean initialNotify) {
    return d().userActive5V.register(callback, initialNotify);
  }

  public static void cancelUserActive5VCallback(int uid) {
    d().userActive5V.cancel(uid);
  }

  public static boolean getUserActive5V() {
    return d().userActive5V.get();
  }

  public static void setUserActive5V(boolean value) {
    d().userActive5V.set(value);
  }

  public static int registerUserFaults5VCallback(NotifyCallback callback, boolean initialNotify) {
    return d().userFaults5V.register(callback, initialNotify);
  }

  public static void cancelUserFaults5VCallback(int uid) {
    d().userFaults5V.cancel(uid);
  }

  public static int getUserFaults5V() {
    return d().userFaults5V.get();
  }

  public static void setUserFaults5V(int value) {
    d().userFaults5V.set(value);
  }

  public static int registerUserVoltage3V3Callback(NotifyCallback callback, boolean initialNotify) {
    return d().userVoltage3V3.register(callback, initialNotify);
  }

  public static void cancelUserVoltage3V3Callback(int uid) {
    d().userVoltage3V3.cancel(uid);
  }

  public static double getUserVoltage3V3() {
    return d().userVoltage3V3.get();
  }

  public static void setUserVoltage3V3(double value) {
    d().userVoltage3V3.set(value);
  }

  public static int registerUserCurrent3V3Callback(NotifyCallback callback, boolean initialNotify) {
    return d().userCurrent3V3.register(callback, initialNotify);
  }

  public static void cancelUserCurrent3V3Callback(int uid) {
    d().userCurrent3V3.cancel(uid);
  }

  public static double getUserCurrent3V3() {
    return d().userCurrent3V3.get();
  }

  public static void setUserCurrent3V3(double value) {
    d().userCurrent3V3.set(value);
  }

  public static int registerUserActive3V3Callback(NotifyCallback callback, boolean initialNotify) {
    return d().userActive3V3.register(callback, initialNotify);
  }

  public static void cancelUserActive3V3Callback(int uid) {
    d().userActive3V3.cancel(uid);
  }

  public static boolean getUserActive3V3() {
    return d().userActive3V3.get();
  }

  public static void setUserActive3V3(boolean value) {
    d().userActive3V3.set(value);
  }

  public static int registerUserFaults3V3Callback(NotifyCallback callback, boolean initialNotify) {
    return d().userFaults3V3.register(callback, initialNotify);
  }

  public static void cancelUserFaults3V3Callback(int uid) {
    d().userFaults3V3.cancel(uid);
  }

  public static int getUserFaults3V3() {
    return d().userFaults3V3.get();
  }

  public static void setUserFaults3V3(int value) {
    d().userFaults3V3.set(value);
  }

  public static int registerBrownoutVoltageCallback(NotifyCallback callback, boolean initialNotify) {
    return d().brownoutVoltage.register(callback, initialNotify);
  }

  public static void cancelBrownoutVoltageCallback(int uid) {
    d().brownoutVoltage.cancel(uid);
  }

  public static double getBrownoutVoltage() {
    return d().brownoutVoltage.get();
  }

  public static void setBrownoutVoltage(double value) {
    d().brownoutVoltage.set(value);
  }

  public static int registerCPUTempCallback(NotifyCallback callback, boolean initialNotify) {
    return d().cpuTemp.register(callback, initialNotify);
  }

  public static void cancelCPUTempCallback(int uid) {
    d().cpuTemp.cancel(uid);
  }

  public static double getCPUTemp() {
    return d().cpuTemp.get();
  }

  public static void setCPUTemp(double value) {
    d().cpuTemp.set(value);
  }

  public static int registerTeamNumberCallback(NotifyCallback callback, boolean initialNotify) {
    return d().teamNumber.register(callback, initialNotify);
  }

  public static void cancelTeamNumberCallback(int uid) {
    d().teamNumber.cancel(uid);
  }

  public static int getTeamNumber() {
    return d().teamNumber.get();
  }

  public static void setTeamNumber(int value) {
    d().teamNumber.set(value);
  }

  public static int registerRadioLEDStateCallback(NotifyCallback callback, boolean initialNotify) {
    return d().radioLedState.register(callback, initialNotify);
  }

  public static void cancelRadioLEDStateCallback(int uid) {
    d().radioLedState.cancel(uid);
  }

  public static int getRadioLEDState() {
    return d().radioLedState.get();
  }

  public static void setRadioLEDState(int value) {
    d().radioLedState.set(value);
  }

  public static String getSerialNumber() {
    return d().getSerialNumber();
  }

  public static void setSerialNumber(String serialNumber) {
    d().setSerialNumber(serialNumber);
  }

  public static String getComments() {
    return d().getComments();
  }

  public static void setComments(String comments) {
    d().setComments(comments);
  }

  public static void resetData() {
    d().reset();
  }
}
