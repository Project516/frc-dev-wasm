package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.AnalogInData;
import dev.project516.frcdevwasm.sim.AnalogOutData;
import dev.project516.frcdevwasm.sim.AnalogTriggerData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;
import edu.wpi.first.hal.AccumulatorResult;

/**
 * Port of sim/AnalogInput.cpp, AnalogOutput.cpp, AnalogAccumulator.cpp, AnalogTrigger.cpp and the
 * AnalogJNI wrappers.
 */
public final class AnalogJNIImpl {
  static final class InputPort {
    int channel;
    boolean isAccumulator;
    String previousAllocation = "";
  }

  private static final class OutputPort {
    int channel;
    String previousAllocation = "";
  }

  private static final class Trigger {
    int analogHandle;
    int index;
    boolean trigState;
  }

  private static final int LSB_WEIGHT = 1220703;
  private static final int OFFSET = 0;
  private static final double DEFAULT_SAMPLE_RATE = 50000.0;
  private static final int[] ACCUMULATOR_CHANNELS = {0, 1};

  private static final Handles.Limited<InputPort> INPUTS =
      new Handles.Limited<>(Handles.ANALOG_INPUT, Ports.ANALOG_INPUTS);
  private static final Handles.Limited<OutputPort> OUTPUTS =
      new Handles.Limited<>(Handles.ANALOG_OUTPUT, Ports.ANALOG_OUTPUTS);
  private static final Handles.Limited<Trigger> TRIGGERS =
      new Handles.Limited<>(Handles.ANALOG_TRIGGER, Ports.ANALOG_TRIGGERS);

  private AnalogJNIImpl() {}

  /** The port of an analog input handle, or null. */
  static InputPort inputPort(int handle) {
    return INPUTS.get(handle);
  }

