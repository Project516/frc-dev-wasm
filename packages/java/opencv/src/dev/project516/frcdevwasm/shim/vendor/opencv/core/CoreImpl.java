package dev.project516.frcdevwasm.shim.vendor.opencv.core;

import dev.project516.frcdevwasm.shim.vendor.opencv.core.MatImpl.Data;

/** The org.opencv.core.Core functions that geometry code calls: arithmetic, reduce, perspectiveTransform. The others do nothing. */
public final class CoreImpl {
  private CoreImpl() {}

  private static final int REDUCE_SUM = 0;
  private static final int REDUCE_AVG = 1;
  private static final int REDUCE_MAX = 2;
  private static final int REDUCE_MIN = 3;
  private static final int REDUCE_SUM2 = 4;

  private static final int ADD = 0;
  private static final int SUBTRACT = 1;
  private static final int MULTIPLY = 2;
  private static final int DIVIDE = 3;

  private static double apply(int op, double a, double b, double scale) {
    return switch (op) {
      case ADD -> a + b;
      case SUBTRACT -> a - b;
      case MULTIPLY -> a * b * scale;
      default -> b == 0 ? 0 : a * scale / b;
    };
  }

  /** Element by element, with the second operand a Mat of the same size or one value per channel. Masks are ignored. */
  private static void arithmetic(int op, long first, long second, double[] scalar, long dst, double scale, int dtype) {
    Data a = MatImpl.data(first);
    Data b = second == 0 ? null : MatImpl.data(second);
    if (b != null && b.size() != a.size()) {
      throw new IllegalArgumentException("the operands must have the same size");
    }
    Data out = new Data();
    out.alloc(a.rows, a.cols, MatImpl.type(dtype < 0 ? a.depth() : dtype & 7, a.channels()));
    for (int i = 0; i < a.size(); i++) {
      out.set(i, apply(op, a.get(i), b != null ? b.get(i) : scalar[i % a.channels() % 4], scale));
    }
    MatImpl.data(dst).share(out);
  }

  private static double[] scalar(double v0, double v1, double v2, double v3) {
    return new double[] {v0, v1, v2, v3};
  }

  public static void add_0(long src1, long src2, long dst, long mask, int dtype) {
    arithmetic(ADD, src1, src2, null, dst, 1, dtype);
  }

  public static void add_1(long src1, long src2, long dst, long mask) {
    arithmetic(ADD, src1, src2, null, dst, 1, -1);
  }

  public static void add_2(long src1, long src2, long dst) {
    arithmetic(ADD, src1, src2, null, dst, 1, -1);
  }

  public static void add_3(long src1, double v0, double v1, double v2, double v3, long dst, long mask, int dtype) {
    arithmetic(ADD, src1, 0, scalar(v0, v1, v2, v3), dst, 1, dtype);
  }

  public static void add_4(long src1, double v0, double v1, double v2, double v3, long dst, long mask) {
    arithmetic(ADD, src1, 0, scalar(v0, v1, v2, v3), dst, 1, -1);
  }

  public static void add_5(long src1, double v0, double v1, double v2, double v3, long dst) {
    arithmetic(ADD, src1, 0, scalar(v0, v1, v2, v3), dst, 1, -1);
  }

  public static void subtract_0(long src1, long src2, long dst, long mask, int dtype) {
    arithmetic(SUBTRACT, src1, src2, null, dst, 1, dtype);
  }

  public static void subtract_1(long src1, long src2, long dst, long mask) {
    arithmetic(SUBTRACT, src1, src2, null, dst, 1, -1);
  }

  public static void subtract_2(long src1, long src2, long dst) {
    arithmetic(SUBTRACT, src1, src2, null, dst, 1, -1);
  }

  public static void subtract_3(long src1, double v0, double v1, double v2, double v3, long dst, long mask, int dtype) {
    arithmetic(SUBTRACT, src1, 0, scalar(v0, v1, v2, v3), dst, 1, dtype);
  }

  public static void subtract_4(long src1, double v0, double v1, double v2, double v3, long dst, long mask) {
    arithmetic(SUBTRACT, src1, 0, scalar(v0, v1, v2, v3), dst, 1, -1);
  }

  public static void subtract_5(long src1, double v0, double v1, double v2, double v3, long dst) {
    arithmetic(SUBTRACT, src1, 0, scalar(v0, v1, v2, v3), dst, 1, -1);
  }

  public static void multiply_0(long src1, long src2, long dst, double scale, int dtype) {
    arithmetic(MULTIPLY, src1, src2, null, dst, scale, dtype);
  }

  public static void multiply_1(long src1, long src2, long dst, double scale) {
    arithmetic(MULTIPLY, src1, src2, null, dst, scale, -1);
  }

  public static void multiply_2(long src1, long src2, long dst) {
    arithmetic(MULTIPLY, src1, src2, null, dst, 1, -1);
  }

