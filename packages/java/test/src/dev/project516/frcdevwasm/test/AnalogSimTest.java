package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import dev.project516.frcdevwasm.sim.AnalogTriggerData;
import dev.project516.frcdevwasm.sim.DigitalPorts;
import dev.project516.frcdevwasm.sim.DsErrors;
import dev.project516.frcdevwasm.sim.Handles;
import edu.wpi.first.hal.AccumulatorResult;
import edu.wpi.first.hal.AnalogGyroJNI;
import edu.wpi.first.hal.AnalogJNI;
import edu.wpi.first.hal.DutyCycleJNI;
import edu.wpi.first.hal.EncoderJNI;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.hal.util.AllocationException;
import edu.wpi.first.hal.util.HalHandleException;
import edu.wpi.first.hal.util.UncleanStatusException;
import edu.wpi.first.wpilibj.AnalogGyro;
import edu.wpi.first.wpilibj.AnalogInput;
import edu.wpi.first.wpilibj.AnalogOutput;
import edu.wpi.first.wpilibj.AnalogTrigger;
import edu.wpi.first.wpilibj.AnalogTriggerOutput;
import edu.wpi.first.wpilibj.AnalogTriggerOutput.AnalogTriggerType;
import edu.wpi.first.wpilibj.BuiltInAccelerometer;
import edu.wpi.first.wpilibj.CounterBase.EncodingType;
import edu.wpi.first.wpilibj.DutyCycle;
import edu.wpi.first.wpilibj.Encoder;
import edu.wpi.first.wpilibj.simulation.AnalogGyroSim;
import edu.wpi.first.wpilibj.simulation.AnalogInputSim;
import edu.wpi.first.wpilibj.simulation.AnalogOutputSim;
import edu.wpi.first.wpilibj.simulation.AnalogTriggerSim;
import edu.wpi.first.wpilibj.simulation.BuiltInAccelerometerSim;
import edu.wpi.first.wpilibj.simulation.DutyCycleSim;
import edu.wpi.first.wpilibj.simulation.EncoderSim;
import edu.wpi.first.wpilibj.simulation.SPIAccelerometerSim;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Analog inputs and outputs, triggers, gyro, accelerometers, encoders and duty cycle inputs. */
public final class AnalogSimTest {
  private AnalogSimTest() {}

  private static final int K4X = 2;

  public static void run(Tests t) {
    HAL.initialize(500, 0);
    analogInput(t);
    accumulator(t);
    analogOutput(t);
    analogTrigger(t);
    analogGyro(t);
    accelerometer(t);
    encoder(t);
    dutyCycle(t);
    handles(t);
    ws(t);
  }