  private static InputPort input(int handle) {
    InputPort p = INPUTS.get(handle);
    if (p == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return p;
  }

  private static OutputPort output(int handle) {
    OutputPort p = OUTPUTS.get(handle);
    if (p == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return p;
  }

  private static Trigger trigger(int handle) {
    Trigger t = TRIGGERS.get(handle);
    if (t == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return t;
  }

  /** HAL_IsAccumulatorChannel for a known port. */
  static boolean isAccumulator(InputPort p) {
    for (int c : ACCUMULATOR_CHANNELS) {
      if (p.channel == c) {
        return true;
      }
    }
    return false;
  }

  public static int initializeAnalogInputPort(int halPortHandle) {
    Sim.ensureInitialized();
    int channel = Handles.portChannel(halPortHandle);
    if (channel == -1 || channel >= Ports.ANALOG_INPUTS) {
      Status.indexOutOfRange(
          Status.RESOURCE_OUT_OF_RANGE,
          "Invalid Index for Analog Input",
          0,
          Ports.ANALOG_INPUTS,
          channel);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    InputPort port = new InputPort();
    int handle = INPUTS.allocate(channel, port);
    if (handle == Handles.INVALID) {
      InputPort existing = INPUTS.get(Handles.make(channel, Handles.ANALOG_INPUT));
      Status.previouslyAllocated(
          Status.RESOURCE_IS_ALLOCATED,
          "Analog Input",
          channel,
          existing == null ? "" : existing.previousAllocation);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    port.channel = channel;
    port.isAccumulator = isAccumulator(port);
    AnalogInData data = AnalogInData.get(channel);
    data.initialized.set(true);
    data.accumulatorInitialized.set(false);
    data.simDevice = 0;
    port.previousAllocation = Status.allocationLocation();
    return handle;
  }

  public static void freeAnalogInputPort(int id) {
    if (id == Handles.INVALID) {
      return;
    }
    InputPort port = INPUTS.free(id);
    if (port == null) {
      return;
    }
    AnalogInData data = AnalogInData.get(port.channel);
    data.initialized.set(false);
    data.accumulatorInitialized.set(false);
  }

  public static int initializeAnalogOutputPort(int halPortHandle) {
    Sim.ensureInitialized();
    int channel = Handles.portChannel(halPortHandle);
    if (channel == -1 || channel >= Ports.ANALOG_OUTPUTS) {
      Status.indexOutOfRange(
          Status.RESOURCE_OUT_OF_RANGE,
          "Invalid Index for Analog Output",
          0,
          Ports.ANALOG_OUTPUTS,
          channel);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    OutputPort port = new OutputPort();
    int handle = OUTPUTS.allocate(channel, port);
    if (handle == Handles.INVALID) {
      OutputPort existing = OUTPUTS.get(Handles.make(channel, Handles.ANALOG_OUTPUT));
      Status.previouslyAllocated(
          Status.RESOURCE_IS_ALLOCATED,
          "Analog Output",
          channel,
          existing == null ? "" : existing.previousAllocation);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    port.channel = channel;
    AnalogOutData.get(channel).initialized.set(true);
    port.previousAllocation = Status.allocationLocation();
    return handle;
  }

  public static void freeAnalogOutputPort(int id) {
    if (id == Handles.INVALID) {
      return;
    }
    OutputPort port = OUTPUTS.get(id);
    if (port == null) {
      return;
    }
    OUTPUTS.free(id);
    AnalogOutData.get(port.channel).initialized.set(false);
  }

  public static boolean checkAnalogModule(byte value) {
    return value == 1;
  }

  public static boolean checkAnalogInputChannel(int value) {
    return value < Ports.ANALOG_INPUTS && value >= 0;
  }

  public static boolean checkAnalogOutputChannel(int value) {
    return value < Ports.ANALOG_OUTPUTS && value >= 0;
  }

  public static void setAnalogInputSimDevice(int handle, int device) {
    InputPort port = INPUTS.get(handle);
    if (port != null) {
      AnalogInData.get(port.channel).simDevice = device;
    }
  }

  public static void setAnalogOutput(int id, double voltage) {
    OutputPort port = output(id);
    if (port != null) {
      AnalogOutData.get(port.channel).voltage.set(voltage);
    }
  }

  public static double getAnalogOutput(int id) {
    OutputPort port = output(id);
    return port == null ? 0.0 : AnalogOutData.get(port.channel).voltage.get();
  }

  public static void setAnalogSampleRate(double value) {}

  public static double getAnalogSampleRate() {
    return DEFAULT_SAMPLE_RATE;
  }

  public static void setAnalogAverageBits(int id, int value) {
    InputPort port = input(id);
    if (port != null) {
      AnalogInData.get(port.channel).averageBits.set(value);
    }
  }

  public static int getAnalogAverageBits(int id) {
    InputPort port = input(id);
    return port == null ? 0 : AnalogInData.get(port.channel).averageBits.get();
  }

  public static void setAnalogOversampleBits(int id, int value) {
    InputPort port = input(id);
    if (port != null) {
      AnalogInData.get(port.channel).oversampleBits.set(value);
    }
  }

  public static int getAnalogOversampleBits(int id) {
    InputPort port = input(id);
    return port == null ? 0 : AnalogInData.get(port.channel).oversampleBits.get();
  }

  private static int voltsToValue(double voltage) {
    int status = 0;
    if (voltage > 5.0) {
      voltage = 5.0;
      status = Status.VOLTAGE_OUT_OF_RANGE;
    }
    if (voltage < 0.0) {
      voltage = 0.0;
      status = Status.VOLTAGE_OUT_OF_RANGE;
    }
    int value = (int) ((voltage + OFFSET * 1.0e-9) / (LSB_WEIGHT * 1.0e-9));
    Status.check(status);
    return value;
  }

  public static short getAnalogValue(int id) {
    InputPort port = input(id);
    return port == null ? 0 : (short) voltsToValue(AnalogInData.get(port.channel).voltage.get());
  }

  public static int getAnalogAverageValue(int id) {
    return getAnalogValue(id);
  }

  public static int getAnalogVoltsToValue(int id, double voltageValue) {
    return voltsToValue(voltageValue);
  }

  public static double getAnalogValueToVolts(int id, int rawValue) {
    return LSB_WEIGHT * 1.0e-9 * rawValue - OFFSET * 1.0e-9;
  }

  public static double getAnalogVoltage(int id) {
    InputPort port = input(id);
    return port == null ? 0.0 : AnalogInData.get(port.channel).voltage.get();
  }

  public static double getAnalogAverageVoltage(int id) {
    return getAnalogVoltage(id);
  }

  public static int getAnalogLSBWeight(int id) {
    return LSB_WEIGHT;
  }

  public static int getAnalogOffset(int id) {
    return OFFSET;
  }

  public static boolean isAccumulatorChannel(int id) {
    InputPort port = input(id);
    return port != null && isAccumulator(port);
  }

  public static void initAccumulator(int id) {
    InputPort port = input(id);
    if (port == null) {
      return;
    }
    if (!isAccumulator(port)) {
      Status.check(Status.HAL_INVALID_ACCUMULATOR_CHANNEL);
      return;
    }
    AnalogInData.get(port.channel).accumulatorInitialized.set(true);
  }

  public static void resetAccumulator(int id) {
    InputPort port = input(id);
    if (port != null) {
      AnalogInData data = AnalogInData.get(port.channel);
      data.accumulatorCenter.set(0);
      data.accumulatorCount.set(0);
      data.accumulatorValue.set(0);
    }
  }

  public static void setAccumulatorCenter(int id, int center) {
    InputPort port = input(id);
    if (port != null) {
      AnalogInData.get(port.channel).accumulatorCenter.set(center);
    }
  }

  public static void setAccumulatorDeadband(int id, int deadband) {
    InputPort port = input(id);
    if (port != null) {
      AnalogInData.get(port.channel).accumulatorDeadband.set(deadband);
    }
  }

  public static long getAccumulatorValue(int id) {
    InputPort port = input(id);
    return port == null ? 0 : AnalogInData.get(port.channel).accumulatorValue.get();
  }

  public static int getAccumulatorCount(int id) {
    InputPort port = input(id);
    return port == null ? 0 : (int) AnalogInData.get(port.channel).accumulatorCount.get();
  }

  public static void getAccumulatorOutput(int id, AccumulatorResult accumulatorResult) {
    InputPort port = INPUTS.get(id);
    if (port == null) {
      accumulatorResult.set(0, 0);
      Status.check(Status.HAL_HANDLE_ERROR);
      return;
    }
    AnalogInData data = AnalogInData.get(port.channel);
    accumulatorResult.set(data.accumulatorValue.get(), data.accumulatorCount.get());
  }

  public static int initializeAnalogTrigger(int id) {
    Sim.ensureInitialized();
    InputPort analog = input(id);
    if (analog == null) {
      return 0;
    }
    Trigger trigger = new Trigger();
    int handle = TRIGGERS.allocate(trigger);
    if (handle == Handles.INVALID) {
      Status.check(Status.NO_AVAILABLE_RESOURCES);
      return 0;
    }
    trigger.analogHandle = id;
    trigger.index = Handles.index(handle, Handles.ANALOG_TRIGGER);
    AnalogTriggerData data = AnalogTriggerData.get(trigger.index);
    data.initialized.set(true);
    data.inputPort = analog.channel;
    return handle;
  }

  public static int initializeAnalogTriggerDutyCycle(int id) {
    Status.check(Status.HAL_SIM_NOT_SUPPORTED);
    return 0;
  }

  public static void cleanAnalogTrigger(int id) {
    if (id == Handles.INVALID) {
      return;
    }
    Trigger trigger = TRIGGERS.free(id);
    if (trigger != null) {
      AnalogTriggerData.get(trigger.index).initialized.set(false);
    }
  }

  public static void setAnalogTriggerLimitsRaw(int id, int lower, int upper) {
    trigger(id);
    // The C++ simulation returns before storing the limits, so they are never stored.
    if (lower > upper) {
      Status.check(Status.ANALOG_TRIGGER_LIMIT_ORDER_ERROR);
    }
  }

  public static void setAnalogTriggerLimitsDutyCycle(int id, double lower, double upper) {
    Status.check(Status.HAL_SIM_NOT_SUPPORTED);
  }

  public static void setAnalogTriggerLimitsVoltage(int id, double lower, double upper) {
    Trigger trigger = trigger(id);
    if (trigger == null) {
      return;
    }
    AnalogTriggerData data = AnalogTriggerData.get(trigger.index);
    data.triggerUpperBound.set(upper);
    data.triggerLowerBound.set(lower);
    if (lower > upper) {
      Status.check(Status.ANALOG_TRIGGER_LIMIT_ORDER_ERROR);
    }
  }

  public static void setAnalogTriggerAveraged(int id, boolean averaged) {
    setTriggerMode(id, averaged ? AnalogTriggerData.AVERAGED : AnalogTriggerData.UNASSIGNED);
  }

  public static void setAnalogTriggerFiltered(int id, boolean filtered) {
    setTriggerMode(id, filtered ? AnalogTriggerData.FILTERED : AnalogTriggerData.UNASSIGNED);
  }

  private static void setTriggerMode(int id, int mode) {
    Trigger trigger = trigger(id);
    if (trigger == null) {
      return;
    }
    AnalogTriggerData data = AnalogTriggerData.get(trigger.index);
    if (data.triggerMode.get() != AnalogTriggerData.UNASSIGNED) {
      Status.check(Status.INCOMPATIBLE_STATE);
      return;
    }
    data.triggerMode.set(mode);
  }

  public static boolean getAnalogTriggerInWindow(int id) {
    Trigger trigger = trigger(id);
    InputPort analog = trigger == null ? null : INPUTS.get(trigger.analogHandle);
    if (analog == null) {
      return false;
    }
    double voltage = AnalogInData.get(analog.channel).voltage.get();
    AnalogTriggerData data = AnalogTriggerData.get(trigger.index);
    return voltage >= data.triggerLowerBound.get() && voltage <= data.triggerUpperBound.get();
  }

  public static boolean getAnalogTriggerTriggerState(int id) {
    Trigger trigger = trigger(id);
    InputPort analog = trigger == null ? null : INPUTS.get(trigger.analogHandle);
    if (analog == null) {
      return false;
    }
    double voltage = AnalogInData.get(analog.channel).voltage.get();
    AnalogTriggerData data = AnalogTriggerData.get(trigger.index);
    if (voltage < data.triggerLowerBound.get()) {
      trigger.trigState = false;
      return false;
    }
    if (voltage > data.triggerUpperBound.get()) {
      trigger.trigState = true;
      return true;
    }
    return trigger.trigState;
  }

  public static boolean getAnalogTriggerOutput(int id, int type) {
    if (type == 0) {
      return getAnalogTriggerInWindow(id);
    }
    if (type == 1) {
      return getAnalogTriggerTriggerState(id);
    }
    Status.check(Status.ANALOG_TRIGGER_PULSE_OUTPUT_ERROR);
    return false;
  }

  /** Analog input channel feeding a trigger, or -1 if the handle is invalid. */
  public static int analogTriggerInputChannel(int id) {
    Trigger trigger = trigger(id);
    InputPort analog = trigger == null ? null : INPUTS.get(trigger.analogHandle);
    return analog == null ? -1 : analog.channel;
  }

  public static int getAnalogTriggerFPGAIndex(int id) {
    Trigger trigger = trigger(id);
    return trigger == null ? -1 : trigger.index;
  }
}
