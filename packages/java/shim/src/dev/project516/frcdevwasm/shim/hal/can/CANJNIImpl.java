package dev.project516.frcdevwasm.shim.hal.can;

import dev.project516.frcdevwasm.sim.Buffers;
import dev.project516.frcdevwasm.sim.CanData;
import dev.project516.frcdevwasm.sim.CanData.StreamMessage;
import dev.project516.frcdevwasm.sim.Status;
import edu.wpi.first.hal.CANStreamMessage;
import edu.wpi.first.hal.can.CANStatus;
import edu.wpi.first.hal.can.CANStreamOverflowException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/** Port of CANJNI.cpp over sim/CAN.cpp. */
public final class CANJNIImpl {
  private CANJNIImpl() {}

  public static void FRCNetCommCANSessionMuxSendMessage(int messageID, byte[] data, int periodMs) {
    int size = data == null ? 0 : data.length;
    int[] status = {0};
    CanData.send(messageID, data, size & 0xff, periodMs, status);
    Status.checkCan(status[0], messageID);
  }

  public static byte[] FRCNetCommCANSessionMuxReceiveMessage(
      IntBuffer messageID, int messageIDMask, ByteBuffer timeStamp) {
    int[] id = {messageID.get(0)};
    int[] size = {0};
    int[] ts = Buffers.ints(timeStamp, 1);
    int[] status = {0};
    byte[] buffer = new byte[8];
    CanData.receive(id, messageIDMask, buffer, size, ts, status);
    messageID.put(0, id[0]);
    Buffers.putInts(timeStamp, ts, 1);
    if (!Status.checkCan(status[0], id[0])) {
      return null;
    }
    return Arrays.copyOf(buffer, size[0] & 0xff);
  }

  public static void getCANStatus(CANStatus canStatus) {
    CanData.BusStatus s = new CanData.BusStatus();
    CanData.busStatus(s);
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

  public static int openCANStreamSession(int messageID, int messageIDMask, int maxMessages) {
    int[] handle = {0};
    int[] status = {0};
    CanData.openStream(handle, messageID, messageIDMask, maxMessages, status);
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
    int filled = Math.min(read[0], count);
    for (int i = 0; i < filled; i++) {
      StreamMessage msg = buffer[i];
      if (messages[i] == null) {
        messages[i] = new CANStreamMessage();
      }
      byte[] target =
          messages[i].setStreamData(msg.dataSize, msg.messageId, msg.timeStamp & 0xFFFFFFFFL);
      System.arraycopy(msg.data, 0, target, 0, Math.min(Math.min(target.length, 8), msg.dataSize));
    }
    if (overrun) {
      throw new CANStreamOverflowException(messages, read[0]);
    }
    return read[0];
  }
}
