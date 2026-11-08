package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.Ports;

/** Port of hal/src/main/native/sim/Ports.cpp and the PortsJNI wrappers. */
public final class PortsJNIImpl {
  private PortsJNIImpl() {}

  public static int getNumCanBuses() {
    return Ports.CAN_BUSES;
  }

  public static int getNumSmartIo() {
    return Ports.SMART_IO;
  }

  public static int getNumCTREPCMModules() {
    return Ports.CTRE_PCM_MODULES;
  }

  public static int getNumCTRESolenoidChannels() {
    return Ports.CTRE_SOLENOID_CHANNELS;
  }

  public static int getNumCTREPDPModules() {
    return Ports.CTRE_PDP_MODULES;
  }

  public static int getNumCTREPDPChannels() {
    return Ports.CTRE_PDP_CHANNELS;
  }

  public static int getNumREVPDHModules() {
    return Ports.REV_PDH_MODULES;
  }

  public static int getNumREVPDHChannels() {
    return Ports.REV_PDH_CHANNELS;
  }

  public static int getNumREVPHModules() {
    return Ports.REV_PH_MODULES;
  }

  public static int getNumREVPHChannels() {
    return Ports.REV_PH_CHANNELS;
  }
}
