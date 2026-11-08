package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.shim.hardware.hal.can.CANJNIImpl;
import dev.project516.frcdevwasm.sim.CanData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.SimTime;
import dev.project516.frcdevwasm.sim.Status;
import java.util.HashMap;
import java.util.Map;
import org.wpilib.hardware.hal.can.CANReceiveMessage;

/** Port of sim/CANAPI.cpp and CANAPIJNI.cpp. */
public final class CANAPIJNIImpl {
  private static final class Device {
    final int busId;
    final int manufacturer;
    final int deviceType;
    final int deviceId;
    final Map<Integer, Integer> periodicSends = new HashMap<>();
    final Map<Integer, CanData.ReceiveMessage> receives = new HashMap<>();

    Device(int busId, int manufacturer, int deviceId, int deviceType) {
      this.busId = busId;
      this.manufacturer = manufacturer;
      this.deviceId = deviceId;
      this.deviceType = deviceType;
    }
  }

  private static final Handles.Unlimited<Device> DEVICES = new Handles.Unlimited<>(Handles.CAN);

  private CANAPIJNIImpl() {}

  private static int createId(Device d, int apiId) {
    return (d.deviceType & 0x1F) << 24
        | (d.manufacturer & 0xFF) << 16
        | (apiId & 0x3FF) << 6
        | (d.deviceId & 0x3F);
  }

  public static int initializeCAN(int busId, int manufacturer, int deviceId, int deviceType) {
    Sim.ensureInitialized();
    if (busId < 0 || busId > Ports.CAN_BUSES) {
      Status.checkForceThrow(Status.PARAMETER_OUT_OF_RANGE);
      return 0;
    }
    int handle = DEVICES.allocate(new Device(busId, manufacturer, deviceId, deviceType));
    if (handle == Handles.INVALID) {
      Status.checkForceThrow(Status.NO_AVAILABLE_RESOURCES);
    }
    return handle;
  }

  public static void cleanCAN(int handle) {
    Device d = DEVICES.free(handle);
    if (d == null) {
      return;
    }
    synchronized (d.periodicSends) {
      for (Map.Entry<Integer, Integer> e : d.periodicSends.entrySet()) {
        CanData.send(
            d.busId,
            createId(d, e.getKey()),
            new CanData.Message(),
            CanData.SEND_PERIOD_STOP_REPEATING,
            new int[1]);
        e.setValue(-1);
      }
    }
  }

  private static int send(int handle, int apiId, CanData.Message message, int periodMs, boolean rtr) {
    Device d = DEVICES.get(handle);
    if (d == null) {
      return Status.HAL_HANDLE_ERROR;
    }
    int id = createId(d, apiId);
    if (rtr) {
      id |= CanData.IS_FRAME_REMOTE;
    }
    int[] status = {0};
    synchronized (d.periodicSends) {
      CanData.send(d.busId, id, message, periodMs, status);
      d.periodicSends.put(apiId, periodMs == CanData.SEND_PERIOD_NO_REPEAT ? -1 : periodMs);
    }
    return status[0];
  }

  public static int writeCANPacketNoThrow(
      int handle, int apiId, byte[] data, int dataLength, int flags) {
    return send(
        handle, apiId, CanData.pack(data, dataLength, flags), CanData.SEND_PERIOD_NO_REPEAT, false);
  }

  public static int writeCANPacketRepeatingNoThrow(
      int handle, int apiId, byte[] data, int dataLength, int flags, int repeatMs) {
    return send(handle, apiId, CanData.pack(data, dataLength, flags), repeatMs, false);
  }

  public static int writeCANRTRFrameNoThrow(
      int handle, int apiId, byte[] data, int dataLength, int flags) {
    return send(
        handle,
        apiId,
        CanData.packRtr(data, dataLength, flags),
        CanData.SEND_PERIOD_NO_REPEAT,
        true);
  }

