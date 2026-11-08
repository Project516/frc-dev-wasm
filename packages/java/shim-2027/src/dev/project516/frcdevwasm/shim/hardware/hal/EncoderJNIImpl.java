package dev.project516.frcdevwasm.shim.hardware.hal;

import dev.project516.frcdevwasm.sim.EncoderData;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.Ports;
import dev.project516.frcdevwasm.sim.Sim;
import dev.project516.frcdevwasm.sim.Status;

/** Port of sim/Encoder.cpp and the EncoderJNI wrappers. */
public final class EncoderJNIImpl {
  private static final int K1X = 0;
  private static final int K2X = 1;
  private static final int K4X = 2;

  private static final class Encoder {
    int nativeHandle;
    int encodingType;
    double distancePerPulse;
    int index;
  }

  private static final Handles.Limited<Object> FPGA_ENCODERS =
      new Handles.Limited<>(Handles.FPGA_ENCODER, Ports.ENCODERS);

  /** Counters taken by 1X and 2X encoders. Share this table with the Counter implementation. */
  public static final Handles.Limited<Object> COUNTERS =
      new Handles.Limited<>(Handles.COUNTER, Ports.COUNTERS);

  private static final Handles.Limited<Encoder> ENCODERS =
      new Handles.Limited<>(Handles.ENCODER, Ports.ENCODERS + Ports.COUNTERS);

  private EncoderJNIImpl() {}

  private static Encoder encoder(int handle) {
    Encoder e = ENCODERS.get(handle);
    if (e == null) {
      Status.check(Status.HAL_HANDLE_ERROR);
    }
    return e;
  }

  private static int encodingScaleFactor(Encoder e) {
    return switch (e.encodingType) {
      case K1X -> 1;
      case K2X -> 2;
      case K4X -> 4;
      default -> 0;
    };
  }

  private static double decodingScaleFactor(Encoder e) {
    return switch (e.encodingType) {
      case K1X -> 1.0;
      case K2X -> 0.5;
      case K4X -> 0.25;
      default -> 0.0;
    };
  }

  public static int initializeEncoder(
      int aChannel, int bChannel, boolean reverseDirection, int encodingType) {
    Sim.ensureInitialized();
    Object slot = new Object();
    int nativeHandle =
        encodingType == K4X ? FPGA_ENCODERS.allocate(slot) : COUNTERS.allocate(slot);
    if (nativeHandle == Handles.INVALID) {
      Status.checkForceThrow(Status.NO_AVAILABLE_RESOURCES);
      return 0;
    }
    Encoder encoder = new Encoder();
    int handle = ENCODERS.allocate(encoder);
    int index = handle & 0xffff;
    if (handle == Handles.INVALID || index >= Ports.ENCODERS) {
      ENCODERS.free(handle);
      freeNative(nativeHandle);
      Status.checkForceThrow(Status.NO_AVAILABLE_RESOURCES);
      return 0;
    }
    EncoderData data = EncoderData.get(index);
    data.digitalChannelA = aChannel;
    data.digitalChannelB = bChannel;
    data.initialized.set(true);
    data.reverseDirection.set(reverseDirection);
    data.simDevice = 0;
    encoder.index = index;
    encoder.nativeHandle = nativeHandle;
    encoder.encodingType = encodingType;
    encoder.distancePerPulse = 1.0;
    return handle;
  }

  public static void freeEncoder(int id) {
    if (id == Handles.INVALID) {
      return;
    }
    Encoder encoder = ENCODERS.free(id);
    if (encoder == null) {
      return;
    }
    freeNative(encoder.nativeHandle);
    EncoderData.get(encoder.index).initialized.set(false);
  }

  private static void freeNative(int nativeHandle) {
    if (Handles.type(nativeHandle) == Handles.FPGA_ENCODER) {
      FPGA_ENCODERS.free(nativeHandle);
    } else if (Handles.type(nativeHandle) == Handles.COUNTER) {
      COUNTERS.free(nativeHandle);
    }
  }

  public static void setEncoderSimDevice(int handle, int device) {
    Encoder encoder = ENCODERS.get(handle);
    if (encoder != null) {
      EncoderData.get(encoder.index).simDevice = device;
    }
  }

  public static int getEncoder(int id) {
    Encoder e = encoder(id);
    return e == null ? 0 : EncoderData.get(e.index).count.get();
  }

  public static int getEncoderRaw(int id) {
    Encoder e = encoder(id);
    return e == null ? 0 : (int) (EncoderData.get(e.index).count.get() / decodingScaleFactor(e));
  }

  public static int getEncodingScaleFactor(int id) {
    return getEncoderEncodingScale(id);
  }

  public static int getEncoderEncodingScale(int id) {
    Encoder e = encoder(id);
    return e == null ? 0 : encodingScaleFactor(e);
  }

  public static void resetEncoder(int id) {
    Encoder e = encoder(id);
    if (e != null) {
      EncoderData data = EncoderData.get(e.index);
      data.reset.set(true);
      data.count.set(0);
    }
  }

  public static boolean getEncoderStopped(int id) {
    Encoder e = encoder(id);
    if (e == null) {
      return false;
    }
    return EncoderData.get(e.index).rate.get() == 0;
  }

  public static boolean getEncoderDirection(int id) {
    Encoder e = encoder(id);
    return e != null && EncoderData.get(e.index).direction.get();
  }

  public static double getEncoderDistance(int id) {
    Encoder e = encoder(id);
    return e == null ? 0 : EncoderData.get(e.index).count.get() * e.distancePerPulse;
  }

  public static double getEncoderRate(int id) {
    Encoder e = encoder(id);
    return e == null ? 0 : EncoderData.get(e.index).rate.get();
  }

  public static void setEncoderRateWindow(int id, int windowMilliseconds) {
    if (encoder(id) != null && (windowMilliseconds < 5 || windowMilliseconds > 255)) {
      Status.check(Status.PARAMETER_OUT_OF_RANGE);
    }
  }

  public static void setEncoderDistancePerPulse(int id, double value) {
    Encoder e = encoder(id);
    if (e == null) {
      return;
    }
    if (value == 0.0) {
      Status.check(
          Status.setLastError(Status.PARAMETER_OUT_OF_RANGE, "distancePerPulse must not be 0"));
      return;
    }
    e.distancePerPulse = value;
    EncoderData.get(e.index).distancePerPulse.set(value);
  }

  public static void setEncoderReverseDirection(int id, boolean value) {
    Encoder e = encoder(id);
    if (e != null) {
      EncoderData.get(e.index).reverseDirection.set(value);
    }
  }

  public static int getEncoderFPGAIndex(int id) {
    Encoder e = encoder(id);
    return e == null ? 0 : e.index;
  }

  public static double getEncoderDecodingScaleFactor(int id) {
    Encoder e = encoder(id);
    return e == null ? 0.0 : decodingScaleFactor(e);
  }

  public static double getEncoderDistancePerPulse(int id) {
    Encoder e = encoder(id);
    return e == null ? 0.0 : EncoderData.get(e.index).distancePerPulse.get();
  }

  public static int getEncoderEncodingType(int id) {
    Encoder e = encoder(id);
    return e == null ? K4X : e.encodingType;
  }
}
