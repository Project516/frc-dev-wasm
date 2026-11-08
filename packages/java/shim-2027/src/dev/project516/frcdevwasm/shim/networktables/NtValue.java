package dev.project516.frcdevwasm.shim.networktables;

import java.util.Arrays;

/** Immutable NetworkTables value. Arrays are never shared with callers. */
final class NtValue {
  static final int UNASSIGNED = 0;
  static final int BOOLEAN = 0x01;
  static final int DOUBLE = 0x02;
  static final int STRING = 0x04;
  static final int RAW = 0x08;
  static final int BOOLEAN_ARRAY = 0x10;
  static final int DOUBLE_ARRAY = 0x20;
  static final int STRING_ARRAY = 0x40;
  static final int RPC = 0x80;
  static final int INTEGER = 0x100;
  static final int FLOAT = 0x200;
  static final int INTEGER_ARRAY = 0x400;
  static final int FLOAT_ARRAY = 0x800;

  static final NtValue NONE = new NtValue(UNASSIGNED, 0, 0, 0, 0, null);

  final int type;
  final long time;
  final long serverTime;
  final long l; // boolean, integer
  final double d; // float, double
  final Object o; // String, byte[], boolean[], long[], float[], double[], String[]

  private NtValue(int type, long time, long serverTime, long l, double d, Object o) {
    this.type = type;
    this.time = time;
    this.serverTime = serverTime;
    this.l = l;
    this.d = d;
    this.o = o;
  }

  /** A time of 0 means now. */
  private static NtValue make(int type, long time, long l, double d, Object o) {
    return new NtValue(type, time == 0 ? NtTime.now() : time, 1, l, d, o);
  }

  static NtValue ofBoolean(boolean v, long time) {
    return make(BOOLEAN, time, v ? 1 : 0, 0, null);
  }

  static NtValue ofInteger(long v, long time) {
    return make(INTEGER, time, v, 0, null);
  }

  static NtValue ofFloat(float v, long time) {
    return make(FLOAT, time, 0, v, null);
  }

  static NtValue ofDouble(double v, long time) {
    return make(DOUBLE, time, 0, v, null);
  }

  static NtValue ofString(String v, long time) {
    return make(STRING, time, 0, 0, v);
  }

  static NtValue ofRaw(byte[] v, int start, int len, long time) {
    return make(RAW, time, 0, 0, Arrays.copyOfRange(v, start, start + len));
  }

  static NtValue ofBooleanArray(boolean[] v, long time) {
    return make(BOOLEAN_ARRAY, time, 0, 0, v.clone());
  }

  static NtValue ofIntegerArray(long[] v, long time) {
    return make(INTEGER_ARRAY, time, 0, 0, v.clone());
  }

  static NtValue ofFloatArray(float[] v, long time) {
    return make(FLOAT_ARRAY, time, 0, 0, v.clone());
  }

  static NtValue ofDoubleArray(double[] v, long time) {
    return make(DOUBLE_ARRAY, time, 0, 0, v.clone());
  }

  static NtValue ofStringArray(String[] v, long time) {
    return make(STRING_ARRAY, time, 0, 0, v.clone());
  }

  boolean isValid() {
    return type != UNASSIGNED;
  }

  NtValue withTimes(long time, long serverTime) {
    return new NtValue(type, time, serverTime, l, d, o);
  }

  boolean getBoolean() {
    return l != 0;
  }

  String getString() {
    return (String) o;
  }

  byte[] getRaw() {
    return (byte[]) o;
  }

  boolean[] getBooleanArray() {
    return (boolean[]) o;
  }

  long[] getIntegerArray() {
    return (long[]) o;
  }

  float[] getFloatArray() {
    return (float[]) o;
  }

  double[] getDoubleArray() {
    return (double[]) o;
  }

  String[] getStringArray() {
    return (String[]) o;
  }

  static boolean isNumeric(int type) {
    return type == INTEGER || type == FLOAT || type == DOUBLE;
  }

  static boolean isNumericArray(int type) {
    return type == INTEGER_ARRAY || type == FLOAT_ARRAY || type == DOUBLE_ARRAY;
  }

  static boolean isNumericCompatible(int a, int b) {
    return (isNumeric(a) && isNumeric(b)) || (isNumericArray(a) && isNumericArray(b));
  }

  /** Scalar numeric value of any numeric type as a long. */
  long asLong() {
    return switch (type) {
      case INTEGER -> l;
      case FLOAT, DOUBLE -> (long) d;
      default -> 0;
    };
  }

  float asFloat() {
    return switch (type) {
      case INTEGER -> (float) l;
      case FLOAT, DOUBLE -> (float) d;
      default -> 0;
    };
  }

