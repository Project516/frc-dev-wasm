package dev.project516.frcdevwasm.shim.hardware.hal.can;

import dev.project516.frcdevwasm.sim.CanData;
import dev.project516.frcdevwasm.sim.CanData.StreamMessage;
import dev.project516.frcdevwasm.sim.Status;
import org.wpilib.hardware.hal.can.CANReceiveMessage;
import org.wpilib.hardware.hal.can.CANStatus;
import org.wpilib.hardware.hal.can.CANStreamMessage;
import org.wpilib.hardware.hal.can.CANStreamOverflowException;

/** Port of CANJNI.cpp over sim/CAN.cpp. */
public final class CANJNIImpl {
  private CANJNIImpl() {}

  public static int sendMessage(
      int busId, int messageId, byte[] data, int dataLength, int flags, int periodMs) {
    CanData.Message message = CanData.pack(data, dataLength, flags);
    int[] status = {0};
    CanData.send(busId, messageId, message, periodMs, status);
    return status[0];
  }

  public static int receiveMessage(int busId, int messageId, CANReceiveMessage message) {
    CanData.ReceiveMessage received = new CanData.ReceiveMessage();
    int[] status = {0};
    CanData.receive(busId, messageId, received, status);
    fill(message, received);
    return status[0];
  }

  /** SetCANReceiveMessageObject and the copy of the bytes. */
  public static void fill(CANReceiveMessage target, CanData.ReceiveMessage source) {
    CanData.Message m = source.message;
    byte[] data = target.setReceiveData(m.dataSize, m.flags, source.timeStamp);
    if (data.length < m.dataSize) {
      throw new IllegalArgumentException("Message buffer not long enough for message");
    }
    System.arraycopy(m.data, 0, data, 0, m.dataSize);
  }

  public static void getCANStatus(int busId, CANStatus canStatus) {
    CanData.BusStatus s = new CanData.BusStatus();
    CanData.busStatus(busId, s);
    if (!Status.check(s.status)) {
      return;
    }
    canStatus.setStatus(
        s.percentBusUtilization,
        s.busOffCount,
        s.txFullCount,
        s.receiveErrorCount,
        s.transmitErrorCount);
  }

  public static int openCANStreamSession(
      int busId, int messageId, int messageIDMask, int maxMessages) {
    int[] handle = {0};
    int[] status = {0};
    CanData.openStream(handle, busId, messageId, messageIDMask, maxMessages, status);
    if (!Status.check(status[0])) {
      return 0;
    }
    return handle[0];
  }

  public static void closeCANStreamSession(int sessionHandle) {
    CanData.closeStream(sessionHandle);
  }

  public static int readCANStreamSession(
      int sessionHandle, CANStreamMessage[] messages, int messagesToRead)
      throws CANStreamOverflowException {
    if (messages == null) {
      throw new NullPointerException("messages cannot be null");
    }
    int count = Math.max(0, Math.min(messagesToRead, messages.length));
    StreamMessage[] buffer = new StreamMessage[count];
    for (int i = 0; i < count; i++) {
      buffer[i] = new StreamMessage();
    }
    int[] read = {0};
    int[] status = {0};
    CanData.readStream(sessionHandle, buffer, count, read, status);

    if (status[0] == Status.ERR_CAN_MESSAGE_NOT_FOUND || read[0] == 0) {
      return 0;
    }
    boolean overrun = status[0] == CanData.SESSION_OVERRUN;
    if (!overrun && !Status.check(status[0])) {
      return 0;
    }
    for (int i = 0; i < Math.min(read[0], count); i++) {
      CanData.Message msg = buffer[i].message.message;
      if (messages[i] == null) {
        messages[i] = new CANStreamMessage();
      }
      // Upstream passes the flags as the length and the size as the flags.
      byte[] target =
          messages[i].setStreamData(
              msg.flags, msg.dataSize, buffer[i].messageId, buffer[i].message.timeStamp);
      if (target.length < msg.dataSize) {
        throw new IllegalArgumentException("Message buffer not long enough for message");
      }
      System.arraycopy(msg.data, 0, target, 0, msg.dataSize);
    }
    if (overrun) {
      throw new CANStreamOverflowException(messages, read[0]);
    }
    return read[0];
  }
}
