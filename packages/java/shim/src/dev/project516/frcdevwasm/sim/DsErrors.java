package dev.project516.frcdevwasm.sim;

/** HAL_SendError and HAL_SendConsoleLine of the sim DriverStation. */
public final class DsErrors {
  private static final int KEEP = 5;
  private static final long REPEAT_MICROS = 1_000_000;

  private static final String[] PREV_MSG = new String[KEEP];
  private static final long[] PREV_TIME = new long[KEEP];
  private static volatile boolean sendErrors = true;
  private static volatile boolean sendConsole = true;

  private DsErrors() {}

  /** DriverStationDataJNI.setSendError: false silences HAL_SendError. */
  public static void setSendErrors(boolean send) {
    sendErrors = send;
  }

  public static void setSendConsoleLine(boolean send) {
    sendConsole = send;
  }

  public static int send(
      boolean isError,
      int errorCode,
      boolean isLVCode,
      String details,
      String location,
      String callStack,
      boolean printMsg) {
    if (!sendErrors) {
      return 0;
    }
    // Repeats of the last few messages are printed at most once a second.
    synchronized (PREV_MSG) {
      long now = SimTime.nowDefault();
      int i;
      for (i = 0; i < KEEP; i++) {
        if (details.equals(PREV_MSG[i])) {
          break;
        }
      }
      if (i == KEEP || now - PREV_TIME[i] >= REPEAT_MICROS) {
        StringBuilder sb = new StringBuilder();
        if (location != null && !location.isEmpty()) {
          sb.append(isError ? "Error" : "Warning").append(" at ").append(location).append(": ");
        }
        sb.append(details).append('\n');
        if (callStack != null && !callStack.isEmpty()) {
          sb.append(callStack).append('\n');
        }
        System.err.print(sb);
        if (i == KEEP) {
          i = 0;
          for (int j = 1; j < KEEP; j++) {
            if (PREV_TIME[j] < PREV_TIME[i]) {
              i = j;
            }
          }
          PREV_MSG[i] = details;
        }
        PREV_TIME[i] = now;
      }
    }
    return 0;
  }

  public static int sendConsoleLine(String line) {
    if (sendConsole) {
      System.out.println(line);
      System.out.flush();
    }
    return 0;
  }
}
