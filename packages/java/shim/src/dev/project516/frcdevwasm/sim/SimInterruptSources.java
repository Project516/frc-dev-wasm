package dev.project516.frcdevwasm.sim;

import dev.project516.frcdevwasm.shim.hal.AnalogJNIImpl;

/** Interrupt sources backed by the DIO and analog simulation data. */
final class SimInterruptSources implements InterruptSources.Provider {
  @Override
  public BoolValue digitalInput(int channel) {
    return DioData.get(channel).value;
  }

  @Override
  public int analogTriggerInputIndex(int triggerHandle) {
    return AnalogJNIImpl.analogTriggerInputChannel(triggerHandle);
  }

  @Override
  public int analogTriggerOutput(int triggerHandle, int triggerType) {
    try {
      return AnalogJNIImpl.getAnalogTriggerOutput(triggerHandle, triggerType) ? 1 : 0;
    } catch (RuntimeException e) {
      return -1;
    }
  }

  @Override
  public DoubleValue analogInputVoltage(int channel) {
    return AnalogInData.get(channel).voltage;
  }
}
