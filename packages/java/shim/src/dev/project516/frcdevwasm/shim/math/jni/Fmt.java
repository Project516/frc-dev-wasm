package dev.project516.frcdevwasm.shim.math.jni;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Number and matrix text matching {fmt} "{}" and the nlohmann/wpi json writer. */
final class Fmt {
  private Fmt() {}

  /** Shortest round-trip digits of a positive finite value, scientific exponent in exp[0]. */
  private static String digits(double abs, int[] exp) {
    BigDecimal bd = new BigDecimal(Double.toString(abs)).stripTrailingZeros();
    // Double.toString is not always shortest before JDK 19; long outputs are re-rounded
    if (bd.precision() > 15 || abs < Double.MIN_NORMAL) {
      BigDecimal exact = new BigDecimal(abs);
      for (int p = bd.precision(); p >= 1; p--) {
        BigDecimal r = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
        if (r.doubleValue() != abs) {
          break;
        }
        bd = r.stripTrailingZeros();
      }
    }
    String ds = bd.unscaledValue().toString();
    exp[0] = ds.length() - 1 - bd.scale();
    return ds;
  }

  private static void exponent(StringBuilder sb, int e) {
    sb.append('e').append(e < 0 ? '-' : '+');
    int a = Math.abs(e);
    if (a < 10) {
      sb.append('0');
    }
    sb.append(a);
  }

  /** What {fmt} prints for "{}" with a double. */
  static String fmt(double v) {
    if (Double.isNaN(v)) {
      return "nan";
    }
    if (Double.isInfinite(v)) {
      return v < 0 ? "-inf" : "inf";
    }
    boolean neg = v < 0 || (v == 0.0 && 1.0 / v < 0);
    StringBuilder sb = new StringBuilder();
    if (neg) {
      sb.append('-');
    }
    double abs = Math.abs(v);
    if (abs == 0.0) {
      return sb.append('0').toString();
    }
    int[] exp = new int[1];
    String ds = digits(abs, exp);
    int e = exp[0];
    int len = ds.length();
    if (e < -4 || e >= 16) {
      sb.append(ds.charAt(0));
      if (len > 1) {
        sb.append('.').append(ds, 1, len);
      }
      exponent(sb, e);
    } else if (e >= 0) {
      if (len <= e + 1) {
        sb.append(ds);
        sb.append("0".repeat(e + 1 - len));
      } else {
        sb.append(ds, 0, e + 1).append('.').append(ds, e + 1, len);
      }
    } else {
      sb.append("0.").append("0".repeat(-e - 1)).append(ds);
    }
    return sb.toString();
  }

  /** What the wpi json writer prints for a double. */
  static String json(double v) {
    if (Double.isNaN(v) || Double.isInfinite(v)) {
      return "null";
    }
    boolean neg = v < 0 || (v == 0.0 && 1.0 / v < 0);
    StringBuilder sb = new StringBuilder();
    if (neg) {
      sb.append('-');
    }
    double abs = Math.abs(v);
    if (abs == 0.0) {
      return sb.append("0.0").toString();
    }
    int[] exp = new int[1];
    String ds = digits(abs, exp);
    int k = ds.length();
    int n = exp[0] + 1;
    if (k <= n && n <= 15) {
      sb.append(ds).append("0".repeat(n - k)).append(".0");
    } else if (0 < n && n <= 15) {
      sb.append(ds, 0, n).append('.').append(ds, n, k);
    } else if (-4 < n && n <= 0) {
      sb.append("0.").append("0".repeat(-n)).append(ds);
    } else {
      sb.append(ds.charAt(0));
      if (k > 1) {
        sb.append('.').append(ds, 1, k);
      }
      exponent(sb, n - 1);
    }
    return sb.toString();
  }

  /** Rows of "  value" joined by newlines, as frc/fmt/Eigen.h formats a matrix. */
  static String matrix(double[] a, int rows, int cols) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < rows; i++) {
      for (int j = 0; j < cols; j++) {
        sb.append("  ").append(fmt(a[i * cols + j]));
      }
      if (i < rows - 1) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }
}
