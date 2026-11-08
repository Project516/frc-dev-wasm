package dev.project516.frcdevwasm.sim;

/**
 * The simulated CAN bus: sim/CAN.cpp over mockdata/CanDataInternal.cpp. There is no message store.
 * Every call is delegated to the registered callbacks (a CAN simulator), and with none registered
 * sends are dropped and receives find no message.
 */
public final class CanData {
  public static final int SEND_PERIOD_NO_REPEAT = 0;
  public static final int SEND_PERIOD_STOP_REPEATING = -1;
  public static final int IS_FRAME_REMOTE = 0x80000000;
  public static final int IS_FRAME_11BIT = 0x40000000;

  /** Status code of a stream read that lost messages. */
  public static final int SESSION_OVERRUN = 44050;

  /** HAL_CANStreamMessage. */
  public static final class StreamMessage {
    public int messageId;
    public int timeStamp;
    public final byte[] data = new byte[8];
    public int dataSize;
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

  /** data is null for a stop request; status is an in/out parameter. */
  @FunctionalInterface
  public interface SendMessage {
    void callback(
        String name, int messageId, byte[] data, int dataSize, int periodMs, int[] status);
  }

  /**
   * messageId, dataSize, timeStamp and status are one element in/out arrays; data has room for 8
   * bytes.
   */
  @FunctionalInterface
  public interface ReceiveMessage {
    void callback(
        String name,
        int[] messageId,
        int messageIdMask,
        byte[] data,
        int[] dataSize,
        int[] timeStamp,
        int[] status);
  }

  @FunctionalInterface
  public interface OpenStream {
    void callback(
        String name,
        int[] sessionHandle,
        int messageId,
        int messageIdMask,
        int maxMessages,
        int[] status);
  }

  @FunctionalInterface
  public interface CloseStream {
    void callback(String name, int sessionHandle);
  }

  @FunctionalInterface
  public interface ReadStream {
    void callback(
        String name,
        int sessionHandle,
        StreamMessage[] messages,
        int messagesToRead,
        int[] messagesRead,
        int[] status);
  }

  @FunctionalInterface
  public interface GetStatus {
    void callback(String name, BusStatus status);
  }

  public static final CallbackRegistry<SendMessage> sendMessage =
      new CallbackRegistry<>("SendMessage");
  public static final CallbackRegistry<ReceiveMessage> receiveMessage =
      new CallbackRegistry<>("ReceiveMessage");
  public static final CallbackRegistry<OpenStream> openStreamSession =
      new CallbackRegistry<>("OpenStream");
  public static final CallbackRegistry<CloseStream> closeStreamSession =
      new CallbackRegistry<>("CloseStream");
  public static final CallbackRegistry<ReadStream> readStreamSession =
      new CallbackRegistry<>("ReadStream");
  public static final CallbackRegistry<GetStatus> getCanStatus =
      new CallbackRegistry<>("GetCanStatus");

  private CanData() {}

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
  public static void send(int messageId, byte[] data, int dataSize, int periodMs, int[] status) {
    sendMessage.invoke(
        cb -> cb.callback(sendMessage.name, messageId, data, dataSize, periodMs, status));
  }

  /** HAL_CAN_ReceiveMessage. A call no callback handled leaves the message not found. */
  public static void receive(
      int[] messageId,
      int messageIdMask,
      byte[] data,
      int[] dataSize,
      int[] timeStamp,
      int[] status) {
    dataSize[0] = 42;
    int before = status[0];
    receiveMessage.invoke(
        cb ->
            cb.callback(
                receiveMessage.name, messageId, messageIdMask, data, dataSize, timeStamp, status));
    if (dataSize[0] == 42 && status[0] == before) {
      status[0] = Status.ERR_CAN_MESSAGE_NOT_FOUND;
    }
  }

  /** HAL_CAN_OpenStreamSession. */
  public static void openStream(
      int[] sessionHandle, int messageId, int messageIdMask, int maxMessages, int[] status) {
    openStreamSession.invoke(
        cb ->
            cb.callback(
                openStreamSession.name,
                sessionHandle,
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
  public static void busStatus(BusStatus out) {
    getCanStatus.invoke(cb -> cb.callback(getCanStatus.name, out));
  }
}
