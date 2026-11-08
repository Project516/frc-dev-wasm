package dev.project516.frcdevwasm.shim.hal;

import dev.project516.frcdevwasm.sim.CanData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.SimTime;
import dev.project516.frcdevwasm.sim.Status;
import edu.wpi.first.hal.CANData;
import java.util.HashMap;
import java.util.Map;

/** Port of sim/CANAPI.cpp and CANAPIJNI.cpp. */
public final class CANAPIJNIImpl {
  private static final int ALL_BITS = 0x1FFFFFFF;
  private static final long UINT32 = 0xFFFFFFFFL;

  private static final class Received {
    long lastTimeStamp;
    final byte[] data = new byte[8];
    int length;
  }

  private static final class Device {
    final int manufacturer;
    final int deviceType;
    final int deviceId;
    final Map<Integer, Integer> periodicSends = new HashMap<>();
    final Map<Integer, Received> receives = new HashMap<>();

    Device(int manufacturer, int deviceId, int deviceType) {
      this.manufacturer = manufacturer;
      this.deviceId = deviceId;
      this.deviceType = deviceType;
    }
  }

  /** Out parameters of the HAL_ReadCANPacket functions. */
  private static final class Packet {
    int status;
    int length;
    long timestamp;
    final byte[] data = new byte[8];
  }

  private static final Handles.Unlimited<Device> DEVICES = new Handles.Unlimited<>(Handles.CAN);

  private CANAPIJNIImpl() {}

  private static int createId(Device d, int apiId) {
    return (d.deviceType & 0x1F) << 24
        | (d.manufacturer & 0xFF) << 16
        | (apiId & 0x3FF) << 6
        | (d.deviceId & 0x3F);
  }

  private static int baseTime() {
    return (int) (SimTime.fpgaTime() / 1000);
  }

  public static long getCANPacketBaseTime() {
    return baseTime() & UINT32;
  }

  public static int initializeCAN(int manufacturer, int deviceId, int deviceType) {
    Sim.ensureInitialized();
    int handle = DEVICES.allocate(new Device(manufacturer, deviceId, deviceType));
    if (handle == Handles.INVALID) {
      Status.checkForceThrow(Status.NO_AVAILABLE_RESOURCES);
    }
    return handle;
  }

  public static void cleanCAN(int handle) {
    if (handle == Handles.INVALID) {
      return;
    }
    Device d = DEVICES.free(handle);
    if (d == null) {
      return;
    }
    synchronized (d.periodicSends) {
      for (Map.Entry<Integer, Integer> e : d.periodicSends.entrySet()) {
        CanData.send(
            createId(d, e.getKey()), null, 0, CanData.SEND_PERIOD_STOP_REPEATING, new int[1]);
        e.setValue(-1);
      }
    }
  }

  private static int send(
      int handle, byte[] data, int length, int apiId, int periodMs, boolean remote) {
    Device d = DEVICES.get(handle);
    if (d == null) {
      return Status.HAL_HANDLE_ERROR;
    }
    int id = createId(d, apiId);
    if (remote) {
      id |= CanData.IS_FRAME_REMOTE;
      data = new byte[8];
    }
    int[] status = {0};
    synchronized (d.periodicSends) {
      CanData.send(id, data, length & 0xff, periodMs, status);
      d.periodicSends.put(apiId, periodMs);
    }
    return status[0];
  }

  private static int write(int handle, byte[] data, int apiId, int periodMs) {
    return send(handle, data, data == null ? 0 : data.length, apiId, periodMs, false);
  }

  public static int writeCANPacketNoThrow(int handle, byte[] data, int apiId) {
    return write(handle, data, apiId, CanData.SEND_PERIOD_NO_REPEAT);
  }

  public static int writeCANPacketRepeatingNoThrow(
      int handle, byte[] data, int apiId, int timeoutMs) {
    return write(handle, data, apiId, timeoutMs);
  }

  public static int writeCANRTRFrameNoThrow(int handle, int length, int apiId) {
    return send(handle, null, length, apiId, CanData.SEND_PERIOD_NO_REPEAT, true);
  }

  public static void writeCANPacket(int handle, byte[] data, int apiId) {
    Status.check(writeCANPacketNoThrow(handle, data, apiId));
  }

