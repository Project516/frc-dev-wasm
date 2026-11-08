package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.RoboRioData;

/** Port of sim/LEDs.cpp and LEDJNI.cpp. The state lives in the RoboRioData radio LED value. */
public final class LEDJNIImpl {

  private LEDJNIImpl() {}

  public static void setRadioLEDState(int state) {
    RoboRioData.get().radioLedState.set(state);
  }

  public static int getRadioLEDState() {
    return RoboRioData.get().radioLedState.get();
  }
}
