package dev.project516.frcdevwasm.sim;

/** Resource counts of the simulated robot controller, from hal/src/main/native/sim/PortsInternal.hpp. */
public final class Ports {
  public static final int CAN_BUSES = 5;
  public static final int SMART_IO = 6;
  public static final int ANALOG_INPUTS = 8;
  public static final int COUNTERS = 8;
  public static final int DIGITAL_HEADERS = 10;
  public static final int PWM_HEADERS = 10;
  public static final int DIGITAL_CHANNELS = 31;
  public static final int PWM_CHANNELS = 20;
  public static final int DIGITAL_PWM_OUTPUTS = 6;
  public static final int ENCODERS = 8;
  public static final int I2C_PORTS = 2;
  public static final int CTRE_PCM_MODULES = 63;
  public static final int CTRE_SOLENOID_CHANNELS = 8;
  public static final int CTRE_PDP_MODULES = 63;
  public static final int CTRE_PDP_CHANNELS = 16;
  public static final int REV_PDH_MODULES = 63;
  public static final int REV_PDH_CHANNELS = 24;
  public static final int PD_SIM_MODULES = REV_PDH_MODULES;
  public static final int PD_SIM_CHANNELS = REV_PDH_CHANNELS;
  public static final int DUTY_CYCLES = 6;
  public static final int ADDRESSABLE_LEDS = 6;
  public static final int REV_PH_MODULES = 63;
  public static final int REV_PH_CHANNELS = 16;

  private Ports() {}
}
