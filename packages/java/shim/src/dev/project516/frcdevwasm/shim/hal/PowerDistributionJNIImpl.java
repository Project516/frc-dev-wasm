package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.DoubleValue;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.PowerDistributionData;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;
import edu.wpi.first.hal.PowerDistributionVersion;

/** Port of hal/src/main/native/sim/PowerDistribution.cpp and the PowerDistributionJNI wrappers. */
public final class PowerDistributionJNIImpl {
  private static final int AUTOMATIC_TYPE = 0;
  private static final int CTRE_TYPE = 1;
  private static final int DEFAULT_MODULE = -1;

  // Module number per handle.
  private static final Handles.Unlimited<Integer> HANDLES = new Handles.Unlimited<>(Handles.CAN);

  private PowerDistributionJNIImpl() {}

  /** Data of the module behind a handle, or null (after reporting a handle error if requested). */
  private static PowerDistributionData data(int handle, boolean report) {
    Integer module = HANDLES.get(handle);
    if (module == null) {
      if (report) {
        Status.check(Status.HAL_HANDLE_ERROR, false);
      }
      return null;
    }
    return PowerDistributionData.get(module);
  }

  private static double total(PowerDistributionData d) {
    double total = 0;
    for (DoubleValue c : d.current) {
      total += c.get();
    }
    return total;
  }

  public static int initialize(int module, int type) {
    if (type == AUTOMATIC_TYPE) {
      if (module != DEFAULT_MODULE) {
        Status.setLastError(
            Status.PARAMETER_OUT_OF_RANGE,
            "Automatic PowerDistributionType must have default module");
        Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
        return 0;
      }
      type = CTRE_TYPE;
      module = 0;
    }

    if (!checkModule(module, type)) {
      if (type == CTRE_TYPE) {
        Status.indexOutOfRange(
            Status.RESOURCE_OUT_OF_RANGE,
            "Invalid Index for CTRE PDP",
            0,
            Ports.CTRE_PDP_MODULES - 1,
            module);
      } else {
        Status.indexOutOfRange(
            Status.RESOURCE_OUT_OF_RANGE,
            "Invalid Index for REV PDH",
            1,
            Ports.REV_PDH_MODULES,
            module);
      }
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    Sim.ensureInitialized();
    PowerDistributionData.get(module).initialized.set(true);
    return HANDLES.allocate(module);
  }

  public static void free(int handle) {
    if (handle != Handles.INVALID) {
      HANDLES.free(handle);
    }
  }

  public static int getModuleNumber(int handle) {
    Integer module = HANDLES.get(handle);
    if (module == null) {
      Status.check(Status.HAL_HANDLE_ERROR, false);
      return 0;
    }
    return module;
  }

  public static boolean checkModule(int module, int type) {
    if (type == CTRE_TYPE) {
      return module < Ports.CTRE_PDP_MODULES && module >= 0;
    }
    return module <= Ports.REV_PDH_MODULES && module >= 1;
  }

  // The simulation always reports a REV PDH layout and a CTRE type, whatever was initialized.
  public static boolean checkChannel(int handle, int channel) {
    return channel < Ports.REV_PDH_CHANNELS && channel >= 0;
  }

  public static int getType(int handle) {
    return CTRE_TYPE;
  }

  public static int getNumChannels(int handle) {
    return Ports.REV_PDH_CHANNELS;
  }

  public static double getTemperature(int handle) {
    PowerDistributionData d = data(handle, true);
    return d == null ? 0 : d.temperature.get();
  }

  public static double getVoltage(int handle) {
    PowerDistributionData d = data(handle, true);
    return d == null ? 0 : d.voltage.get();
  }

  public static double getVoltageNoError(int handle) {
    PowerDistributionData d = data(handle, false);
    return d == null ? 0 : d.voltage.get();
  }

  public static double getChannelCurrent(int handle, int channel) {
    PowerDistributionData d = data(handle, true);
    return d == null ? 0 : d.current[channel].get();
  }

  public static double getChannelCurrentNoError(int handle, int channel) {
    PowerDistributionData d = data(handle, false);
    return d == null ? 0 : d.current[channel].get();
  }

  public static void getAllCurrents(int handle, double[] currents) {
    PowerDistributionData d = data(handle, true);
    if (d == null) {
      return;
    }
    for (int i = 0; i < d.current.length; i++) {
      currents[i] = d.current[i].get();
    }
  }

  public static double getTotalCurrent(int handle) {
    PowerDistributionData d = data(handle, true);
    return d == null ? 0 : total(d);
  }

  public static double getTotalCurrentNoError(int handle) {
    PowerDistributionData d = data(handle, false);
    return d == null ? 0 : total(d);
  }

  public static double getTotalPower(int handle) {
    PowerDistributionData d = data(handle, true);
    return d == null ? 0 : d.voltage.get() * total(d);
  }

  public static double getTotalEnergy(int handle) {
    return 0.0;
  }

  public static void resetTotalEnergy(int handle) {}

  public static void clearStickyFaults(int handle) {}

  public static void setSwitchableChannel(int handle, boolean enabled) {}

  public static void setSwitchableChannelNoError(int handle, boolean enabled) {}

  public static boolean getSwitchableChannel(int handle) {
    return false;
  }

  public static boolean getSwitchableChannelNoError(int handle) {
    return false;
  }

  public static int getStickyFaultsNative(int handle) {
    return 0;
  }

  public static int getFaultsNative(int handle) {
    return 0;
  }

  public static PowerDistributionVersion getVersion(int handle) {
    return new PowerDistributionVersion(0, 0, 0, 0, 0, 0);
  }
}
