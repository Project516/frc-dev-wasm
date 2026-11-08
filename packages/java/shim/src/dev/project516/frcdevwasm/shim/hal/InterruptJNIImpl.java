package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.BoolValue;
import dev.project516.frcdevwasm.sim.DigitalPorts;
import dev.project516.frcdevwasm.sim.DoubleValue;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.InterruptSources;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.SimTime;
import dev.project516.frcdevwasm.sim.SimValue;
import dev.project516.frcdevwasm.sim.Status;
import edu.wpi.first.hal.simulation.NotifyCallback;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Port of sim/Interrupts.cpp and InterruptJNI.cpp. A wait blocks on a data change of the DIO or
 * analog input behind the interrupt's source, in real time.
 */
public final class InterruptJNIImpl {
  private static final long TIMEOUT = 0;

  private static final class Interrupt {
    volatile boolean isAnalog;
    volatile int portHandle;
    int index;
    volatile int trigType;
    volatile long risingTimestamp;
    volatile long fallingTimestamp;
    volatile boolean currentState;
    volatile boolean fireOnUp;
    volatile boolean fireOnDown;
    final List<Wait> waits = new CopyOnWriteArrayList<>();
  }

  /** SynchronousWaitData. */
  private static final class Wait {
    private boolean fired;

    synchronized void fire() {
      fired = true;
      notifyAll();
    }

