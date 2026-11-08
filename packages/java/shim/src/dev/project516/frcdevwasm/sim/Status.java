package dev.project516.frcdevwasm.sim;

import edu.wpi.first.hal.can.CANInvalidBufferException;
import edu.wpi.first.hal.can.CANMessageNotAllowedException;
import edu.wpi.first.hal.can.CANMessageNotFoundException;
import edu.wpi.first.hal.can.CANNotInitializedException;
import edu.wpi.first.hal.util.AllocationException;
import edu.wpi.first.hal.util.HalHandleException;
import edu.wpi.first.hal.util.UncleanStatusException;

/**
 * HAL status codes and the JNI-side error handling of HALUtil.cpp. HAL style functions return a
 * status (0 ok, negative error) and callers pass it to {@link #check} or {@link #checkRange}.
 */
public final class Status {
  public static final int SUCCESS = 0;
  public static final int SAMPLE_RATE_TOO_HIGH = 1001;
  public static final int VOLTAGE_OUT_OF_RANGE = 1002;
  public static final int LOOP_TIMING_ERROR = 1004;
  public static final int SPI_WRITE_NO_MOSI = 1012;
  public static final int SPI_READ_NO_MISO = 1013;
  public static final int SPI_READ_NO_DATA = 1014;
  public static final int INCOMPATIBLE_STATE = 1015;
  public static final int NO_AVAILABLE_RESOURCES = -1004;
  public static final int NULL_PARAMETER = -1005;
  public static final int ANALOG_TRIGGER_LIMIT_ORDER_ERROR = -1010;
  public static final int ANALOG_TRIGGER_PULSE_OUTPUT_ERROR = -1011;
  public static final int PARAMETER_OUT_OF_RANGE = -1028;
  public static final int RESOURCE_IS_ALLOCATED = -1029;
  public static final int RESOURCE_OUT_OF_RANGE = -1030;
  public static final int HAL_INVALID_ACCUMULATOR_CHANNEL = -1035;
  public static final int HAL_COUNTER_NOT_SUPPORTED = -1058;
  public static final int HAL_PWM_SCALE_ERROR = -1072;
  public static final int HAL_HANDLE_ERROR = -1098;
  public static final int HAL_LED_CHANNEL_ERROR = -1099;
  public static final int HAL_INVALID_DMA_ADDITION = -1102;
  public static final int HAL_INVALID_DMA_STATE = -1103;
  public static final int HAL_SERIAL_PORT_NOT_FOUND = -1123;
  public static final int HAL_SERIAL_PORT_OPEN_ERROR = -1124;
  public static final int HAL_SERIAL_PORT_ERROR = -1125;
  public static final int HAL_THREAD_PRIORITY_ERROR = -1152;
  public static final int HAL_THREAD_PRIORITY_RANGE_ERROR = -1153;
  public static final int HAL_CAN_TIMEOUT = -1154;
  public static final int HAL_SIM_NOT_SUPPORTED = -1155;
  public static final int HAL_USE_LAST_ERROR = -1156;
  public static final int HAL_CONSOLE_OUT_ENABLED_ERROR = -1157;
  public static final int HAL_CAN_BUFFER_OVERRUN = -35007;
  public static final int ERR_CAN_INVALID_BUFFER = -44086;
  public static final int ERR_CAN_MESSAGE_NOT_FOUND = -44087;
  public static final int WARN_CAN_NO_TOKEN = 44087;
  public static final int ERR_CAN_NOT_ALLOWED = -44088;
  public static final int ERR_CAN_NOT_INITIALIZED = -44089;

  private static final int RIO_STATUS_OFFSET = -63000;
  private static final int RIO_BUFFER_INVALID_SIZE = RIO_STATUS_OFFSET - 80;
  private static final int RIO_OPERATION_TIMED_OUT = -52007;
  private static final int RIO_FEATURE_NOT_SUPPORTED = RIO_STATUS_OFFSET - 193;
  private static final int RIO_RESOURCE_NOT_INITIALIZED = -52010;

  private record LastError(int status, String message) {}

  private static final ThreadLocal<LastError> LAST = new ThreadLocal<>();

  private Status() {}

