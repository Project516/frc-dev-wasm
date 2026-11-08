package dev.project516.frcdevwasm.shim.hardware.hal.simulation;

import dev.project516.frcdevwasm.sim.RoboRioData;
import dev.project516.frcdevwasm.sim.Sim;
import org.wpilib.hardware.hal.simulation.NotifyCallback;

/** HALSIM data access (RoboRioDataJNI.cpp and mockdata). */
public final class RoboRioDataJNIImpl {
  private RoboRioDataJNIImpl() {}

  private static RoboRioData d() {
    Sim.ensureInitialized();
    return RoboRioData.get();
  }

  public static int registerVInVoltageCallback(
      NotifyCallback callback, boolean initialNotify) {
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

  public static int registerUserVoltage3V3Callback(
      NotifyCallback callback, boolean initialNotify) {
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

  public static int registerUserCurrent3V3Callback(
      NotifyCallback callback, boolean initialNotify) {
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

  public static int registerUserActive3V3Callback(
      NotifyCallback callback, boolean initialNotify) {
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

  public static int registerUserFaults3V3Callback(
      NotifyCallback callback, boolean initialNotify) {
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

  public static int registerBrownoutVoltageCallback(
      NotifyCallback callback, boolean initialNotify) {
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

  public static int registerBrownoutRecoveryVoltageCallback(
      NotifyCallback callback, boolean initialNotify) {
    return d().brownoutRecoveryVoltage.register(callback, initialNotify);
  }

  public static void cancelBrownoutRecoveryVoltageCallback(int uid) {
    d().brownoutRecoveryVoltage.cancel(uid);
  }

  public static double getBrownoutRecoveryVoltage() {
    return d().brownoutRecoveryVoltage.get();
  }

  public static void setBrownoutRecoveryVoltage(double value) {
    d().brownoutRecoveryVoltage.set(value);
  }

  public static int registerCPUTempCallback(
      NotifyCallback callback, boolean initialNotify) {
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

  public static int registerTeamNumberCallback(
      NotifyCallback callback, boolean initialNotify) {
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
  }}