    /** Waits until fired. Returns true on timeout. */
    synchronized boolean await(double timeoutSeconds) {
      double nanos = timeoutSeconds * 1e9;
      long total = nanos >= Long.MAX_VALUE / 2 ? Long.MAX_VALUE / 2 : (long) Math.max(nanos, 0);
      long deadline = System.nanoTime() + total;
      while (!fired) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return true;
        }
        try {
          wait(left / 1_000_000, (int) (left % 1_000_000));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return true;
        }
      }
      return false;
    }
  }

  private static final Handles.Limited<Interrupt> INTERRUPTS =
      new Handles.Limited<>(Handles.INTERRUPT, Ports.INTERRUPTS);

  private InterruptJNIImpl() {}

  public static int initializeInterrupts() {
    Sim.ensureInitialized();
    Interrupt interrupt = new Interrupt();
    int handle = INTERRUPTS.allocate(interrupt);
    if (handle == Handles.INVALID) {
      Status.checkForceThrow(Status.NO_AVAILABLE_RESOURCES);
      return 0;
    }
    interrupt.index = handle & 0xffff;
    return handle;
  }

  public static void cleanInterrupts(int interruptHandle) {
    if (interruptHandle != Handles.INVALID) {
      INTERRUPTS.free(interruptHandle);
    }
  }

  /** Records a data change as an edge, and wakes the wait if the interrupt fires on it. */
  private static void process(Interrupt interrupt, Wait wait, boolean state) {
    boolean previous;
    synchronized (interrupt) {
      previous = interrupt.currentState;
      interrupt.currentState = state;
    }
    if (state == previous) {
      return;
    }
    if (previous && !interrupt.fireOnDown) {
      return;
    }
    if (!previous && !interrupt.fireOnUp) {
      return;
    }
    wait.fire();
  }

  private static long waitDigital(Interrupt interrupt, double timeout) {
    Wait wait = new Wait();
    interrupt.waits.add(wait);
    try {
      int channel = DigitalPorts.digitalInputChannel(interrupt.portHandle);
      if (channel < 0) {
        return TIMEOUT;
      }
      InterruptSources.Provider sources = InterruptSources.current;
      BoolValue value = sources == null ? null : sources.digitalInput(channel);
      int uid = 0;
      if (value != null) {
        interrupt.currentState = value.get();
        uid =
            value.register(
                (NotifyCallback)
                    (name, v) -> {
                      if (v.getType() == SimValue.BOOLEAN) {
                        process(interrupt, wait, v.getBoolean());
                      }
                    },
                false);
      }
      boolean timedOut = wait.await(timeout);
      if (value != null) {
        value.cancel(uid);
      }
      return result(interrupt, timedOut);
    } finally {
      interrupt.waits.remove(wait);
    }
  }

  private static long waitAnalog(Interrupt interrupt, double timeout) {
    Wait wait = new Wait();
    interrupt.waits.add(wait);
    try {
      InterruptSources.Provider sources = InterruptSources.current;
      if (sources == null) {
        wait.await(timeout);
        return TIMEOUT;
      }
      int output = sources.analogTriggerOutput(interrupt.portHandle, interrupt.trigType);
      if (output < 0) {
        return TIMEOUT;
      }
      interrupt.currentState = output != 0;
      int index = sources.analogTriggerInputIndex(interrupt.portHandle);
      if (index < 0) {
        return TIMEOUT;
      }
      DoubleValue voltage = sources.analogInputVoltage(index);
      int uid =
          voltage.register(
              (NotifyCallback)
                  (name, v) -> {
                    if (v.getType() != SimValue.DOUBLE) {
                      return;
                    }
                    int state =
                        sources.analogTriggerOutput(interrupt.portHandle, interrupt.trigType);
                    if (state < 0) {
                      wait.fire();
                    }
                    process(interrupt, wait, state > 0);
                  },
              false);
      boolean timedOut = wait.await(timeout);
      voltage.cancel(uid);
      return result(interrupt, timedOut);
    } finally {
      interrupt.waits.remove(wait);
    }
  }

  private static long result(Interrupt interrupt, boolean timedOut) {
    if (timedOut) {
      return TIMEOUT;
    }
    // The value changed, so a true state means a rising edge.
    if (interrupt.currentState) {
      interrupt.risingTimestamp = SimTime.fpgaTime();
      return 1 << interrupt.index;
    }
    interrupt.fallingTimestamp = SimTime.fpgaTime();
    return 1 << (8 + interrupt.index);
  }

  private static Interrupt get(int handle) {
    Interrupt i = INTERRUPTS.get(handle);
    if (i == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return i;
  }

  public static long waitForInterrupt(int interruptHandle, double timeout, boolean ignorePrevious) {
    Interrupt interrupt = get(interruptHandle);
    if (interrupt == null) {
      return TIMEOUT;
    }
    return interrupt.isAnalog ? waitAnalog(interrupt, timeout) : waitDigital(interrupt, timeout);
  }

  public static long waitForMultipleInterrupts(
      int interruptHandle, long mask, double timeout, boolean ignorePrevious) {
    return waitForInterrupt(interruptHandle, timeout, ignorePrevious);
  }

  public static long readInterruptRisingTimestamp(int interruptHandle) {
    Interrupt interrupt = get(interruptHandle);
    return interrupt == null ? 0 : interrupt.risingTimestamp;
  }

  public static long readInterruptFallingTimestamp(int interruptHandle) {
    Interrupt interrupt = get(interruptHandle);
    return interrupt == null ? 0 : interrupt.fallingTimestamp;
  }

  public static void requestInterrupts(
      int interruptHandle, int digitalSourceHandle, int analogTriggerType) {
    Interrupt interrupt = get(interruptHandle);
    if (interrupt == null) {
      return;
    }
    DigitalPorts.Source source =
        DigitalPorts.remapDigitalSource(digitalSourceHandle, analogTriggerType);
    if (source == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
      return;
    }
    interrupt.isAnalog = source.analogTrigger();
    interrupt.trigType = analogTriggerType;
    interrupt.portHandle = digitalSourceHandle;
  }

  public static void setInterruptUpSourceEdge(
      int interruptHandle, boolean risingEdge, boolean fallingEdge) {
    Interrupt interrupt = get(interruptHandle);
    if (interrupt != null) {
      interrupt.fireOnDown = fallingEdge;
      interrupt.fireOnUp = risingEdge;
    }
  }

  public static void releaseWaitingInterrupt(int interruptHandle) {
    Interrupt interrupt = get(interruptHandle);
    if (interrupt != null) {
      for (Wait w : interrupt.waits) {
        w.fire();
      }
    }
  }
}
