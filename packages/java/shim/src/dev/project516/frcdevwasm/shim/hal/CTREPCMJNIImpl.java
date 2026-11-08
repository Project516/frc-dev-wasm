package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.CTREPCMData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/** Port of hal/src/main/native/sim/CTREPCM.cpp and the CTREPCMJNI wrappers. */
public final class CTREPCMJNIImpl {
  private static final class Pcm {
    final int module;
    final String previousAllocation;

    Pcm(int module, String previousAllocation) {
      this.module = module;
      this.previousAllocation = previousAllocation;
    }
  }

  private static final Handles.Limited<Pcm> HANDLES =
      new Handles.Limited<>(Handles.CTRE_PCM, Ports.CTRE_PCM_MODULES);

  private CTREPCMJNIImpl() {}

  /** The PCM behind a handle, or null after reporting a handle error. */
  private static Pcm pcm(int handle) {
    Pcm pcm = HANDLES.get(handle);
    if (pcm == null) {
      Status.check(Status.HAL_HANDLE_ERROR, false);
    }
    return pcm;
  }

  private static CTREPCMData data(int handle) {
    Pcm pcm = pcm(handle);
    return pcm == null ? null : CTREPCMData.get(pcm.module);
  }

  public static int initialize(int module) {
    Sim.ensureInitialized();
    if (module < 0 || module >= Ports.CTRE_PCM_MODULES) {
      Status.indexOutOfRange(
          Status.RESOURCE_OUT_OF_RANGE,
          "Invalid Index for CTRE PCM",
          0,
          Ports.CTRE_PCM_MODULES - 1,
          module);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    Pcm existing = HANDLES.get(Handles.make(module, Handles.CTRE_PCM));
    if (existing != null) {
      Status.previouslyAllocated(
          Status.RESOURCE_IS_ALLOCATED, "CTRE PCM", module, existing.previousAllocation);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    int handle = HANDLES.allocate(module, new Pcm(module, Status.allocationLocation()));
    CTREPCMData d = CTREPCMData.get(module);
    d.initialized.set(true);
    d.closedLoopEnabled.set(true);
    return handle;
  }

  public static void free(int handle) {
    if (handle == Handles.INVALID) {
      return;
    }
    Pcm pcm = HANDLES.get(handle);
    if (pcm != null) {
      CTREPCMData.get(pcm.module).initialized.set(false);
    }
    HANDLES.free(handle);
  }

  public static boolean checkSolenoidChannel(int channel) {
    return channel < Ports.CTRE_SOLENOID_CHANNELS && channel >= 0;
  }

  public static boolean getCompressor(int handle) {
    CTREPCMData d = data(handle);
    return d != null && d.compressorOn.get();
  }

  public static void setClosedLoopControl(int handle, boolean enabled) {
    CTREPCMData d = data(handle);
    if (d != null) {
      d.closedLoopEnabled.set(enabled);
    }
  }

  public static boolean getClosedLoopControl(int handle) {
    CTREPCMData d = data(handle);
    return d != null && d.closedLoopEnabled.get();
  }

  public static boolean getPressureSwitch(int handle) {
    CTREPCMData d = data(handle);
    return d != null && d.pressureSwitch.get();
  }

  public static double getCompressorCurrent(int handle) {
    CTREPCMData d = data(handle);
    return d == null ? 0 : d.compressorCurrent.get();
  }

  public static int getSolenoids(int handle) {
    Pcm pcm = pcm(handle);
    if (pcm == null) {
      return 0;
    }
    synchronized (pcm) {
      return CTREPCMData.get(pcm.module).solenoidMask();
    }
  }

  public static void setSolenoids(int handle, int mask, int values) {
    Pcm pcm = pcm(handle);
    if (pcm == null) {
      return;
    }
    CTREPCMData d = CTREPCMData.get(pcm.module);
    synchronized (pcm) {
      for (int i = 0; i < Ports.CTRE_SOLENOID_CHANNELS; i++) {
        int indexMask = 1 << i;
        if ((mask & indexMask) != 0) {
          d.solenoidOutput[i].set((values & indexMask) != 0);
        }
      }
    }
  }

  public static boolean getCompressorCurrentTooHighFault(int handle) {
    return false;
  }

  public static boolean getCompressorCurrentTooHighStickyFault(int handle) {
    return false;
  }

  public static boolean getCompressorShortedFault(int handle) {
    return false;
  }

  public static boolean getCompressorShortedStickyFault(int handle) {
    return false;
  }

  public static boolean getCompressorNotConnectedFault(int handle) {
    return false;
  }

  public static boolean getCompressorNotConnectedStickyFault(int handle) {
    return false;
  }

  public static boolean getSolenoidVoltageFault(int handle) {
    return false;
  }

  public static boolean getSolenoidVoltageStickyFault(int handle) {
    return false;
  }

  public static int getSolenoidDisabledList(int handle) {
    return 0;
  }

  public static void clearAllStickyFaults(int handle) {}

  public static void fireOneShot(int handle, int index) {}

  public static void setOneShotDuration(int handle, int index, int durMs) {}
}