  /** Message for a status code, as HAL_GetErrorMessage. */
  public static String message(int code) {
    return switch (code) {
      case 0 -> "";
      case SAMPLE_RATE_TOO_HIGH -> "HAL: Analog module sample rate is too high";
      case VOLTAGE_OUT_OF_RANGE -> "HAL: Voltage to convert to raw value is out of range [0; 5]";
      case LOOP_TIMING_ERROR ->
          "HAL: Digital module loop timing is not the expected value";
      case SPI_WRITE_NO_MOSI -> "HAL: Cannot write to SPI port with no MOSI output";
      case SPI_READ_NO_MISO -> "HAL: Cannot read from SPI port with no MISO input";
      case SPI_READ_NO_DATA -> "HAL: No data available to read from SPI";
      case INCOMPATIBLE_STATE -> "HAL: Incompatible State: The operation cannot be completed";
      case NO_AVAILABLE_RESOURCES -> "HAL: No available resources to allocate";
      case NULL_PARAMETER -> "HAL: A pointer parameter to a method is NULL";
      case ANALOG_TRIGGER_LIMIT_ORDER_ERROR ->
          "HAL: AnalogTrigger limits error.  Lower limit > Upper Limit";
      case ANALOG_TRIGGER_PULSE_OUTPUT_ERROR ->
          "HAL: Attempted to read AnalogTrigger pulse output.";
      case PARAMETER_OUT_OF_RANGE -> "HAL: A parameter is out of range.";
      case RESOURCE_IS_ALLOCATED -> "HAL: Resource already allocated";
      case RESOURCE_OUT_OF_RANGE -> "HAL: The requested resource is out of range.";
      case HAL_INVALID_ACCUMULATOR_CHANNEL ->
          "HAL: The requested input is not an accumulator channel";
      case HAL_COUNTER_NOT_SUPPORTED -> "HAL: Counter mode not supported for encoder method";
      case HAL_PWM_SCALE_ERROR -> "HAL: The PWM Scale Factors are out of range";
      case HAL_HANDLE_ERROR -> "HAL: A handle parameter was passed incorrectly";
      case HAL_LED_CHANNEL_ERROR -> "HAL: Addressable LEDs only supported on PWM Headers, not MXP or DIO";
      case HAL_INVALID_DMA_ADDITION -> "HAL_AddDMA() only works before HAL_StartDMA()";
      case HAL_INVALID_DMA_STATE -> "HAL_SetPause() only works before HAL_StartDMA()";
      case HAL_SERIAL_PORT_NOT_FOUND -> "HAL: The specified serial port device was not found";
      case HAL_SERIAL_PORT_OPEN_ERROR -> "HAL: The serial port could not be opened";
      case HAL_SERIAL_PORT_ERROR -> "HAL: There was an error on the serial port";
      case HAL_THREAD_PRIORITY_ERROR -> "HAL: Getting or setting the priority of a thread has failed";
      case HAL_THREAD_PRIORITY_RANGE_ERROR ->
          "HAL: The priority requested to be set is invalid";
      case HAL_CAN_TIMEOUT -> "HAL: CAN Receive has Timed Out";
      case HAL_SIM_NOT_SUPPORTED -> "HAL: Method not supported in sim";
      case HAL_CAN_BUFFER_OVERRUN -> "HAL: CAN Output Buffer Full. Ensure a device is attached";
      case HAL_USE_LAST_ERROR ->
          "HAL: Use HAL_GetLastError(status) to get last error";
      case HAL_CONSOLE_OUT_ENABLED_ERROR ->
          "HAL: Onboard serial port is requested, but Console Out is enabled. Disable Console Out using imaging tool";
      case ERR_CAN_INVALID_BUFFER -> "CAN: Invalid Buffer";
      case ERR_CAN_MESSAGE_NOT_FOUND -> "CAN: Message not found";
      case WARN_CAN_NO_TOKEN -> "CAN: No token";
      case ERR_CAN_NOT_ALLOWED -> "CAN: Not allowed";
      case ERR_CAN_NOT_INITIALIZED -> "CAN: Not initialized";
      default -> "Unknown error status";
    };
  }

  /** Records a detailed error for the current thread. Returns HAL_USE_LAST_ERROR. */
  public static int setLastError(int status, String message) {
    LAST.set(new LastError(status, message));
    return HAL_USE_LAST_ERROR;
  }

  public static int indexOutOfRange(
      int status, String message, int minimum, int maximum, int requested) {
    return setLastError(
        status,
        message
            + "\n Status: "
            + status
            + "\n  Minimum: "
            + minimum
            + " Maximum: "
            + maximum
            + " Requested: "
            + requested);
  }