  public static void multiply_3(long src1, double v0, double v1, double v2, double v3, long dst, double scale, int dtype) {
    arithmetic(MULTIPLY, src1, 0, scalar(v0, v1, v2, v3), dst, scale, dtype);
  }

  public static void multiply_4(long src1, double v0, double v1, double v2, double v3, long dst, double scale) {
    arithmetic(MULTIPLY, src1, 0, scalar(v0, v1, v2, v3), dst, scale, -1);
  }

  public static void multiply_5(long src1, double v0, double v1, double v2, double v3, long dst) {
    arithmetic(MULTIPLY, src1, 0, scalar(v0, v1, v2, v3), dst, 1, -1);
  }

  public static void divide_0(long src1, long src2, long dst, double scale, int dtype) {
    arithmetic(DIVIDE, src1, src2, null, dst, scale, dtype);
  }

  public static void divide_1(long src1, long src2, long dst, double scale) {
    arithmetic(DIVIDE, src1, src2, null, dst, scale, -1);
  }

  public static void divide_2(long src1, long src2, long dst) {
    arithmetic(DIVIDE, src1, src2, null, dst, 1, -1);
  }

  public static void divide_5(long src1, double v0, double v1, double v2, double v3, long dst, double scale, int dtype) {
    arithmetic(DIVIDE, src1, 0, scalar(v0, v1, v2, v3), dst, scale, dtype);
  }

  public static void divide_6(long src1, double v0, double v1, double v2, double v3, long dst, double scale) {
    arithmetic(DIVIDE, src1, 0, scalar(v0, v1, v2, v3), dst, scale, -1);
  }

  public static void divide_7(long src1, double v0, double v1, double v2, double v3, long dst) {
    arithmetic(DIVIDE, src1, 0, scalar(v0, v1, v2, v3), dst, 1, -1);
  }

  /** Collapses rows (dim 0) or columns (dim 1) of every channel. dtype below 0 keeps the depth of the source. */
  public static void reduce_0(long src, long dst, int dim, int rtype, int dtype) {
    Data s = MatImpl.data(src);
    int channels = s.channels();
    boolean toRow = dim == 0;
    int outRows = toRow ? 1 : s.rows;
    int outCols = toRow ? s.cols : 1;
    Data out = new Data();
    out.alloc(outRows, outCols, MatImpl.type(dtype < 0 ? s.depth() : dtype & 7, channels));
    int count = toRow ? s.rows : s.cols;
    for (int r = 0; r < outRows; r++) {
      for (int c = 0; c < outCols; c++) {
        for (int ch = 0; ch < channels; ch++) {
          double acc = rtype == REDUCE_MAX ? -Double.MAX_VALUE : rtype == REDUCE_MIN ? Double.MAX_VALUE : 0;
          for (int k = 0; k < count; k++) {
            double v = toRow ? s.get(k, c, ch) : s.get(r, k, ch);
            switch (rtype) {
              case REDUCE_MAX -> acc = Math.max(acc, v);
              case REDUCE_MIN -> acc = Math.min(acc, v);
              case REDUCE_SUM2 -> acc += v * v;
              default -> acc += v;
            }
          }
          out.set(r, c, ch, rtype == REDUCE_AVG ? acc / count : acc);
        }
      }
    }
    MatImpl.data(dst).share(out);
  }

  public static void reduce_1(long src, long dst, int dim, int rtype) {
    reduce_0(src, dst, dim, rtype, -1);
  }

  /** Maps 2d or 3d points through a 3x3 or 4x4 projective matrix. */
  public static void perspectiveTransform_0(long src, long dst, long m) {
    Data s = MatImpl.data(src);
    Data t = MatImpl.data(m);
    int dims = s.channels();
    if (dims != 2 && dims != 3) {
      throw new IllegalArgumentException("perspectiveTransform needs 2 or 3 channels");
    }
    if (t.rows != dims + 1 || t.cols != dims + 1) {
      throw new IllegalArgumentException("perspectiveTransform needs a " + (dims + 1) + "x" + (dims + 1) + " matrix");
    }
    Data out = new Data();
    out.alloc(s.rows, s.cols, s.type);
    for (int i = 0; i < s.rows * s.cols; i++) {
      double[] homogeneous = new double[dims + 1];
      homogeneous[dims] = 1;
      for (int k = 0; k < dims; k++) {
        homogeneous[k] = s.get(i * dims + k);
      }
      double[] mapped = new double[dims + 1];
      for (int row = 0; row <= dims; row++) {
        for (int col = 0; col <= dims; col++) {
          mapped[row] += t.get(row, col, 0) * homogeneous[col];
        }
      }
      double w = mapped[dims] == 0 ? 0 : 1 / mapped[dims];
      for (int k = 0; k < dims; k++) {
        out.set(i * dims + k, mapped[k] * w);
      }
    }
    MatImpl.data(dst).share(out);
  }
}