  public static void writeCANPacketRepeating(int handle, byte[] data, int apiId, int timeoutMs) {
    Status.check(writeCANPacketRepeatingNoThrow(handle, data, apiId, timeoutMs));
  }

  public static void writeCANRTRFrame(int handle, int length, int apiId) {
    Status.check(writeCANRTRFrameNoThrow(handle, length, apiId));
  }

  public static void stopCANPacketRepeating(int handle, int apiId) {
    Device d = DEVICES.get(handle);
    if (d == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
      return;
    }
    int[] status = {0};
    synchronized (d.periodicSends) {
      CanData.send(createId(d, apiId), null, 0, CanData.SEND_PERIOD_STOP_REPEATING, status);
      d.periodicSends.put(apiId, -1);
    }
    Status.check(status[0]);
  }

  /** Receives a message for apiId. Returns the message id the receive ended on. */
  private static int receive(Device d, int apiId, Packet out) {
    int[] id = {createId(d, apiId)};
    int[] size = {0};
    int[] ts = {0};
    int[] status = {0};
    CanData.receive(id, ALL_BITS, out.data, size, ts, status);
    out.status = status[0];
    out.length = size[0] & 0xff;
    out.timestamp = ts[0] & UINT32;
    return id[0];
  }

  private static void remember(Device d, int messageId, Packet p) {
    Received r = d.receives.computeIfAbsent(messageId, k -> new Received());
    r.length = p.length;
    r.lastTimeStamp = p.timestamp;
    System.arraycopy(p.data, 0, r.data, 0, Math.min(p.length, 8));
  }

  private static Packet readNew(int handle, int apiId) {
    Packet p = new Packet();
    Device d = DEVICES.get(handle);
    if (d == null) {
      p.status = Status.HAL_HANDLE_ERROR;
      return p;
    }
    int id = receive(d, apiId, p);
    if (p.status == 0) {
      synchronized (d.receives) {
        remember(d, id, p);
      }
    }
    return p;
  }

  private static Packet readLatest(int handle, int apiId, int timeoutMs, boolean checkTimeout) {
    Packet p = new Packet();
    Device d = DEVICES.get(handle);
    if (d == null) {
      p.status = Status.HAL_HANDLE_ERROR;
      return p;
    }
    int id = receive(d, apiId, p);
    synchronized (d.receives) {
      if (p.status == 0) {
        remember(d, id, p);
        return p;
      }
      Received r = d.receives.get(id);
      if (r == null) {
        return p;
      }
      if (checkTimeout
          && Long.compareUnsigned((baseTime() & UINT32) - r.lastTimeStamp, timeoutMs & UINT32) > 0) {
        p.status = Status.HAL_CAN_TIMEOUT;
        return p;
      }
      System.arraycopy(r.data, 0, p.data, 0, Math.min(r.length, 8));
      p.length = r.length;
      p.timestamp = r.lastTimeStamp;
      p.status = 0;
    }
    return p;
  }

  private static boolean deliver(Packet p, CANData data) {
    int length = Math.min(p.length, 8);
    byte[] target = data.setData(length, p.timestamp);
    System.arraycopy(p.data, 0, target, 0, Math.min(length, target.length));
    return true;
  }

  public static boolean readCANPacketNew(int handle, int apiId, CANData data) {
    Packet p = readNew(handle, apiId);
    if (p.status == Status.ERR_CAN_MESSAGE_NOT_FOUND || !Status.check(p.status)) {
      return false;
    }
    return deliver(p, data);
  }

  public static boolean readCANPacketLatest(int handle, int apiId, CANData data) {
    Packet p = readLatest(handle, apiId, 0, false);
    if (p.status == Status.ERR_CAN_MESSAGE_NOT_FOUND || !Status.check(p.status)) {
      return false;
    }
    return deliver(p, data);
  }

  public static boolean readCANPacketTimeout(int handle, int apiId, int timeoutMs, CANData data) {
    Packet p = readLatest(handle, apiId, timeoutMs, true);
    if (p.status == Status.HAL_CAN_TIMEOUT
        || p.status == Status.ERR_CAN_MESSAGE_NOT_FOUND
        || !Status.check(p.status)) {
      return false;
    }
    return deliver(p, data);
  }
}
