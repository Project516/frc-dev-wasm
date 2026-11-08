package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Sim;
import java.util.Arrays;

/** Port of sim/SerialPort.cpp and SerialPortJNI.cpp. The sim has no serial ports. */
public final class SerialPortJNIImpl {
  private SerialPortJNIImpl() {}

  public static int serialInitializePort(byte port) {
    Sim.ensureInitialized();
    return Handles.INVALID;
  }

  public static int serialInitializePortDirect(byte port, String portName) {
    Sim.ensureInitialized();
    return Handles.INVALID;
  }

  public static void serialSetBaudRate(int handle, int rate) {}

  public static void serialSetDataBits(int handle, byte bits) {}

  public static void serialSetParity(int handle, byte parity) {}

  public static void serialSetStopBits(int handle, byte bits) {}

  public static void serialSetWriteMode(int handle, byte mode) {}

  public static void serialSetFlowControl(int handle, byte flow) {}

  public static void serialSetTimeout(int handle, double timeout) {}

  public static void serialEnableTermination(int handle, char terminator) {}

  public static void serialDisableTermination(int handle) {}

  public static void serialSetReadBufferSize(int handle, int size) {}

  public static void serialSetWriteBufferSize(int handle, int size) {}

  public static int serialGetBytesReceived(int handle) {
    return 0;
  }

  public static int serialRead(int handle, byte[] dataReceived, int size) {
    Arrays.fill(dataReceived, 0, size, (byte) 0);
    return 0;
  }

  public static int serialWrite(int handle, byte[] dataToSend, int size) {
    return 0;
  }

  public static void serialFlush(int handle) {}

  public static void serialClear(int handle) {}

  public static void serialClose(int handle) {}
}
