package dev.project516.frcdevwasm.shim.vendor.ctre.phoenix6.sim;

import org.wpilib.hardware.hal.HALUtil;
import java.util.HashMap;
import java.util.Map;

/**
 * The simulated Phoenix 6 devices of the robot program. The native sim library runs device firmware
 * on its own threads. Here each device catches up to the HAL clock whenever the program touches it,
 * so simulated time can be paused and stepped. Every JNI entry point holds {@link #LOCK}.
 */
public final class PhoenixSim {
  public static final Object LOCK = new Object();

  public static final int TALON_FX = 4;
  public static final int CANCODER = 5;
  public static final int PIGEON2 = 6;

  private static final Map<Integer, Device> devices = new HashMap<>();

  private PhoenixSim() {}

  public static double now() {
    return HALUtil.getMonotonicTime() / 1e9;
  }

  public static int typeOf(String model) {
    return switch (model) {
      case "talon fx" -> TALON_FX;
      case "cancoder" -> CANCODER;
      case "pigeon 2" -> PIGEON2;
      case "talon fxs" -> 7;
      case "canrange" -> 8;
      case "candi" -> 9;
      case "candle" -> 10;
      default -> -1;
    };
  }

  private static String modelOf(int type) {
    return switch (type) {
      case TALON_FX -> "talon fx";
      case CANCODER -> "cancoder";
      case PIGEON2 -> "pigeon 2";
      case 7 -> "talon fxs";
      case 8 -> "canrange";
      case 9 -> "candi";
      case 10 -> "candle";
      default -> "";
    };
  }

  /** The device of the type and id, created on first use. */
  public static Device device(int type, int id) {
    synchronized (LOCK) {
      return devices.computeIfAbsent(
          type * 1000 + id,
          k ->
              switch (type) {
                case TALON_FX -> new TalonFxSim(id);
                case CANCODER -> new CanCoderSim(id);
                case PIGEON2 -> new Pigeon2Sim(id);
                default -> new Device(modelOf(type), id);
              });
    }
  }

  public static TalonFxSim talon(int id) {
    return (TalonFxSim) device(TALON_FX, id);
  }

  public static CanCoderSim cancoder(int id) {
    return (CanCoderSim) device(CANCODER, id);
  }

  public static Pigeon2Sim pigeon(int id) {
    return (Pigeon2Sim) device(PIGEON2, id);
  }

  public static void destroy(int type, int id) {
    synchronized (LOCK) {
      devices.remove(type * 1000 + id);
    }
  }

  public static void destroyAll() {
    synchronized (LOCK) {
      devices.clear();
    }
  }
}
