package dev.project516.frcdevwasm.test;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.hal.util.AllocationException;
import edu.wpi.first.wpilibj.motorcontrol.PWMSparkMax;
import edu.wpi.first.wpilibj.simulation.PWMSim;
import java.util.concurrent.atomic.AtomicInteger;

/** PWM through the real wpilibj classes: HAL port, sim data store and callbacks. */
public final class PwmTest {
  private PwmTest() {}

  public static void run(Tests t) {
    HAL.initialize(500, 0);

    t.test("pwm motor controller output reaches the sim store", () -> {
      PWMSim sim = new PWMSim(2);
      sim.resetData();
      try (PWMSparkMax motor = new PWMSparkMax(2)) {
        Tests.check(sim.getInitialized(), "initialized");
        motor.set(0.5);
        Tests.near(0.5, sim.getSpeed(), 0.01, "speed");
        motor.set(-1);
        Tests.near(-1.0, sim.getSpeed(), 1e-9, "full reverse");
        motor.stopMotor();
        Tests.eq(0.0, sim.getSpeed(), "stopped");
      }
      Tests.check(!sim.getInitialized(), "freed");
    });

    t.test("pwm callbacks fire on change only", () -> {
      PWMSim sim = new PWMSim(4);
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      var cb = sim.registerSpeedCallback((name, value) -> calls.incrementAndGet(), false);
      try (PWMSparkMax motor = new PWMSparkMax(4)) {
        motor.set(0.25);
        motor.set(0.25);
        motor.set(0.75);
        Tests.eq(2, calls.get(), "callback count");
      } finally {
        cb.close();
      }
    });

    t.test("pwm channel allocated twice throws AllocationException", () -> {
      try (PWMSparkMax a = new PWMSparkMax(6)) {
        try {
          new PWMSparkMax(6).close();
          throw new AssertionError("expected AllocationException");
        } catch (AllocationException expected) {
          Tests.check(expected.getMessage().contains("previously allocated"), expected.getMessage());
        }
      }
    });
  }
}
