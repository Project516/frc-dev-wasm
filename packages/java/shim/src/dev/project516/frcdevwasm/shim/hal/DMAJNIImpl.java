package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.Handles;
import edu.wpi.first.hal.ShimAccessBus;
import java.util.Arrays;
import java.util.function.IntFunction;

/**
 * Port of sim/DMA.cpp and DMAJNI.cpp. The sim has no DMA: nothing can be initialized, and every
 * read reports an error.
 */
public final class DMAJNIImpl {
  private static final int ENABLE_AI0_LOW = 0;
  private static final int ENABLE_AI0_HIGH = 1;
  private static final int ENABLE_DI = 10;
  private static final int ENABLE_ANALOG_TRIGGERS = 11;
  private static final int ENABLE_COUNTERS_LOW = 12;
  private static final int ENABLE_COUNTERS_HIGH = 13;
  private static final int ENABLE_ENCODERS_LOW = 16;
  private static final int ENABLE_ENCODERS_HIGH = 17;
  private static final int ENABLE_DUTY_CYCLE_LOW = 20;
  private static final int ENABLE_DUTY_CYCLE_HIGH = 21;

  private static final int CHANNEL_OFFSETS = 22;
  private static final int READ_ERROR = 3;

  /**
   * GetEncoderBaseHandle of the encoder group: for an encoder handle, {fpgaEncoderHandle,
   * counterHandle}, otherwise null.
   */
  public static volatile IntFunction<int[]> encoderBase = handle -> null;

  private DMAJNIImpl() {}

  public static int initialize() {
    return Handles.INVALID;
  }

  public static void free(int handle) {}

  public static void setPause(int handle, boolean pause) {}

  public static void setTimedTrigger(int handle, double seconds) {}

  public static void setTimedTriggerCycles(int handle, int cycles) {}

  public static void addEncoder(int handle, int encoderHandle) {}

  public static void addEncoderPeriod(int handle, int encoderHandle) {}

  public static void addCounter(int handle, int counterHandle) {}

  public static void addCounterPeriod(int handle, int counterHandle) {}

  public static void addDutyCycle(int handle, int dutyCycleHandle) {}

  public static void addDigitalSource(int handle, int digitalSourceHandle) {}

  public static void addAnalogInput(int handle, int analogInputHandle) {}

  public static void addAveragedAnalogInput(int handle, int analogInputHandle) {}

  public static void addAnalogAccumulator(int handle, int analogInputHandle) {}

  public static int setExternalTrigger(
      int handle, int digitalSourceHandle, int analogTriggerType, boolean rising, boolean falling) {
    return 0;
  }

  public static void clearSensors(int handle) {}

  public static void clearExternalTriggers(int handle) {}

  public static void startDMA(int handle, int queueDepth) {}

  public static void stopDMA(int handle) {}

  /** The sample is all zero apart from the read status. */
  public static long readDMA(int handle, double timeoutSeconds, int[] buf, int[] store) {
    Arrays.fill(store, 0, CHANNEL_OFFSETS + 3, 0);
    store[25] = READ_ERROR;
    return 0;
  }

  public static Object getSensorReadData(int handle) {
    int[] base = encoderBase.apply(handle);
    if (base != null) {
      int fpga = base[0];
      int counter = base[1];
      if (counter != Handles.INVALID) {
        int index = counter & 0xffff;
        return index < 4
            ? store(ENABLE_COUNTERS_LOW, index)
            : store(ENABLE_COUNTERS_HIGH, index - 4);
      }
      int index = fpga & 0xffff;
      return index < 4
          ? store(ENABLE_ENCODERS_LOW, index)
          : store(ENABLE_ENCODERS_HIGH, index - 4);
    }

    int index = handle & 0xffff;
    return switch (Handles.type(handle)) {
      case Handles.DIO -> store(ENABLE_DI, index);
      case Handles.ANALOG_TRIGGER -> store(ENABLE_ANALOG_TRIGGERS, index);
      case Handles.ANALOG_INPUT ->
          index < 4 ? store(ENABLE_AI0_LOW, index) : store(ENABLE_AI0_HIGH, index - 4);
      case Handles.DUTY_CYCLE ->
          index < 4
              ? store(ENABLE_DUTY_CYCLE_LOW, index)
              : store(ENABLE_DUTY_CYCLE_HIGH, index - 4);
      default -> null;
    };
  }

  private static Object store(int valueType, int index) {
    return ShimAccessBus.dmaBaseStore(valueType, index);
  }
}
