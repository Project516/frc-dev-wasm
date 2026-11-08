package dev.project516.frcdevwasm.shim.math.optimization;

/** Dense vector helpers, with std::max semantics where Sleipnir uses it. */
final class Vec {
  private Vec() {}

  static final double EPSILON = Math.ulp(1.0);

  static double max(double a, double b) {
    return a < b ? b : a;
  }

  static double min(double a, double b) {
    return b < a ? b : a;
  }

  static double norm1(double[] x) {
    double s = 0.0;
    for (double v : x) {
      s += Math.abs(v);
    }
    return s;
  }

  /** Eigen returns 0 for an empty vector. */
  static double normInf(double[] x) {
    double m = 0.0;
    for (int i = 0; i < x.length; i++) {
      double a = Math.abs(x[i]);
      if (i == 0 || a > m) {
        m = a;
      }
    }
    return m;
  }

  static double norm2(double[] x) {
    double s = 0.0;
    for (double v : x) {
      s += v * v;
    }
    return Math.sqrt(s);
  }

  static double dot(double[] a, double[] b) {
    double s = 0.0;
    for (int i = 0; i < a.length; i++) {
      s += a[i] * b[i];
    }
    return s;
  }

  static boolean allFinite(double[] x) {
    for (double v : x) {
      if (!Double.isFinite(v)) {
        return false;
      }
    }
    return true;
  }

  /** x + a * p. */
  static double[] axpy(double[] x, double a, double[] p) {
    double[] out = new double[x.length];
    for (int i = 0; i < x.length; i++) {
      out[i] = x[i] + a * p[i];
    }
    return out;
  }

  static double[] minus(double[] a, double[] b) {
    double[] out = new double[a.length];
    for (int i = 0; i < a.length; i++) {
      out[i] = a[i] - b[i];
    }
    return out;
  }

  static double[] times(double[] a, double[] b) {
    double[] out = new double[a.length];
    for (int i = 0; i < a.length; i++) {
      out[i] = a[i] * b[i];
    }
    return out;
  }

  static double[] scale(double s, double[] a) {
    double[] out = new double[a.length];
    for (int i = 0; i < a.length; i++) {
      out[i] = s * a[i];
    }
    return out;
  }

  static double[] inverse(double[] a) {
    double[] out = new double[a.length];
    for (int i = 0; i < a.length; i++) {
      out[i] = 1.0 / a[i];
    }
    return out;
  }

  static double[] filled(int n, double v) {
    double[] out = new double[n];
    java.util.Arrays.fill(out, v);
    return out;
  }

  static double[] concat(double[]... parts) {
    int n = 0;
    for (double[] p : parts) {
      n += p.length;
    }
    double[] out = new double[n];
    int at = 0;
    for (double[] p : parts) {
      System.arraycopy(p, 0, out, at, p.length);
      at += p.length;
    }
    return out;
  }

  static double[] segment(double[] x, int from, int length) {
    return java.util.Arrays.copyOfRange(x, from, from + length);
  }
}
