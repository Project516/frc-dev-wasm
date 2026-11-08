package dev.project516.frcdevwasm.test;

import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.util.AllocationException;
import org.wpilib.simulation.PWMSim;
import java.util.concurrent.atomic.AtomicInteger;

/** PWM through the real wpilibj classes: HAL port, sim data store and callbacks. */
public final class PwmTest {
  private PwmTest() {}

  public static void run(Tests t) {
    HAL.initialize();

  }
}
