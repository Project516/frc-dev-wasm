package dev.project516.frcdevwasm.shim.math.jni;

public final class EigenJNIImpl {
  private static final double[] PADE3 = {120, 60, 12, 1};
  private static final double[] PADE5 = {30240, 15120, 3360, 420, 30, 1};
  private static final double[] PADE7 = {17297280, 8648640, 1995840, 277200, 25200, 1512, 56, 1};
  private static final double[] PADE9 = {
    17643225600.0, 8821612800.0, 2075673600.0, 302702400, 30270240, 2162160, 110880, 3960, 90, 1
  };
  private static final double[] PADE13 = {
    64764752532480000.0,
    32382376266240000.0,
    7771770303897600.0,
    1187353796428800.0,
    129060195264000.0,
    10559470521600.0,
    670442572800.0,
    33522128640.0,
    1323241920,
    40840800,
    960960,
    16380,
    182,
    1
  };

  private EigenJNIImpl() {}

  /** Matrix exponential by scaling and squaring with Pade approximants. */
  public static void exp(double[] src, int rows, double[] dst) {
    int n = rows;
    if (n == 0) {
      return;
    }
    double l1 = 0.0;
    for (int j = 0; j < n; j++) {
      double s = 0.0;
      for (int i = 0; i < n; i++) {
        s += Math.abs(src[i * n + j]);
      }
      l1 = Math.max(l1, s);
    }
    int squarings = 0;
    double[] a = src;
    double[][] uv;
    if (l1 < 1.495585217958292e-002) {
      uv = pade(a, n, PADE3, 3);
    } else if (l1 < 2.539398330063230e-001) {
      uv = pade(a, n, PADE5, 5);
    } else if (l1 < 9.504178996162932e-001) {
      uv = pade(a, n, PADE7, 7);
    } else if (l1 < 2.097847961257068e+000) {
      uv = pade(a, n, PADE9, 9);
    } else {
      double x = l1 / 5.371920351148152;
      if (x > 0 && !Double.isInfinite(x) && !Double.isNaN(x)) {
        squarings = Math.max(Math.getExponent(x) + 1, 0);
      }
      a = new double[src.length];
      for (int i = 0; i < a.length; i++) {
        a[i] = Math.scalb(src[i], -squarings);
      }
      uv = pade13(a, n);
    }
    double[] u = uv[0];
    double[] v = uv[1];
    double[] numer = new double[n * n];
    double[] denom = new double[n * n];
    for (int i = 0; i < numer.length; i++) {
      numer[i] = u[i] + v[i];
      denom[i] = -u[i] + v[i];
    }
    new Dense.Lu(denom, n).solve(numer, n);
    double[] result = numer;
    for (int i = 0; i < squarings; i++) {
      result = Dense.mul(result, result, n, n, n);
    }
    System.arraycopy(result, 0, dst, 0, n * n);
  }

  /** Low degree Pade: U = A (b1 I + b3 A2 + ...), V = b0 I + b2 A2 + ... . */
  private static double[][] pade(double[] a, int n, double[] b, int degree) {
    int terms = degree / 2;
    double[][] pow = new double[terms + 1][];
    pow[1] = Dense.mul(a, a, n, n, n);
    for (int k = 2; k <= terms; k++) {
      pow[k] = Dense.mul(pow[k - 1], pow[1], n, n, n);
    }
    double[] tmp = new double[n * n];
    double[] v = new double[n * n];
    for (int i = 0; i < n; i++) {
      tmp[i * n + i] = b[1];
      v[i * n + i] = b[0];
    }
    for (int k = 1; k <= terms; k++) {
      double bo = b[2 * k + 1];
      double be = b[2 * k];
      for (int i = 0; i < tmp.length; i++) {
        tmp[i] += bo * pow[k][i];
        v[i] += be * pow[k][i];
      }
    }
    return new double[][] {Dense.mul(a, tmp, n, n, n), v};
  }

  private static double[][] pade13(double[] a, int n) {
    double[] b = PADE13;
    double[] a2 = Dense.mul(a, a, n, n, n);
    double[] a4 = Dense.mul(a2, a2, n, n, n);
    double[] a6 = Dense.mul(a4, a2, n, n, n);
    int len = n * n;
    double[] t = new double[len];
    for (int i = 0; i < len; i++) {
      t[i] = b[13] * a6[i] + b[11] * a4[i] + b[9] * a2[i];
    }
    double[] tmp = Dense.mul(a6, t, n, n, n);
    for (int i = 0; i < len; i++) {
      tmp[i] += b[7] * a6[i] + b[5] * a4[i] + b[3] * a2[i];
    }
    for (int i = 0; i < n; i++) {
      tmp[i * n + i] += b[1];
    }
    double[] u = Dense.mul(a, tmp, n, n, n);
    for (int i = 0; i < len; i++) {
      t[i] = b[12] * a6[i] + b[10] * a4[i] + b[8] * a2[i];
    }
    double[] v = Dense.mul(a6, t, n, n, n);
    for (int i = 0; i < len; i++) {
      v[i] += b[6] * a6[i] + b[4] * a4[i] + b[2] * a2[i];
    }
    for (int i = 0; i < n; i++) {
      v[i * n + i] += b[0];
    }
    return new double[][] {u, v};
  }

  public static void pow(double[] src, int rows, double exponent, double[] dst) {
    MatrixPow.pow(src, rows, exponent, dst);
  }

  /** In-place rank one update of a Cholesky factor held row-major. */
  public static void rankUpdate(
      double[] mat, int rows, double[] vec, double sigma, boolean lowerTriangular) {
    Dense.lltRankUpdate(mat, rows, vec, sigma, lowerTriangular);
  }
}
