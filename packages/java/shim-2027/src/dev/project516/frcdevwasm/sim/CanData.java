package dev.project516.frcdevwasm.sim;

/**
 * The simulated CAN buses: sim/CAN.cpp over mockdata/CanDataInternal.cpp. There is no message
 * store. Every call is delegated to the registered callbacks (a CAN simulator), and with none
 * registered sends are dropped and receives find no message.
 */
public final class CanData {
  public static final int SEND_PERIOD_NO_REPEAT = 0;
  public static final int SEND_PERIOD_STOP_REPEATING = -1;
  public static final int IS_FRAME_REMOTE = 0x40000000;
  public static final int IS_FRAME_11BIT = 0x80000000;
  public static final int FD_BITRATESWITCH = 0x1;
  public static final int FD_DATALENGTH = 0x2;
  public static final int MAX_DATA = 64;

  /** Status code of a stream read that lost messages. */
  public static final int SESSION_OVERRUN = 44050;

  /** HAL_CANMessage. */
  public static final class Message {
    public int flags;
    public int dataSize;
    public final byte[] data = new byte[MAX_DATA];
  }

  /** HAL_CANReceiveMessage. The timestamp is in nanoseconds of the HAL clock. */
  public static final class ReceiveMessage {
    public long timeStamp;
    public final Message message = new Message();
  }

  /** HAL_CANStreamMessage. */
  public static final class StreamMessage {
    public int messageId;
    public final ReceiveMessage message = new ReceiveMessage();
  }

  /** The out parameters of HAL_CAN_GetCANStatus. */
  public static final class BusStatus {
    public float percentBusUtilization;
    public int busOffCount;
    public int txFullCount;
    public int receiveErrorCount;
    public int transmitErrorCount;
    public int status;
  }

  /** status is an in/out parameter. */
  @FunctionalInterface
  public interface SendCallback {
    void callback(
        String name, int busId, int messageId, Message message, int periodMs, int[] status);
  }

  /** The callback fills message and may set status. */
  @FunctionalInterface
  public interface ReceiveCallback {
    void callback(String name, int busId, int messageId, ReceiveMessage message, int[] status);
  }

  @FunctionalInterface
  public interface OpenStreamCallback {
    void callback(
        String name,
        int[] sessionHandle,
        int busId,
        int messageId,
        int messageIdMask,
        int maxMessages,
        int[] status);
  }

  @FunctionalInterface
  public interface CloseStreamCallback {
    void callback(String name, int sessionHandle);
  }

  @FunctionalInterface
  public interface ReadStreamCallback {
    void callback(
        String name,
        int sessionHandle,
        StreamMessage[] messages,
        int messagesToRead,
        int[] messagesRead,
        int[] status);
  }

  @FunctionalInterface
  public interface GetStatusCallback {
    void callback(String name, int busId, BusStatus status);
  }

  public static final CallbackRegistry<SendCallback> sendMessage =
      new CallbackRegistry<>("SendMessage");
  public static final CallbackRegistry<ReceiveCallback> receiveMessage =
      new CallbackRegistry<>("ReceiveMessage");
  public static final CallbackRegistry<OpenStreamCallback> openStreamSession =
      new CallbackRegistry<>("OpenStream");
  public static final CallbackRegistry<CloseStreamCallback> closeStreamSession =
      new CallbackRegistry<>("CloseStream");
  public static final CallbackRegistry<ReadStreamCallback> readStreamSession =
      new CallbackRegistry<>("ReadStream");
  public static final CallbackRegistry<GetStatusCallback> getCanStatus =
      new CallbackRegistry<>("GetCanStatus");

  private CanData() {}

  /** PackCANMessage of the JNI wrappers: throws on a null array or a length that does not fit. */
  public static Message pack(byte[] data, int dataLength, int flags) {
    if (data == null) {
      throw new NullPointerException("data array cannot be null");
    }
    if (data.length < dataLength) {
      throw new IllegalArgumentException("array length less than data length");
    }
    return packChecked(data, dataLength, flags);
  }

  /** Like {@link #pack}, but remote frames may have a null array. */
  public static Message packRtr(byte[] data, int dataLength, int flags) {
    return data == null ? packChecked(null, dataLength, flags) : pack(data, dataLength, flags);
  }

  private static Message packChecked(byte[] data, int dataLength, int flags) {
    if ((flags & FD_DATALENGTH) != 0 && dataLength > 64) {
      throw new IllegalArgumentException("FD frame has max length of 64 bytes");
    } else if ((flags & FD_DATALENGTH) == 0 && dataLength > 8) {
      throw new IllegalArgumentException("Non FD frame has max length of 8 bytes");
    }
    Message m = new Message();
    m.dataSize = dataLength;
    m.flags = flags;
    if (data != null) {
      System.arraycopy(data, 0, m.data, 0, dataLength);
    }
    return m;
  }

  /** Drops all callbacks, as HALSIM_ResetCanData. */
  public static void reset() {
    sendMessage.reset();
    receiveMessage.reset();
    openStreamSession.reset();
    closeStreamSession.reset();
    readStreamSession.reset();
    getCanStatus.reset();
  }

  /** HAL_CAN_SendMessage. */
  public static void send(int busId, int messageId, Message message, int periodMs, int[] status) {
    sendMessage.invoke(
        cb -> cb.callback(sendMessage.name, busId, messageId, message, periodMs, status));
  }

  /** HAL_CAN_ReceiveMessage. A call no callback handled leaves the message not found. */
  public static void receive(int busId, int messageId, ReceiveMessage message, int[] status) {
    message.message.dataSize = 100;
    int before = status[0];
    receiveMessage.invoke(
        cb -> cb.callback(receiveMessage.name, busId, messageId, message, status));
    if (message.message.dataSize == 100 && status[0] == before) {
      status[0] = Status.ERR_CAN_MESSAGE_NOT_FOUND;
    }
  }

  /** HAL_CAN_OpenStreamSession. */
  public static void openStream(
      int[] sessionHandle,
      int busId,
      int messageId,
      int messageIdMask,
      int maxMessages,
      int[] status) {
    openStreamSession.invoke(
        cb ->
            cb.callback(
                openStreamSession.name,
                sessionHandle,
                busId,
                messageId,
                messageIdMask,
                maxMessages,
                status));
  }

  /** HAL_CAN_CloseStreamSession. */
  public static void closeStream(int sessionHandle) {
    closeStreamSession.invoke(cb -> cb.callback(closeStreamSession.name, sessionHandle));
  }

  /** HAL_CAN_ReadStreamSession. */
  public static void readStream(
      int sessionHandle,
      StreamMessage[] messages,
      int messagesToRead,
      int[] messagesRead,
      int[] status) {
    readStreamSession.invoke(
        cb ->
            cb.callback(
                readStreamSession.name,
                sessionHandle,
                messages,
                messagesToRead,
                messagesRead,
                status));
  }

  /** HAL_CAN_GetCANStatus. */
  public static void busStatus(int busId, BusStatus out) {
    getCanStatus.invoke(cb -> cb.callback(getCanStatus.name, busId, out));
  }
}
