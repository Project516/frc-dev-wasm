package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.Ports;

/** Port of hal/src/main/native/sim/Ports.cpp and the PortsJNI wrappers. */
public final class PortsJNIImpl {
  private PortsJNIImpl() {}

  public static int getNumAccumulators() {
    return Ports.ACCUMULATORS;
  }

  public static int getNumAnalogInputs() {
    return Ports.ANALOG_INPUTS;
  }

  public static int getNumAnalogOutputs() {
    return Ports.ANALOG_OUTPUTS;
  }

  public static int getNumAnalogTriggers() {
    return Ports.ANALOG_TRIGGERS;
  }

  public static int getNumCTREPCMModules() {
    return Ports.CTRE_PCM_MODULES;
  }

  public static int getNumCTREPDPChannels() {
    return Ports.CTRE_PDP_CHANNELS;
  }

  public static int getNumCTREPDPModules() {
    return Ports.CTRE_PDP_MODULES;
  }

  public static int getNumCTRESolenoidChannels() {
    return Ports.CTRE_SOLENOID_CHANNELS;
  }

  public static int getNumCounters() {
    return Ports.COUNTERS;
  }

  public static int getNumDigitalChannels() {
    return Ports.DIGITAL_CHANNELS;
  }

  public static int getNumDigitalHeaders() {
    return Ports.DIGITAL_HEADERS;
  }

  public static int getNumDigitalPWMOutputs() {
    return Ports.DIGITAL_PWM_OUTPUTS;
  }

  public static int getNumEncoders() {
    return Ports.ENCODERS;
  }

  public static int getNumInterrupts() {
    return Ports.INTERRUPTS;
  }

  public static int getNumPWMChannels() {
    return Ports.PWM_CHANNELS;
  }

  public static int getNumPWMHeaders() {
    return Ports.PWM_HEADERS;
  }

  public static int getNumREVPDHChannels() {
    return Ports.REV_PDH_CHANNELS;
  }

  public static int getNumREVPDHModules() {
    return Ports.REV_PDH_MODULES;
  }

  public static int getNumREVPHChannels() {
    return Ports.REV_PH_CHANNELS;
  }

  public static int getNumREVPHModules() {
    return Ports.REV_PH_MODULES;
  }

  public static int getNumRelayChannels() {
    return Ports.RELAY_CHANNELS;
  }

  public static int getNumRelayHeaders() {
    return Ports.RELAY_HEADERS;
  }
}
