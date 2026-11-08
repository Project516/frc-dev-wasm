package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.AddressableLedData;
import dev.project516.frcdevwasm.sim.DigitalPorts;
import dev.project516.frcdevwasm.sim.DigitalPorts.Port;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/** Port of hal/src/main/native/sim/AddressableLED.cpp and the AddressableLEDJNI wrappers. */
public final class AddressableLEDJNIImpl {
  private static final Handles.Limited<Integer> LEDS =
      new Handles.Limited<>(Handles.ADDRESSABLE_LED, Ports.ADDRESSABLE_LEDS);

  private AddressableLEDJNIImpl() {}

  private static AddressableLedData led(int handle) {
    if (LEDS.get(handle) == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
      return null;
    }
    return AddressableLedData.get(Handles.index(handle, Handles.ADDRESSABLE_LED));
  }

  public static int initialize(int handle) {
    Sim.ensureInitialized();
    Port port = DigitalPorts.get(handle, Handles.PWM);
    if (port == null) {
      // A DIO handle is a channel error, anything else a generic one.
      Status.check(
          Handles.type(handle) == Handles.DIO
              ? Status.HAL_LED_CHANNEL_ERROR
              : Status.HAL_HANDLE_ERROR);
      return 0;
    }
    if (port.channel >= Ports.PWM_HEADERS) {
      Status.check(Status.HAL_LED_CHANNEL_ERROR);
      return 0;
    }
    int ledHandle = LEDS.allocate(0);
    if (ledHandle == Handles.INVALID) {
      Status.check(Status.NO_AVAILABLE_RESOURCES);
      return 0;
    }
    AddressableLedData data = AddressableLedData.get(Handles.index(ledHandle, Handles.ADDRESSABLE_LED));
    data.outputPort.set(port.channel);
    data.length.set(1);
    data.running.set(false);
    data.initialized.set(true);
    return ledHandle;
  }

  public static void free(int handle) {
    if (handle == Handles.INVALID) {
      return;
    }
    if (LEDS.free(handle) != null) {
      AddressableLedData data = AddressableLedData.get(Handles.index(handle, Handles.ADDRESSABLE_LED));
      data.running.set(false);
      data.initialized.set(false);
    }
  }

  public static void setColorOrder(int handle, int colorOrder) {}

  public static void setBitTiming(
      int handle, int highTime0, int lowTime0, int highTime1, int lowTime1) {}

  public static void setSyncTime(int handle, int syncTime) {}

  public static void setLength(int handle, int length) {
    AddressableLedData data = led(handle);
    if (data == null) {
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
    data.length.set(length);
  }

  public static void setData(int handle, byte[] arr) {
    AddressableLedData data = led(handle);
    if (data == null) {
      return;
    }
    int length = arr.length / 4;
    if (length > data.length.get()) {
      Status.check(
          Status.setLastError(
              Status.PARAMETER_OUT_OF_RANGE,
              "Data length must be less than or equal to "
                  + data.length.get()
                  + ". "
                  + length
                  + " was requested"));
      return;
    }
    data.setData(arr, length);
  }

  public static void start(int handle) {
    AddressableLedData data = led(handle);
    if (data != null) {
      data.running.set(true);
    }
  }

  public static void stop(int handle) {
    AddressableLedData data = led(handle);
    if (data != null) {
      data.running.set(false);
    }
  }
}