  public static void writeCANPacket(int handle, int apiId, byte[] data, int dataLength, int flags) {
    Status.check(writeCANPacketNoThrow(handle, apiId, data, dataLength, flags));
  }

  public static void writeCANPacketRepeating(
      int handle, int apiId, byte[] data, int dataLength, int flags, int repeatMs) {
    Status.check(writeCANPacketRepeatingNoThrow(handle, apiId, data, dataLength, flags, repeatMs));
  }

  public static void writeCANRTRFrame(
      int handle, int apiId, byte[] data, int dataLength, int flags) {
    Status.check(writeCANRTRFrameNoThrow(handle, apiId, data, dataLength, flags));
  }

  public static void stopCANPacketRepeating(int handle, int apiId) {
    Device d = DEVICES.get(handle);
    if (d == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
      return;
    }
    int[] status = {0};
    synchronized (d.periodicSends) {
      CanData.send(
          d.busId,
          createId(d, apiId),
          new CanData.Message(),
          CanData.SEND_PERIOD_STOP_REPEATING,
          status);
      d.periodicSends.put(apiId, -1);
    }
    Status.check(status[0]);
  }

  private static CanData.ReceiveMessage copy(CanData.ReceiveMessage m) {
    CanData.ReceiveMessage c = new CanData.ReceiveMessage();
    c.timeStamp = m.timeStamp;
    c.message.flags = m.message.flags;
    c.message.dataSize = m.message.dataSize;
    System.arraycopy(m.message.data, 0, c.message.data, 0, CanData.MAX_DATA);
    return c;
  }

  /** HAL_ReadCANPacketNew, Latest (a negative timeout does not expire) and Timeout. */
  private static int read(
      int handle, int apiId, int timeoutMs, boolean latest, CanData.ReceiveMessage out) {
    Device d = DEVICES.get(handle);
    if (d == null) {
      return Status.HAL_HANDLE_ERROR;
    }
    int id = createId(d, apiId);
    int[] status = {0};
    CanData.receive(d.busId, id, out, status);
    synchronized (d.receives) {
      if (status[0] == 0) {
        d.receives.put(id, copy(out));
        return 0;
      }
      CanData.ReceiveMessage last = d.receives.get(id);
      if (!latest || last == null) {
        return status[0];
      }
      if (timeoutMs >= 0 && SimTime.now() - last.timeStamp > timeoutMs * 1_000_000L) {
        return Status.HAL_CAN_TIMEOUT;
      }
      CanData.ReceiveMessage c = copy(last);
      out.timeStamp = c.timeStamp;
      out.message.flags = c.message.flags;
      out.message.dataSize = c.message.dataSize;
      System.arraycopy(c.message.data, 0, out.message.data, 0, CanData.MAX_DATA);
      return 0;
    }
  }

  private static boolean deliver(int status, CanData.ReceiveMessage m, CANReceiveMessage data) {
    if (status == Status.ERR_CAN_MESSAGE_NOT_FOUND || !Status.check(status)) {
      return false;
    }
    CANJNIImpl.fill(data, m);
    return true;
  }

  public static boolean readCANPacketNew(int handle, int apiId, CANReceiveMessage data) {
    CanData.ReceiveMessage m = new CanData.ReceiveMessage();
    return deliver(read(handle, apiId, -1, false, m), m, data);
  }

  public static boolean readCANPacketLatest(int handle, int apiId, CANReceiveMessage data) {
    CanData.ReceiveMessage m = new CanData.ReceiveMessage();
    return deliver(read(handle, apiId, -1, true, m), m, data);
  }

  public static boolean readCANPacketTimeout(
      int handle, int apiId, CANReceiveMessage data, int timeoutMs) {
    CanData.ReceiveMessage m = new CanData.ReceiveMessage();
    int status = read(handle, apiId, timeoutMs, true, m);
    if (status == Status.HAL_CAN_TIMEOUT) {
      return false;
    }
    return deliver(status, m, data);
  }
}
