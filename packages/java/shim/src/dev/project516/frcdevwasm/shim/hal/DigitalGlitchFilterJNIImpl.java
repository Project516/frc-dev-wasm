package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.DigitalPorts;
import dev.project516.frcdevwasm.sim.DigitalPorts.Port;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Status;

/** Port of the glitch filter functions of sim/DIO.cpp and DigitalGlitchFilterJNI.cpp. */
public final class DigitalGlitchFilterJNIImpl {
  private DigitalGlitchFilterJNIImpl() {}

  private static Port port(int handle) {
    Port p = DigitalPorts.get(handle, Handles.DIO);
    if (p == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return p;
  }

  public static void setFilterSelect(int id, int filterIndex) {
    Port p = port(id);
    if (p != null) {
      p.filterIndex = filterIndex % 4;
    }
  }

  public static int getFilterSelect(int id) {
    Port p = port(id);
    return p == null ? 0 : p.filterIndex;
  }

  public static void setFilterPeriod(int filterIndex, int fpgaCycles) {}

  public static int getFilterPeriod(int filterIndex) {
    return 0;
  }
}