  public static int previouslyAllocated(
      int status, String message, int channel, String previousAllocation) {
    return setLastError(
        status,
        message
            + " "
            + channel
            + " previously allocated.\nLocation of the previous allocation:\n"
            + previousAllocation
            + "\nLocation of the current allocation:");
  }

  /** Status after resolving HAL_USE_LAST_ERROR. */
  private static LastError resolve(int status) {
    if (status == HAL_USE_LAST_ERROR) {
      LastError l = LAST.get();
      if (l != null) {
        return l;
      }
    }
    return new LastError(status, message(status));
  }

  /** Message for a status, resolving HAL_USE_LAST_ERROR. */
  public static String lastError(int status) {
    return resolve(status).message;
  }

  /** CheckStatus(status, doThrow=true). Returns true if status is 0. */
  public static boolean check(int status) {
    if (status != 0) {
      reportError(status, true);
    }
    return status == 0;
  }

  public static boolean check(int status, boolean doThrow) {
    if (status != 0) {
      reportError(status, doThrow);
    }
    return status == 0;
  }

  /** CheckStatusRange: allocation style errors throw AllocationException. */
  public static boolean checkRange(int status, int min, int max, int requested) {
    if (status != 0) {
      throwError(status);
    }
    return status == 0;
  }

  /** CheckStatusForceThrow. */
  public static boolean checkForceThrow(int status) {
    if (status != 0) {
      throwError(status);
    }
    return status == 0;
  }

  public static void reportError(int status, boolean doThrow) {
    if (status == 0) {
      return;
    }
    LastError l = resolve(status);
    if (l.status == HAL_HANDLE_ERROR) {
      throw new HalHandleException(" Code: " + l.status + ". " + l.message);
    }
    if (doThrow && l.status < 0) {
      throw new UncleanStatusException(l.status, " Code: " + l.status + ". " + l.message);
    }
    DsErrors.send(true, l.status, false, l.message, location(), stack(), true);
  }

  private static void throwError(int status) {
    LastError l = resolve(status);
    if (l.status == NO_AVAILABLE_RESOURCES
        || l.status == RESOURCE_IS_ALLOCATED
        || l.status == RESOURCE_OUT_OF_RANGE) {
      throw new AllocationException("Code: " + l.status + "\n" + l.message);
    }
    if (l.status == HAL_HANDLE_ERROR) {
      throw new HalHandleException(" Code: " + l.status + ". " + l.message);
    }
    throw new UncleanStatusException(l.status, " Code: " + l.status + ". " + l.message);
  }

  /** CheckCANStatus. */
  public static boolean checkCan(int status, int messageId) {
    if (status >= 0) {
      return status == 0;
    }
    switch (status) {
      case ERR_CAN_INVALID_BUFFER, RIO_BUFFER_INVALID_SIZE -> throw new CANInvalidBufferException();
      case ERR_CAN_MESSAGE_NOT_FOUND, RIO_OPERATION_TIMED_OUT ->
          throw new CANMessageNotFoundException();
      case ERR_CAN_NOT_ALLOWED, RIO_FEATURE_NOT_SUPPORTED ->
          throw new CANMessageNotAllowedException("MessageID = " + messageId);
      case ERR_CAN_NOT_INITIALIZED, RIO_RESOURCE_NOT_INITIALIZED ->
          throw new CANNotInitializedException();
      default -> throw new UncleanStatusException("Fatal status code detected: " + status);
    }
  }

  /** Java stack of the caller without WPILib and shim frames, for allocation tracking. */
  public static String allocationLocation() {
    StringBuilder sb = new StringBuilder();
    for (StackTraceElement e : new Throwable().getStackTrace()) {
      String c = e.getClassName();
      if (!c.startsWith("edu.wpi.first") && !c.startsWith("dev.project516.frcdevwasm")) {
        sb.append("\tat ").append(e).append('\n');
      }
    }
    return sb.toString();
  }

  private static String location() {
    for (StackTraceElement e : new Throwable().getStackTrace()) {
      if (e.getClassName().startsWith("edu.wpi.first") && !e.getClassName().contains("JNI")) {
        return e.getClassName() + "." + e.getMethodName();
      }
    }
    return "";
  }

  private static String stack() {
    StringBuilder sb = new StringBuilder();
    for (StackTraceElement e : new Throwable().getStackTrace()) {
      if (!e.getClassName().startsWith("frcdevwasm.")) {
        sb.append("\tat ").append(e).append('\n');
      }
    }
    return sb.toString();
  }
}
