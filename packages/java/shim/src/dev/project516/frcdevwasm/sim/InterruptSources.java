package dev.project516.frcdevwasm.sim;

/**
 * What interrupts read from the DIO and analog simulations. Set {@link #current} once those data
 * stores exist; without it a wait just runs out its timeout.
 */
public final class InterruptSources {
  public interface Provider {
    /** The DIO input value of a channel (SimDIOData[channel].value). */
    BoolValue digitalInput(int channel);

    /** hal::GetAnalogTriggerInputIndex: the analog input channel of a trigger, or -1 if invalid. */
    int analogTriggerInputIndex(int triggerHandle);

    /**
     * HAL_GetAnalogTriggerOutput: 1 or 0 for the output state, or -1 when the call reports a
     * non-zero status (invalid handle, or the pulse output type).
     */
    int analogTriggerOutput(int triggerHandle, int triggerType);

    /** The voltage value of an analog input channel (SimAnalogInData[channel].voltage). */
    DoubleValue analogInputVoltage(int channel);
  }

  public static volatile Provider current;

  private InterruptSources() {}
}
