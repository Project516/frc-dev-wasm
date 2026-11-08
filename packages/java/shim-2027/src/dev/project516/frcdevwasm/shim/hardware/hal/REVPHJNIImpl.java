package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.REVPHData;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;
import org.wpilib.hardware.hal.REVPHVersion;

/** Port of hal/src/main/native/sim/REVPH.cpp and the REVPHJNI wrappers. */
public final class REVPHJNIImpl {
  private static final class Ph {
    final int module;
    final String previousAllocation;

    Ph(int module, String previousAllocation) {
      this.module = module;
      this.previousAllocation = previousAllocation;
    }
  }

  // Indexed by module - 1.
  private static final Handles.Limited<Ph> HANDLES =
      new Handles.Limited<>(Handles.REV_PH, Ports.REV_PH_MODULES);

  private REVPHJNIImpl() {}

  /** The PH behind a handle, or null after reporting a handle error. */
  private static Ph ph(int handle) {
    Ph ph = HANDLES.get(handle);
    if (ph == null) {
      Status.check(Status.HAL_HANDLE_ERROR, false);
    }
    return ph;
  }

  private static REVPHData data(int handle) {
    Ph ph = ph(handle);
    return ph == null ? null : REVPHData.get(ph.module);
  }

  private static void setConfigType(int handle, int type) {
    REVPHData d = data(handle);
    if (d != null) {
      d.compressorConfigType.set(type);
    }
  }

  public static int initialize(int busId, int module) {
    Sim.ensureInitialized();
    if (module < 1 || module > Ports.REV_PH_MODULES) {
      Status.indexOutOfRange(
          Status.RESOURCE_OUT_OF_RANGE,
          "Invalid Index for REV PH",
          1,
          Ports.REV_PH_MODULES,
          module);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    Ph existing = HANDLES.get(Handles.make(module - 1, Handles.REV_PH));
    if (existing != null) {
      Status.previouslyAllocated(
          Status.RESOURCE_IS_ALLOCATED, "REV PH", module, existing.previousAllocation);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    int handle = HANDLES.allocate(module - 1, new Ph(module, Status.allocationLocation()));
    REVPHData d = REVPHData.get(module);
    d.initialized.set(true);
    d.compressorConfigType.set(REVPHData.CONFIG_DIGITAL);
    return handle;
  }

  public static void free(int handle) {
    Ph ph = HANDLES.get(handle);
    if (ph != null) {
      REVPHData.get(ph.module).initialized.set(false);
    }
    HANDLES.free(handle);
  }

  public static boolean checkSolenoidChannel(int channel) {
    return channel < Ports.REV_PH_CHANNELS && channel >= 0;
  }

  public static boolean getCompressor(int handle) {
    REVPHData d = data(handle);
    return d != null && d.compressorOn.get();
  }

  // The simulation does not derive the config type from these parameters.
  public static void setCompressorConfig(
      int handle,
      double minAnalogVoltage,
      double maxAnalogVoltage,
      boolean forceDisable,
      boolean useDigital) {
    ph(handle);
  }

  public static void setClosedLoopControlDisabled(int handle) {
    setConfigType(handle, REVPHData.CONFIG_DISABLED);
  }

  public static void setClosedLoopControlDigital(int handle) {
    setConfigType(handle, REVPHData.CONFIG_DIGITAL);
  }

  public static void setClosedLoopControlAnalog(
      int handle, double minAnalogVoltage, double maxAnalogVoltage) {
    setConfigType(handle, REVPHData.CONFIG_ANALOG);
  }

  public static void setClosedLoopControlHybrid(
      int handle, double minAnalogVoltage, double maxAnalogVoltage) {
    setConfigType(handle, REVPHData.CONFIG_HYBRID);
  }

  public static int getCompressorConfig(int handle) {
    REVPHData d = data(handle);
    return d == null ? REVPHData.CONFIG_DISABLED : d.compressorConfigType.get();
  }

  public static boolean getPressureSwitch(int handle) {
    REVPHData d = data(handle);
    return d != null && d.pressureSwitch.get();
  }

  public static double getAnalogVoltage(int handle, int channel) {
    return 0;
  }

  public static double getCompressorCurrent(int handle) {
    REVPHData d = data(handle);
    return d == null ? 0 : d.compressorCurrent.get();
  }

  public static int getSolenoids(int handle) {
    Ph ph = ph(handle);
    if (ph == null) {
      return 0;
    }
    synchronized (ph) {
      return REVPHData.get(ph.module).solenoidMask();
    }
  }

  public static void setSolenoids(int handle, int mask, int values) {
    Ph ph = ph(handle);
    if (ph == null) {
      return;
    }
    REVPHData d = REVPHData.get(ph.module);
    synchronized (ph) {
      for (int i = 0; i < Ports.REV_PH_CHANNELS; i++) {
        int indexMask = 1 << i;
        if ((mask & indexMask) != 0) {
          d.solenoidOutput[i].set((values & indexMask) != 0);
        }
      }
    }
  }

  public static void fireOneShot(int handle, int index, int durMs) {}

  public static void clearStickyFaults(int handle) {}

  public static double getInputVoltage(int handle) {
    return 0;
  }

  public static double get5VVoltage(int handle) {
    return 0;
  }

  public static double getSolenoidCurrent(int handle) {
    return 0;
  }

  public static double getSolenoidVoltage(int handle) {
    return 0;
  }

  public static int getStickyFaultsNative(int handle) {
    return 0;
  }

  public static int getFaultsNative(int handle) {
    return 0;
  }

  public static int getSolenoidDisabledList(int handle) {
    return 0;
  }

  public static REVPHVersion getVersion(int handle) {
    return new REVPHVersion(0, 0, 0, 0, 0, 0);
  }
}