  double asDouble() {
    return switch (type) {
      case INTEGER -> (double) l;
      case FLOAT, DOUBLE -> d;
      default -> 0;
    };
  }

  long[] asLongArray() {
    return switch (type) {
      case INTEGER_ARRAY -> ((long[]) o).clone();
      case FLOAT_ARRAY -> {
        float[] a = (float[]) o;
        long[] r = new long[a.length];
        for (int i = 0; i < a.length; i++) {
          r[i] = (long) a[i];
        }
        yield r;
      }
      case DOUBLE_ARRAY -> {
        double[] a = (double[]) o;
        long[] r = new long[a.length];
        for (int i = 0; i < a.length; i++) {
          r[i] = (long) a[i];
        }
        yield r;
      }
      default -> new long[0];
    };
  }

  float[] asFloatArray() {
    return switch (type) {
      case FLOAT_ARRAY -> ((float[]) o).clone();
      case INTEGER_ARRAY -> {
        long[] a = (long[]) o;
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) {
          r[i] = (float) a[i];
        }
        yield r;
      }
      case DOUBLE_ARRAY -> {
        double[] a = (double[]) o;
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) {
          r[i] = (float) a[i];
        }
        yield r;
      }
      default -> new float[0];
    };
  }

  double[] asDoubleArray() {
    return switch (type) {
      case DOUBLE_ARRAY -> ((double[]) o).clone();
      case INTEGER_ARRAY -> {
        long[] a = (long[]) o;
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) {
          r[i] = a[i];
        }
        yield r;
      }
      case FLOAT_ARRAY -> {
        float[] a = (float[]) o;
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) {
          r[i] = a[i];
        }
        yield r;
      }
      default -> new double[0];
    };
  }

  /** Converts between numeric types, keeping timestamps. */
  NtValue convertNumeric(int to) {
    if (to == type) {
      return this;
    }
    return switch (to) {
      case INTEGER -> new NtValue(to, time, serverTime, asLong(), 0, null);
      case FLOAT -> new NtValue(to, time, serverTime, 0, asFloat(), null);
      case DOUBLE -> new NtValue(to, time, serverTime, 0, asDouble(), null);
      case INTEGER_ARRAY -> new NtValue(to, time, serverTime, 0, 0, asLongArray());
      case FLOAT_ARRAY -> new NtValue(to, time, serverTime, 0, 0, asFloatArray());
      case DOUBLE_ARRAY -> new NtValue(to, time, serverTime, 0, 0, asDoubleArray());
      default -> NONE;
    };
  }

  /** Data equality, ignoring timestamps. Scalars compare like C++ ==. */
  boolean sameData(NtValue other) {
    if (type != other.type) {
      return false;
    }
    return switch (type) {
      case UNASSIGNED -> true;
      case BOOLEAN, INTEGER -> l == other.l;
      case FLOAT, DOUBLE -> d == other.d;
      case STRING -> o.equals(other.o);
      case RAW -> Arrays.equals((byte[]) o, (byte[]) other.o);
      case BOOLEAN_ARRAY -> Arrays.equals((boolean[]) o, (boolean[]) other.o);
      case INTEGER_ARRAY -> Arrays.equals((long[]) o, (long[]) other.o);
      case FLOAT_ARRAY -> Arrays.equals((float[]) o, (float[]) other.o);
      case DOUBLE_ARRAY -> Arrays.equals((double[]) o, (double[]) other.o);
      case STRING_ARRAY -> Arrays.equals((String[]) o, (String[]) other.o);
      default -> false;
    };
  }

  static String typeToString(int type) {
    return switch (type) {
      case BOOLEAN -> "boolean";
      case DOUBLE -> "double";
      case STRING -> "string";
      case BOOLEAN_ARRAY -> "boolean[]";
      case DOUBLE_ARRAY -> "double[]";
      case STRING_ARRAY -> "string[]";
      case RPC -> "rpc";
      case INTEGER -> "int";
      case FLOAT -> "float";
      case INTEGER_ARRAY -> "int[]";
      case FLOAT_ARRAY -> "float[]";
      default -> "raw";
    };
  }

  static int stringToType(String s) {
    return switch (s) {
      case "boolean" -> BOOLEAN;
      case "double" -> DOUBLE;
      case "string", "json" -> STRING;
      case "boolean[]" -> BOOLEAN_ARRAY;
      case "double[]" -> DOUBLE_ARRAY;
      case "string[]" -> STRING_ARRAY;
      case "rpc" -> RPC;
      case "int" -> INTEGER;
      case "float" -> FLOAT;
      case "int[]" -> INTEGER_ARRAY;
      case "float[]" -> FLOAT_ARRAY;
      default -> RAW;
    };
  }
}