  private static void analogInput(Tests t) {
    t.test("analog input voltage, value and init flag", () -> {
      AnalogInputSim sim = new AnalogInputSim(0);
      sim.resetData();
      try (AnalogInput ai = new AnalogInput(0)) {
        Tests.check(sim.getInitialized(), "initialized");
        Tests.eq(0.0, ai.getVoltage(), "default voltage");
        sim.setVoltage(2.5);
        Tests.eq(2.5, ai.getVoltage(), "voltage");
        Tests.eq(2.5, ai.getAverageVoltage(), "average voltage");
        Tests.eq(2048, ai.getValue(), "raw value");
        Tests.eq(2048, ai.getAverageValue(), "average value");
        Tests.eq(1220703L, ai.getLSBWeight(), "lsb weight");
        Tests.eq(0, ai.getOffset(), "offset");
        sim.setVoltage(5.0);
        Tests.eq(4096, ai.getValue(), "full scale");
        Tests.eq(0, ai.getChannel(), "channel");
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("analog input out of range voltage clamps the raw value", () -> {
      AnalogInputSim sim = new AnalogInputSim(1);
      sim.resetData();
      DsErrors.setSendErrors(false);
      try (AnalogInput ai = new AnalogInput(1)) {
        sim.setVoltage(7.0);
        Tests.eq(4096, ai.getValue(), "clamped high");
        sim.setVoltage(-2.0);
        Tests.eq(0, ai.getValue(), "clamped low");
        Tests.eq(-2.0, ai.getVoltage(), "voltage is not clamped");
      } finally {
        DsErrors.setSendErrors(true);
      }
    });

    t.test("analog input volts and raw value conversions", () -> {
      try (AnalogInput ai = new AnalogInput(2)) {
        int handle = analogHandle(2);
        Tests.eq(819, AnalogJNI.getAnalogVoltsToValue(handle, 1.0), "volts to value");
        Tests.near(1220703e-9 * 1000, AnalogJNI.getAnalogValueToVolts(handle, 1000), 1e-12, "value to volts");
        Tests.eq(1220703, AnalogJNI.getAnalogLSBWeight(handle), "lsb weight");
        Tests.eq(0, AnalogJNI.getAnalogOffset(handle), "offset");
        Tests.eq(50000.0, AnalogJNI.getAnalogSampleRate(), "sample rate");
        AnalogJNI.setAnalogSampleRate(1000);
        Tests.eq(50000.0, AnalogInput.getGlobalSampleRate(), "sample rate is fixed");
      }
    });

    t.test("analog input average and oversample bits", () -> {
      AnalogInputSim sim = new AnalogInputSim(3);
      sim.resetData();
      try (AnalogInput ai = new AnalogInput(3)) {
        Tests.eq(7, ai.getAverageBits(), "default average bits");
        Tests.eq(0, ai.getOversampleBits(), "default oversample bits");
        ai.setAverageBits(4);
        ai.setOversampleBits(2);
        Tests.eq(4, sim.getAverageBits(), "sim average bits");
        Tests.eq(2, sim.getOversampleBits(), "sim oversample bits");
        sim.setAverageBits(9);
        sim.setOversampleBits(5);
        Tests.eq(9, ai.getAverageBits(), "average bits from sim");
        Tests.eq(5, ai.getOversampleBits(), "oversample bits from sim");
      }
    });

    t.test("analog input callbacks fire on change only", () -> {
      AnalogInputSim sim = new AnalogInputSim(4);
      sim.resetData();
      AtomicInteger voltage = new AtomicInteger();
      AtomicInteger init = new AtomicInteger();
      var vcb = sim.registerVoltageCallback((name, value) -> voltage.incrementAndGet(), false);
      var icb = sim.registerInitializedCallback((name, value) -> init.incrementAndGet(), true);
      try {
        Tests.eq(1, init.get(), "initial notify");
        try (AnalogInput ai = new AnalogInput(4)) {
          Tests.eq(2, init.get(), "init true");
          sim.setVoltage(1.0);
          sim.setVoltage(1.0);
          sim.setVoltage(2.0);
          Tests.eq(2, voltage.get(), "voltage callbacks");
        }
        Tests.eq(3, init.get(), "init false");
      } finally {
        vcb.close();
        icb.close();
      }
      sim.setVoltage(3.0);
      Tests.eq(2, voltage.get(), "no callback after cancel");
    });

    t.test("analog input resetData drops callbacks and restores defaults", () -> {
      AnalogInputSim sim = new AnalogInputSim(5);
      AtomicInteger calls = new AtomicInteger();
      sim.registerVoltageCallback((name, value) -> calls.incrementAndGet(), false);
      sim.setVoltage(4.0);
      sim.setAverageBits(1);
      sim.resetData();
      Tests.eq(1, calls.get(), "callbacks before reset");
      Tests.eq(0.0, sim.getVoltage(), "voltage reset");
      Tests.eq(7, sim.getAverageBits(), "average bits reset");
      sim.setVoltage(1.0);
      Tests.eq(1, calls.get(), "callbacks dropped");
    });
  }

  private static void accumulator(Tests t) {
    t.test("analog input accumulator", () -> {
      AnalogInputSim sim = new AnalogInputSim(0);
      sim.resetData();
      try (AnalogInput ai = new AnalogInput(0)) {
        Tests.check(ai.isAccumulatorChannel(), "channel 0 is an accumulator channel");
        Tests.check(!sim.getAccumulatorInitialized(), "not initialized");
        ai.initAccumulator();
        Tests.check(sim.getAccumulatorInitialized(), "initialized");
        sim.setAccumulatorValue(1000);
        sim.setAccumulatorCount(10);
        Tests.eq(1000L, ai.getAccumulatorValue(), "value");
        Tests.eq(10L, ai.getAccumulatorCount(), "count");
        AccumulatorResult r = new AccumulatorResult();
        ai.getAccumulatorOutput(r);
        Tests.eq(1000L, r.value, "output value");
        Tests.eq(10L, r.count, "output count");
        ai.setAccumulatorCenter(2048);
        ai.setAccumulatorDeadband(16);
        Tests.eq(2048, sim.getAccumulatorCenter(), "center");
        Tests.eq(16, sim.getAccumulatorDeadband(), "deadband");
        ai.resetAccumulator();
        Tests.eq(0L, ai.getAccumulatorValue(), "value after reset");
        Tests.eq(0L, ai.getAccumulatorCount(), "count after reset");
        Tests.eq(0, sim.getAccumulatorCenter(), "center after reset");
        Tests.eq(16, sim.getAccumulatorDeadband(), "deadband kept");
      }
      Tests.check(!sim.getAccumulatorInitialized(), "cleared on free");
    });

    t.test("accumulator on a channel without one is an error", () -> {
      try (AnalogInput ai = new AnalogInput(3)) {
        Tests.check(!ai.isAccumulatorChannel(), "channel 3");
        DsErrors.setSendErrors(false);
        try {
          AnalogJNI.initAccumulator(analogHandle(3));
          throw new AssertionError("expected UncleanStatusException");
        } catch (UncleanStatusException expected) {
          Tests.check(expected.getMessage().contains("-1035"), expected.getMessage());
        } finally {
          DsErrors.setSendErrors(true);
        }
      }
    });
  }

  /** Handle of the analog input on the channel; the channel must be allocated. */
  private static int analogHandle(int channel) {
    return Handles.make(channel, Handles.ANALOG_INPUT);
  }

  private static void analogOutput(Tests t) {
    t.test("analog output voltage reaches the sim store", () -> {
      AnalogOutputSim sim = new AnalogOutputSim(0);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      try (AnalogOutput ao = new AnalogOutput(0)) {
        var cb = sim.registerVoltageCallback((name, value) -> calls.incrementAndGet(), false);
        Tests.check(sim.getInitialized(), "initialized");
        ao.setVoltage(3.3);
        ao.setVoltage(3.3);
        Tests.eq(3.3, sim.getVoltage(), "sim voltage");
        Tests.eq(3.3, ao.getVoltage(), "voltage");
        Tests.eq(1, calls.get(), "callback on change only");
        cb.close();
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("analog output allocated twice throws AllocationException", () -> {
      try (AnalogOutput a = new AnalogOutput(1)) {
        try {
          new AnalogOutput(1).close();
          throw new AssertionError("expected AllocationException");
        } catch (AllocationException expected) {
          Tests.check(expected.getMessage().contains("previously allocated"), expected.getMessage());
          Tests.check(expected.getMessage().contains("Analog Output"), expected.getMessage());
        }
      }
    });
  }

  private static void analogTrigger(Tests t) {
    t.test("analog trigger window and hysteresis", () -> {
      AnalogInputSim input = new AnalogInputSim(2);
      input.resetData();
      try (AnalogInput ai = new AnalogInput(2);
          AnalogTrigger trigger = new AnalogTrigger(ai)) {
        AnalogTriggerSim sim = new AnalogTriggerSim(trigger);
        Tests.check(sim.getInitialized(), "initialized");
        Tests.eq(0, trigger.getIndex(), "index");
        trigger.setLimitsVoltage(1.0, 3.0);
        Tests.eq(1.0, sim.getTriggerLowerBound(), "lower");
        Tests.eq(3.0, sim.getTriggerUpperBound(), "upper");

        input.setVoltage(0.5);
        Tests.check(!trigger.getInWindow(), "below: window");
        Tests.check(!trigger.getTriggerState(), "below: state");
        input.setVoltage(2.0);
        Tests.check(trigger.getInWindow(), "inside: window");
        Tests.check(!trigger.getTriggerState(), "inside: state stays false");
        input.setVoltage(3.5);
        Tests.check(!trigger.getInWindow(), "above: window");
        Tests.check(trigger.getTriggerState(), "above: state");
        input.setVoltage(2.0);
        Tests.check(trigger.getTriggerState(), "inside: state stays true");
        input.setVoltage(0.5);
        Tests.check(!trigger.getTriggerState(), "below again");

        AnalogTriggerOutput state = trigger.createOutput(AnalogTriggerType.kState);
        AnalogTriggerOutput window = trigger.createOutput(AnalogTriggerType.kInWindow);
        input.setVoltage(2.0);
        Tests.check(window.get(), "window output");
        Tests.check(!state.get(), "state output");
        input.setVoltage(4.0);
        Tests.check(state.get(), "state output high");
      }
    });

    t.test("analog trigger sim lookup by channel and by index", () -> {
      try (AnalogInput ai = new AnalogInput(6);
          AnalogTrigger trigger = new AnalogTrigger(ai)) {
        AnalogTriggerSim byChannel = AnalogTriggerSim.createForChannel(6);
        byChannel.setTriggerLowerBound(1.25);
        Tests.eq(1.25, AnalogTriggerSim.createForIndex(trigger.getIndex()).getTriggerLowerBound(), "bound");
        try {
          AnalogTriggerSim.createForChannel(7);
          throw new AssertionError("expected NoSuchElementException");
        } catch (NoSuchElementException expected) {
          // no trigger on channel 7
        }
      }
    });

    t.test("analog trigger limit and mode errors", () -> {
      try (AnalogInput ai = new AnalogInput(2);
          AnalogTrigger trigger = new AnalogTrigger(ai)) {
        AnalogTriggerSim sim = new AnalogTriggerSim(trigger);
        int handle = Handles.make(trigger.getIndex(), Handles.ANALOG_TRIGGER);
        DsErrors.setSendErrors(false);
        try {
          try {
            AnalogJNI.setAnalogTriggerLimitsVoltage(handle, 3.0, 1.0);
            throw new AssertionError("expected UncleanStatusException");
          } catch (UncleanStatusException expected) {
            Tests.check(expected.getMessage().contains("-1010"), expected.getMessage());
          }
          Tests.eq(3.0, sim.getTriggerLowerBound(), "limits are still stored");
          Tests.eq(1.0, sim.getTriggerUpperBound(), "limits are still stored");

          sim.setTriggerLowerBound(0.5);
          AnalogJNI.setAnalogTriggerLimitsRaw(handle, 100, 200);
          Tests.eq(0.5, sim.getTriggerLowerBound(), "raw limits are not stored");
          try {
            AnalogJNI.setAnalogTriggerLimitsRaw(handle, 200, 100);
            throw new AssertionError("expected UncleanStatusException");
          } catch (UncleanStatusException expected) {
            Tests.check(expected.getMessage().contains("-1010"), expected.getMessage());
          }

          try {
            trigger.setLimitsDutyCycle(0.1, 0.9);
            throw new AssertionError("expected UncleanStatusException");
          } catch (UncleanStatusException expected) {
            Tests.check(expected.getMessage().contains("-1155"), expected.getMessage());
          }

          AnalogTriggerData data = AnalogTriggerData.get(trigger.getIndex());
          trigger.setAveraged(true);
          Tests.eq(AnalogTriggerData.AVERAGED, data.triggerMode.get(), "averaged");
          trigger.setFiltered(true);
          Tests.eq(AnalogTriggerData.AVERAGED, data.triggerMode.get(), "mode is not changed once assigned");
          trigger.setAveraged(false);
          Tests.eq(AnalogTriggerData.AVERAGED, data.triggerMode.get(), "still assigned");
        } finally {
          DsErrors.setSendErrors(true);
        }
      }
    });

    t.test("analog trigger pulse outputs are not supported", () -> {
      try (AnalogInput ai = new AnalogInput(2)) {
        int trigger = AnalogJNI.initializeAnalogTrigger(analogHandle(2));
        DsErrors.setSendErrors(false);
        try {
          AnalogJNI.getAnalogTriggerOutput(trigger, AnalogJNI.AnalogTriggerType.kRisingPulse);
          throw new AssertionError("expected UncleanStatusException");
        } catch (UncleanStatusException expected) {
          Tests.check(expected.getMessage().contains("-1011"), expected.getMessage());
        } finally {
          DsErrors.setSendErrors(true);
          AnalogJNI.cleanAnalogTrigger(trigger);
        }
      }
    });

    t.test("analog trigger on a freed input reads false", () -> {
      AnalogInput ai = new AnalogInput(2);
      AnalogTrigger trigger = new AnalogTrigger(ai);
      try {
        trigger.setLimitsVoltage(1.0, 3.0);
        new AnalogInputSim(2).setVoltage(5.0);
        Tests.check(trigger.getTriggerState(), "state");
        ai.close();
        Tests.check(!trigger.getTriggerState(), "state after the input was freed");
        Tests.check(!trigger.getInWindow(), "window after the input was freed");
      } finally {
        trigger.close();
      }
    });

    t.test("analog triggers run out after eight", () -> {
      try (AnalogInput ai = new AnalogInput(2)) {
        int[] handles = new int[8];
        for (int i = 0; i < handles.length; i++) {
          handles[i] = AnalogJNI.initializeAnalogTrigger(analogHandle(2));
          Tests.eq(i, AnalogJNI.getAnalogTriggerFPGAIndex(handles[i]), "fpga index");
        }
        DsErrors.setSendErrors(false);
        try {
          AnalogJNI.initializeAnalogTrigger(analogHandle(2));
          throw new AssertionError("expected UncleanStatusException");
        } catch (UncleanStatusException expected) {
          Tests.check(expected.getMessage().contains("-1004"), expected.getMessage());
        } finally {
          DsErrors.setSendErrors(true);
          for (int h : handles) {
            AnalogJNI.cleanAnalogTrigger(h);
          }
        }
      }
    });

    t.test("analog trigger on a missing input is a handle error", () -> {
      try {
        AnalogJNI.initializeAnalogTrigger(analogHandle(7));
        throw new AssertionError("expected HalHandleException");
      } catch (HalHandleException expected) {
        // handle is not allocated
      }
    });
  }

  private static void analogGyro(Tests t) {
    t.test("analog gyro reads angle and rate from the sim", () -> {
      AnalogGyroSim sim = new AnalogGyroSim(1);
      sim.resetData();
      try (AnalogGyro gyro = new AnalogGyro(1)) {
        Tests.check(sim.getInitialized(), "initialized");
        sim.setAngle(45.0);
        sim.setRate(10.0);
        Tests.eq(45.0, gyro.getAngle(), "angle");
        Tests.eq(10.0, gyro.getRate(), "rate");
        Tests.eq(0.0, gyro.getOffset(), "offset");
        Tests.eq(0, gyro.getCenter(), "center");
        gyro.reset();
        Tests.eq(0.0, sim.getAngle(), "reset angle");
        Tests.eq(10.0, sim.getRate(), "reset keeps rate");
        sim.setAngle(30.0);
        gyro.calibrate();
        Tests.eq(0.0, sim.getAngle(), "calibrate resets angle");
        gyro.setSensitivity(0.007);
        gyro.setDeadband(0.01);
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("analog gyro callbacks", () -> {
      AnalogGyroSim sim = new AnalogGyroSim(0);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      var cb = sim.registerAngleCallback((name, value) -> calls.incrementAndGet(), false);
      try (AnalogGyro gyro = new AnalogGyro(0)) {
        sim.setAngle(5.0);
        sim.setAngle(5.0);
        gyro.reset();
        Tests.eq(2, calls.get(), "angle callbacks");
      } finally {
        cb.close();
      }
    });

    t.test("analog gyro needs an accumulator channel and a free slot", () -> {
      try (AnalogInput ai = new AnalogInput(3)) {
        try {
          new AnalogGyro(ai).close();
          throw new AssertionError("expected UncleanStatusException");
        } catch (UncleanStatusException expected) {
          Tests.check(expected.getMessage().contains("-1035"), expected.getMessage());
        }
      }
      try (AnalogGyro first = new AnalogGyro(0)) {
        try {
          AnalogGyroJNI.initializeAnalogGyro(analogHandle(0));
          throw new AssertionError("expected AllocationException");
        } catch (AllocationException expected) {
          Tests.check(expected.getMessage().contains("Analog Gyro"), expected.getMessage());
        }
      }
      try {
        AnalogGyroJNI.initializeAnalogGyro(analogHandle(0));
        throw new AssertionError("expected HalHandleException");
      } catch (HalHandleException expected) {
        // the analog input is gone
      }
    });
  }

  private static void accelerometer(Tests t) {
    t.test("built-in accelerometer with the sim", () -> {
      BuiltInAccelerometerSim sim = new BuiltInAccelerometerSim();
      sim.resetData();
      Tests.check(!sim.getActive(), "inactive");
      try (BuiltInAccelerometer acc = new BuiltInAccelerometer(BuiltInAccelerometer.Range.k4G)) {
        Tests.check(sim.getActive(), "active");
        Tests.eq(1, sim.getRange(), "range");
        sim.setX(0.25);
        sim.setY(-0.5);
        sim.setZ(1.0);
        Tests.eq(0.25, acc.getX(), "x");
        Tests.eq(-0.5, acc.getY(), "y");
        Tests.eq(1.0, acc.getZ(), "z");
        acc.setRange(BuiltInAccelerometer.Range.k8G);
        Tests.eq(2, sim.getRange(), "range 8g");
      }
    });

    t.test("built-in accelerometer callbacks and reset", () -> {
      BuiltInAccelerometerSim sim = new BuiltInAccelerometerSim();
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      var cb = sim.registerXCallback((name, value) -> calls.incrementAndGet(), false);
      sim.setX(1.0);
      sim.setX(1.0);
      sim.setX(2.0);
      Tests.eq(2, calls.get(), "callbacks");
      cb.close();
      sim.setRange(1);
      sim.resetData();
      Tests.eq(0.0, sim.getX(), "x reset");
      Tests.eq(0, sim.getRange(), "range reset");
    });

    t.test("spi accelerometer sim data", () -> {
      SPIAccelerometerSim sim = new SPIAccelerometerSim(2);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      var cb = sim.registerZCallback((name, value) -> calls.incrementAndGet(), false);
      sim.setActive(true);
      sim.setRange(3);
      sim.setX(0.1);
      sim.setY(0.2);
      sim.setZ(0.3);
      Tests.check(sim.getActive(), "active");
      Tests.eq(3, sim.getRange(), "range");
      Tests.eq(0.1, sim.getX(), "x");
      Tests.eq(0.2, sim.getY(), "y");
      Tests.eq(0.3, sim.getZ(), "z");
      Tests.eq(1, calls.get(), "callback");
      cb.close();
      sim.resetData();
      Tests.check(!sim.getActive(), "reset");
      Tests.eq(0.0, sim.getZ(), "z reset");
      Tests.eq(0.0, new SPIAccelerometerSim(3).getZ(), "other index untouched");
    });
  }

  private static final class Sources implements AutoCloseable {
    {
      for (int i = 0; i < 8; i++) {
        EncoderSim.createForIndex(i).resetData();
        DutyCycleSim.createForIndex(i).resetData();
      }
    }

    final AnalogInput inputA = new AnalogInput(0);
    final AnalogInput inputB = new AnalogInput(1);
    final AnalogTrigger triggerA = new AnalogTrigger(inputA);
    final AnalogTrigger triggerB = new AnalogTrigger(inputB);
    final AnalogTriggerOutput a = triggerA.createOutput(AnalogTriggerType.kState);
    final AnalogTriggerOutput b = triggerB.createOutput(AnalogTriggerType.kState);

    @Override
    public void close() {
      triggerB.close();
      triggerA.close();
      inputB.close();
      inputA.close();
    }
  }

  private static void encoder(Tests t) {
    t.test("encoder count, distance and raw value", () -> {
      try (Sources s = new Sources();
          Encoder enc = new Encoder(s.a, s.b, false, EncodingType.k4X)) {
        EncoderSim sim = new EncoderSim(enc);
        Tests.eq(0, enc.getFPGAIndex(), "fpga index");
        sim.setCount(100);
        Tests.eq(100, enc.get(), "count");
        Tests.eq(400, enc.getRaw(), "raw count");
        Tests.eq(4, enc.getEncodingScale(), "encoding scale");
        Tests.eq(0.25, enc.getDecodingScaleFactor(), "decoding scale");
        Tests.eq(1.0, enc.getDistancePerPulse(), "default distance per pulse");
        enc.setDistancePerPulse(0.5);
        Tests.eq(0.5, sim.getDistancePerPulse(), "sim distance per pulse");
        Tests.eq(50.0, enc.getDistance(), "distance");
        Tests.eq(50.0, sim.getDistance(), "sim distance");
        sim.setDistance(10.0);
        Tests.eq(20, sim.getCount(), "count from distance");
        Tests.eq(10.0, enc.getDistance(), "distance after set");
      }
    });

    t.test("encoder period, rate and stopped", () -> {
      try (Sources s = new Sources();
          Encoder enc = new Encoder(s.a, s.b, false, EncodingType.k4X)) {
        EncoderSim sim = new EncoderSim(enc);
        Tests.eq(Double.MAX_VALUE, enc.getPeriod(), "default period");
        Tests.check(enc.getStopped(), "stopped by default");
        enc.setDistancePerPulse(0.5);
        sim.setPeriod(0.5);
        Tests.eq(0.5, enc.getPeriod(), "period");
        Tests.eq(1.0, enc.getRate(), "rate");
        Tests.eq(1.0, sim.getRate(), "sim rate");
        sim.setRate(2.0);
        Tests.eq(0.25, sim.getPeriod(), "period from rate");
        sim.setRate(0.0);
        Tests.eq(Double.POSITIVE_INFINITY, sim.getPeriod(), "zero rate");
        enc.setMaxPeriod(0.2);
        sim.setPeriod(0.1);
        Tests.check(!enc.getStopped(), "moving");
        sim.setPeriod(0.3);
        Tests.check(enc.getStopped(), "stopped");
        enc.setMinRate(2.0);
        Tests.eq(0.25, sim.getMaxPeriod(), "max period from min rate");
      }
    });

    t.test("encoder rejects zero min rate and distance per pulse", () -> {
      try (Sources s = new Sources();
          Encoder enc = new Encoder(s.a, s.b, false, EncodingType.k4X)) {
        DsErrors.setSendErrors(false);
        try {
          try {
            enc.setMinRate(0);
            throw new AssertionError("expected UncleanStatusException");
          } catch (UncleanStatusException expected) {
            Tests.check(expected.getMessage().contains("minRate must not be 0"), expected.getMessage());
          }
          try {
            enc.setDistancePerPulse(0);
            throw new AssertionError("expected UncleanStatusException");
          } catch (UncleanStatusException expected) {
            Tests.check(
                expected.getMessage().contains("distancePerPulse must not be 0"),
                expected.getMessage());
          }
          Tests.eq(1.0, enc.getDistancePerPulse(), "distance per pulse kept");
        } finally {
          DsErrors.setSendErrors(true);
        }
      }
    });

    t.test("encoder direction, reverse and samples to average", () -> {
      try (Sources s = new Sources();
          Encoder enc = new Encoder(s.a, s.b, true, EncodingType.k4X)) {
        EncoderSim sim = new EncoderSim(enc);
        Tests.check(sim.getReverseDirection(), "reverse from constructor");
        enc.setReverseDirection(false);
        Tests.check(!sim.getReverseDirection(), "reverse cleared");
        Tests.check(!enc.getDirection(), "direction");
        sim.setDirection(true);
        Tests.check(enc.getDirection(), "direction from sim");
        enc.setSamplesToAverage(5);
        Tests.eq(5, sim.getSamplesToAverage(), "samples to average");
        Tests.eq(5, enc.getSamplesToAverage(), "samples to average back");
      }
    });

    t.test("encoder reset", () -> {
      try (Sources s = new Sources();
          Encoder enc = new Encoder(s.a, s.b, false, EncodingType.k4X)) {
        EncoderSim sim = new EncoderSim(enc);
        AtomicInteger resets = new AtomicInteger();
        var cb = sim.registerResetCallback((name, value) -> resets.incrementAndGet(), false);
        sim.setCount(50);
        sim.setPeriod(0.2);
        Tests.check(!sim.getReset(), "reset flag clear");
        enc.reset();
        Tests.eq(0, enc.get(), "count after reset");
        Tests.eq(Double.MAX_VALUE, enc.getPeriod(), "period after reset");
        Tests.check(sim.getReset(), "reset flag set");
        Tests.eq(1, resets.get(), "reset callback");
        sim.setCount(5);
        Tests.check(!sim.getReset(), "setting the count clears the flag");
        cb.close();
      }
    });

    t.test("encoder count callbacks", () -> {
      try (Sources s = new Sources();
          Encoder enc = new Encoder(s.a, s.b, false, EncodingType.k4X)) {
        EncoderSim sim = new EncoderSim(enc);
        AtomicInteger calls = new AtomicInteger();
        var cb = sim.registerCountCallback((name, value) -> calls.incrementAndGet(), false);
        sim.setCount(1);
        sim.setCount(1);
        sim.setCount(2);
        Tests.eq(2, calls.get(), "count callbacks");
        cb.close();
      }
    });

    t.test("encoder encoding types", () -> {
      int[] scale = {1, 2, 4};
      double[] decoding = {1.0, 0.5, 0.25};
      EncodingType[] types = {EncodingType.k1X, EncodingType.k2X, EncodingType.k4X};
      for (int i = 0; i < types.length; i++) {
        try (Sources s = new Sources();
            Encoder enc = new Encoder(s.a, s.b, false, types[i])) {
          Tests.eq(scale[i], enc.getEncodingScale(), "scale " + types[i]);
          Tests.eq(decoding[i], enc.getDecodingScaleFactor(), "decoding " + types[i]);
          Tests.eq(i, EncoderJNI.getEncoderEncodingType(encoderHandle(enc)), "hal type " + types[i]);
        }
      }
    });

    t.test("encoder is found by channel and freed", () -> {
      EncoderSim sim = EncoderSim.createForIndex(0);
      sim.resetData();
      try (Sources s = new Sources()) {
        Encoder enc = new Encoder(s.a, s.b);
        Tests.check(sim.getInitialized(), "initialized");
        EncoderSim found = EncoderSim.createForChannel(s.triggerA.getIndex());
        found.setCount(7);
        Tests.eq(7, sim.getCount(), "found the same encoder");
        EncoderSim foundB = EncoderSim.createForChannel(s.triggerB.getIndex());
        Tests.eq(7, foundB.getCount(), "found by the b channel");
        enc.close();
        Tests.check(!sim.getInitialized(), "freed");
      }
    });

    t.test("encoders on dio handles", () -> {
      DigitalPorts.Allocation a = DigitalPorts.allocate(12, Handles.DIO);
      DigitalPorts.Allocation b = DigitalPorts.allocate(13, Handles.DIO);
      a.port.channel = 12;
      b.port.channel = 13;
      int handle = EncoderJNI.initializeEncoder(a.handle, 0, b.handle, 0, false, K4X);
      try {
        EncoderSim sim = EncoderSim.createForChannel(13);
        Tests.check(sim.getInitialized(), "initialized");
        sim.setCount(8);
        Tests.eq(8, EncoderJNI.getEncoder(handle), "count");
        Tests.eq(32, EncoderJNI.getEncoderRaw(handle), "raw");
        Tests.eq(4, EncoderJNI.getEncodingScaleFactor(handle), "scale");
        Tests.eq(K4X, EncoderJNI.getEncoderEncodingType(handle), "type");
        EncoderJNI.setEncoderIndexSource(handle, a.handle, 0, 0);
      } finally {
        EncoderJNI.freeEncoder(handle);
        EncoderSim.createForIndex(0).resetData();
        DigitalPorts.free(a.handle, Handles.DIO);
        DigitalPorts.free(b.handle, Handles.DIO);
      }
      try {
        EncoderJNI.getEncoder(handle);
        throw new AssertionError("expected HalHandleException");
      } catch (HalHandleException expected) {
        // stale handle
      }
    });

    t.test("encoders run out after eight", () -> {
      int[] handles = new int[8];
      DigitalPorts.Allocation a = DigitalPorts.allocate(14, Handles.DIO);
      try {
        for (int i = 0; i < handles.length; i++) {
          handles[i] = EncoderJNI.initializeEncoder(a.handle, 0, a.handle, 0, false, K4X);
          Tests.eq(i, EncoderJNI.getEncoderFPGAIndex(handles[i]), "index");
        }
        try {
          EncoderJNI.initializeEncoder(a.handle, 0, a.handle, 0, false, K4X);
          throw new AssertionError("expected AllocationException");
        } catch (AllocationException expected) {
          Tests.check(expected.getMessage().contains("-1004"), expected.getMessage());
        }
        EncoderJNI.freeEncoder(handles[3]);
        int again = EncoderJNI.initializeEncoder(a.handle, 0, a.handle, 0, false, K4X);
        Tests.eq(3, EncoderJNI.getEncoderFPGAIndex(again), "reuses the freed slot");
        handles[3] = again;
      } finally {
        for (int h : handles) {
          EncoderJNI.freeEncoder(h);
        }
        DigitalPorts.free(a.handle, Handles.DIO);
        for (int i = 0; i < 8; i++) {
          EncoderSim.createForIndex(i).resetData();
        }
      }
    });
  }

  private static int encoderHandle(Encoder enc) {
    return Handles.make(enc.getFPGAIndex(), Handles.ENCODER);
  }

  private static void dutyCycle(Tests t) {
    t.test("duty cycle reads frequency and output from the sim", () -> {
      try (Sources s = new Sources();
          DutyCycle dc = new DutyCycle(s.a)) {
        DutyCycleSim sim = new DutyCycleSim(dc);
        Tests.eq(0, dc.getFPGAIndex(), "fpga index");
        Tests.eq(0, dc.getFrequency(), "default frequency");
        Tests.eq(0, dc.getHighTimeNanoseconds(), "high time without frequency");
        sim.setFrequency(1000);
        sim.setOutput(0.5);
        Tests.eq(1000, dc.getFrequency(), "frequency");
        Tests.eq(0.5, dc.getOutput(), "output");
        Tests.near(500000, dc.getHighTimeNanoseconds(), 1, "high time");
        Tests.eq(39999999, dc.getOutputScaleFactor(), "scale factor");
        Tests.eq(s.triggerA.getIndex(), dc.getSourceChannel(), "source channel");
        DutyCycleSim found = DutyCycleSim.createForChannel(s.triggerA.getIndex());
        Tests.eq(1000, found.getFrequency(), "found by channel");
      }
    });

    t.test("duty cycle callbacks and free", () -> {
      DutyCycleSim sim = DutyCycleSim.createForIndex(0);
      AtomicInteger calls = new AtomicInteger();
      try (Sources s = new Sources();
          DutyCycle dc = new DutyCycle(s.a)) {
        var cb = sim.registerOutputCallback((name, value) -> calls.incrementAndGet(), false);
        Tests.check(sim.getInitialized(), "initialized");
        sim.setOutput(0.1);
        sim.setOutput(0.1);
        sim.setOutput(0.2);
        Tests.eq(2, calls.get(), "callbacks");
        cb.close();
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("duty cycles run out after eight", () -> {
      DigitalPorts.Allocation a = DigitalPorts.allocate(15, Handles.DIO);
      int[] handles = new int[8];
      try {
        for (int i = 0; i < handles.length; i++) {
          handles[i] = DutyCycleJNI.initialize(a.handle, 0);
          Tests.eq(i, DutyCycleJNI.getFPGAIndex(handles[i]), "index");
        }
        DsErrors.setSendErrors(false);
        try {
          DutyCycleJNI.initialize(a.handle, 0);
          throw new AssertionError("expected UncleanStatusException");
        } catch (UncleanStatusException expected) {
          Tests.check(expected.getMessage().contains("-1004"), expected.getMessage());
        } finally {
          DsErrors.setSendErrors(true);
        }
        DutyCycleSim sim = DutyCycleSim.createForChannel(15);
        sim.setFrequency(50);
        Tests.eq(50, DutyCycleJNI.getFrequency(handles[0]), "frequency");
      } finally {
        for (int h : handles) {
          DutyCycleJNI.free(h);
        }
        DigitalPorts.free(a.handle, Handles.DIO);
        for (int i = 0; i < 8; i++) {
          DutyCycleSim.createForIndex(i).resetData();
        }
      }
    });
  }

  private static void handles(Tests t) {
    t.test("analog input on a bad channel or taken channel", () -> {
      try {
        AnalogJNI.initializeAnalogInputPort(HAL.getPort((byte) 8));
        throw new AssertionError("expected AllocationException");
      } catch (AllocationException expected) {
        Tests.check(expected.getMessage().contains("Invalid Index for Analog Input"), expected.getMessage());
      }
      try (AnalogInput ai = new AnalogInput(5)) {
        try {
          new AnalogInput(5).close();
          throw new AssertionError("expected AllocationException");
        } catch (AllocationException expected) {
          Tests.check(expected.getMessage().contains("previously allocated"), expected.getMessage());
          Tests.check(expected.getMessage().contains("Analog Input"), expected.getMessage());
        }
      }
    });

    t.test("stale and invalid analog handles", () -> {
      int handle = AnalogJNI.initializeAnalogInputPort(HAL.getPort((byte) 6));
      AnalogJNI.freeAnalogInputPort(handle);
      AnalogJNI.freeAnalogInputPort(handle);
      AnalogJNI.freeAnalogInputPort(0);
      try {
        AnalogJNI.getAnalogVoltage(handle);
        throw new AssertionError("expected HalHandleException");
      } catch (HalHandleException expected) {
        // freed
      }
      try {
        AnalogJNI.getAnalogOutput(0);
        throw new AssertionError("expected HalHandleException");
      } catch (HalHandleException expected) {
        // invalid
      }
      try {
        AnalogJNI.getAccumulatorOutput(handle, new AccumulatorResult());
        throw new AssertionError("expected HalHandleException");
      } catch (HalHandleException expected) {
        // freed
      }
      try {
        DutyCycleJNI.getOutput(0);
        throw new AssertionError("expected HalHandleException");
      } catch (HalHandleException expected) {
        // invalid
      }
    });

    t.test("analog channel and module checks", () -> {
      Tests.check(AnalogJNI.checkAnalogInputChannel(0), "input 0");
      Tests.check(AnalogJNI.checkAnalogInputChannel(7), "input 7");
      Tests.check(!AnalogJNI.checkAnalogInputChannel(8), "input 8");
      Tests.check(!AnalogJNI.checkAnalogInputChannel(-1), "input -1");
      Tests.check(AnalogJNI.checkAnalogOutputChannel(1), "output 1");
      Tests.check(!AnalogJNI.checkAnalogOutputChannel(2), "output 2");
      Tests.check(AnalogJNI.checkAnalogModule((byte) 1), "module 1");
      Tests.check(!AnalogJNI.checkAnalogModule((byte) 0), "module 0");
    });
  }

  private static LocalTransport transport() {
    return BridgeLoop.local();
  }

  private static void waitFor(BooleanSupplier condition, String message) {
    long end = System.nanoTime() + 3_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out: " + message);
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
  }

  /** Last value sent for a key of a device, or null. */
  @SuppressWarnings("unchecked")
  private static Object sentValue(String type, String device, String key) {
    BridgeLoop.flush();
    Object found = null;
    List<Map<String, Object>> all = new ArrayList<>(transport().sent());
    for (Map<String, Object> m : all) {
      if (type.equals(m.get("type")) && device.equals(m.get("device"))) {
        Map<String, Object> data = (Map<String, Object>) m.get("data");
        if (data.containsKey(key)) {
          found = data.get(key);
        }
      }
    }
    return found;
  }

  private static double number(Object o) {
    return ((Number) o).doubleValue();
  }

  private static void ws(Tests t) {
    t.test("ws AI: init, voltage echo, page input and free", () -> {
      transport().clearSent();
      AnalogInputSim sim = new AnalogInputSim(1);
      sim.resetData();
      AnalogInput ai = new AnalogInput(1);
      Tests.eq(Boolean.TRUE, sentValue("AI", "1", "<init"), "init sent");
      Tests.eq(7L, ((Number) sentValue("AI", "1", "<avg_bits")).longValue(), "avg bits sent with state");

      sim.setVoltage(1.5);
      Tests.eq(1.5, number(sentValue("AI", "1", ">voltage")), "voltage delta");

      transport().clearSent();
      Map<String, Object> data = new LinkedHashMap<>();
      data.put(">voltage", 3.25);
      transport().inject("AI", "1", data);
      waitFor(() -> ai.getVoltage() == 3.25, "page voltage applied");
      Tests.eq(null, sentValue("AI", "1", ">voltage"), "page value is not echoed");

      ai.setOversampleBits(3);
      Tests.eq(3L, ((Number) sentValue("AI", "1", "<oversample_bits")).longValue(), "oversample bits");

      ai.close();
      Tests.eq(Boolean.FALSE, sentValue("AI", "1", "<init"), "init false on free");
    });

    t.test("ws AI: input for an uninitialized device is stored", () -> {
      AnalogInputSim sim = new AnalogInputSim(2);
      sim.resetData();
      Map<String, Object> data = new LinkedHashMap<>();
      data.put(">voltage", 4.5);
      data.put(">accum_count", 12);
      transport().inject("AI", "2", data);
      waitFor(() -> sim.getVoltage() == 4.5, "voltage applied");
      waitFor(() -> sim.getAccumulatorCount() == 12, "count applied");
      sim.resetData();
    });

    t.test("ws AO and hello", () -> {
      transport().clearSent();
      AnalogOutputSim sim = new AnalogOutputSim(1);
      sim.resetData();
      try (AnalogOutput ao = new AnalogOutput(1)) {
        ao.setVoltage(2.0);
        Tests.eq(2.0, number(sentValue("AO", "1", "<voltage")), "voltage");
        transport().clearSent();
        transport().inject("{\"type\":\"hello\"}");
        waitFor(() -> Boolean.TRUE.equals(sentValue("AO", "1", "<init")), "state after hello");
        Tests.eq(2.0, number(sentValue("AO", "1", "<voltage")), "voltage after hello");
      }
    });

    t.test("ws Accel: range is sent in g and axes are inputs", () -> {
      transport().clearSent();
      BuiltInAccelerometerSim sim = new BuiltInAccelerometerSim();
      sim.resetData();
      try (BuiltInAccelerometer acc = new BuiltInAccelerometer(BuiltInAccelerometer.Range.k4G)) {
        Tests.eq(Boolean.TRUE, sentValue("Accel", "BuiltInAccel", "<init"), "init");
        Tests.eq(4.0, number(sentValue("Accel", "BuiltInAccel", "<range")), "range 4g");
        acc.setRange(BuiltInAccelerometer.Range.k8G);
        Tests.eq(8.0, number(sentValue("Accel", "BuiltInAccel", "<range")), "range 8g");
        acc.setRange(BuiltInAccelerometer.Range.k2G);
        Tests.eq(2.0, number(sentValue("Accel", "BuiltInAccel", "<range")), "range 2g");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(">x", 0.5);
        data.put(">z", 1.0);
        transport().inject("Accel", "BuiltInAccel", data);
        waitFor(() -> acc.getX() == 0.5 && acc.getZ() == 1.0, "axes applied");
      }
      sim.setActive(false);
      Tests.eq(Boolean.FALSE, sentValue("Accel", "BuiltInAccel", "<init"), "init false when deactivated");
    });

    t.test("ws Encoder: channels, count offset and reset", () -> {
      transport().clearSent();
      try (Sources s = new Sources()) {
        Encoder enc = new Encoder(s.a, s.b, false, EncodingType.k4X);
        EncoderSim sim = new EncoderSim(enc);
        Tests.eq(Boolean.TRUE, sentValue("Encoder", "0", "<init"), "init");
        Tests.eq(
            (long) s.triggerA.getIndex(),
            ((Number) sentValue("Encoder", "0", "<channel_a")).longValue(),
            "channel a");
        Tests.eq(
            (long) s.triggerB.getIndex(),
            ((Number) sentValue("Encoder", "0", "<channel_b")).longValue(),
            "channel b");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(">count", 25);
        data.put(">period", 0.5);
        transport().inject("Encoder", "0", data);
        waitFor(() -> sim.getCount() == 25 && sim.getPeriod() == 0.5, "page input applied");

        enc.reset();
        Tests.eq(0, sim.getCount(), "robot count after reset");
        Tests.eq(25L, ((Number) sentValue("Encoder", "0", ">count")).longValue(), "page count is unchanged");

        data = new LinkedHashMap<>();
        data.put(">count", 30);
        transport().inject("Encoder", "0", data);
        waitFor(() -> sim.getCount() == 5, "page count relative to the reset");

        enc.setReverseDirection(true);
        Tests.eq(Boolean.TRUE, sentValue("Encoder", "0", "<reverse_direction"), "reverse direction");
        enc.setSamplesToAverage(4);
        Tests.eq(4L, ((Number) sentValue("Encoder", "0", "<samples_to_avg")).longValue(), "samples");
        enc.close();
        Tests.eq(Boolean.FALSE, sentValue("Encoder", "0", "<init"), "init false on free");
        sim.resetData();
      }
    });
  }
}
