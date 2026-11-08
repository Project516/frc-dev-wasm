package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.AddressableLedData;
import dev.project516.frcdevwasm.sim.Buffers;
import dev.project516.frcdevwasm.sim.DigitalPorts;
import dev.project516.frcdevwasm.sim.DigitalPorts.Port;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Port of hal/src/main/native/sim/AddressableLED.cpp and the AddressableLEDJNI wrappers. */
public final class AddressableLEDJNIImpl {
  private AddressableLEDJNIImpl() {}

  private static Port port(int handle) {
    Port p = DigitalPorts.get(handle, Handles.ADDRESSABLE_LED);
    if (p == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return p;
  }

  public static int initialize(int channel) {
    Sim.ensureInitialized();
    if (channel < 0 || channel >= Ports.ADDRESSABLE_LEDS) {
      Status.indexOutOfRange(
          Status.RESOURCE_OUT_OF_RANGE,
          "Invalid Index for AddressableLED",
          0,
          Ports.ADDRESSABLE_LEDS - 1,
          channel);
      Status.checkForceThrow(Status.HAL_USE_LAST_ERROR);
      return 0;
    }
    DigitalPorts.Allocation a =
        DigitalPorts.allocate(channel, Handles.ADDRESSABLE_LED, "AddressableLED");
    if (a.status != 0) {
      Status.checkForceThrow(a.status);
      return 0;
    }
    a.port.channel = channel;
    AddressableLedData data = AddressableLedData.get(channel);
    data.start.set(0);
    data.length.set(0);
    data.initialized.set(true);
    a.port.previousAllocation = Status.allocationLocation();
    return a.handle;
  }

  public static void free(int handle) {
    Port p = DigitalPorts.get(handle, Handles.ADDRESSABLE_LED);
    DigitalPorts.free(handle, Handles.ADDRESSABLE_LED);
    if (p != null) {
      AddressableLedData.get(p.channel).initialized.set(false);
    }
  }

  public static void setStart(int handle, int start) {
    Port p = port(handle);
    if (p == null) {
      return;
    }
    if (start > AddressableLedData.MAX_LENGTH || start < 0) {
      Status.check(
          Status.setLastError(
              Status.PARAMETER_OUT_OF_RANGE,
              "LED start must be less than or equal to "
                  + AddressableLedData.MAX_LENGTH
                  + ". "
                  + start
                  + " was requested"));
      return;
    }
    AddressableLedData.get(p.channel).start.set(start);
  }

  public static void setLength(int handle, int length) {
    Port p = port(handle);
    if (p == null) {
      return;
    }
    if (length > AddressableLedData.MAX_LENGTH || length < 0) {
      Status.check(
          Status.setLastError(
              Status.PARAMETER_OUT_OF_RANGE,
              "LED length must be less than or equal to "
                  + AddressableLedData.MAX_LENGTH
                  + ". "
                  + length
                  + " was requested"));
      return;
    }
    AddressableLedData.get(p.channel).length.set(length);
  }

  /** The sim stores colors as given; the color order is not applied. */
  public static void setData(int outStart, int colorOrder, byte[] data, int start, int len) {
    if (data == null) {
      throw new NullPointerException("data is null");
    }
    checkRange(start, len, data.length);
    AddressableLedData.setData(outStart, len / 3, Arrays.copyOfRange(data, start, start + len));
  }

  public static void setDataFromBuffer(
      int outStart, int colorOrder, ByteBuffer data, int start, int len) {
    if (data == null) {
      throw new NullPointerException("data is null");
    }
    checkRange(start, len, start + len);
    byte[] bytes = Buffers.bytes(data, start + len);
    AddressableLedData.setData(outStart, len / 3, Arrays.copyOfRange(bytes, start, start + len));
  }

  private static void checkRange(int start, int len, int available) {
    if (start < 0) {
      throw new IndexOutOfBoundsException("start must be >= 0");
    }
    if (len < 0) {
      throw new IndexOutOfBoundsException("len must be >= 0");
    }
    if (start + len > available) {
      throw new IndexOutOfBoundsException("start + len must be smaller than array length");
    }
    if (len % 3 != 0) {
      throw new IndexOutOfBoundsException("len must be a multiple of 3");
    }
  }
}
