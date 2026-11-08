package dev.project516.frcdevwasm.shim.math.jni;

public final class DAREJNIImpl {
  private static final int MAX_ITERATIONS = 1000;

  private DAREJNIImpl() {}

  public static void dareNoPrecondABQR(
      double[] A, double[] B, double[] Q, double[] R, int states, int inputs, double[] S) {
    dare(A, B, Q, R, null, states, inputs, S, false);
  }

  public static void dareNoPrecondABQRN(
      double[] A,
      double[] B,
      double[] Q,
      double[] R,
      double[] N,
      int states,
      int inputs,
      double[] S) {
    dare(A, B, Q, R, N, states, inputs, S, false);
  }

  public static void dareABQR(
      double[] A, double[] B, double[] Q, double[] R, int states, int inputs, double[] S) {
    dare(A, B, Q, R, null, states, inputs, S, true);
  }

  public static void dareABQRN(
      double[] A,
      double[] B,
      double[] Q,
      double[] R,
      double[] N,
      int states,
      int inputs,
      double[] S) {
    dare(A, B, Q, R, N, states, inputs, S, true);
  }

  private static void dare(
      double[] a,
      double[] b,
      double[] q,
      double[] r,
      double[] nn,
      int n,
      int m,
      double[] out,
      boolean check) {
    if (check && asymmetric(r, m)) {
      throw rError("R was not symmetric.", r, m);
    }
    double[] rl = r.clone();
    if (!Dense.llt(rl, m)) {
      throw rError("R was not positive definite.", r, m);
    }

    double[] a2 = a;
    double[] q2 = q;
    if (nn != null) {
      double[] rinvNt = Dense.transpose(nn, n, m);
      Dense.lltSolve(rl, m, rinvNt, n);
      a2 = sub(a, Dense.mul(b, rinvNt, n, m, n));
      q2 = sub(q, Dense.mul(nn, rinvNt, n, m, n));
    }

    if (check) {
      if (asymmetric(q2, n)) {
        throw qError("Q was not symmetric.", q, n);
      }
      Dense.Ldlt ldlt = new Dense.Ldlt(q2, n);
      boolean negative = false;
      for (int i = 0; i < n; i++) {
        negative |= ldlt.d(i) < 0.0;
      }
      if (!ldlt.ok || negative) {
        throw qError("Q was not positive semidefinite.", q, n);
      }
      if (!StateSpaceUtilJNIImpl.isStabilizable(n, m, a2, b)) {
        throw new IllegalArgumentException(
            "(A, B) pair was not stabilizable.\n\nA =\n"
                + Fmt.matrix(a2, n, n)
                + "\nB =\n"
                + Fmt.matrix(b, n, m)
                + "\n");
      }
      if (!StateSpaceUtilJNIImpl.isStabilizable(
          n, n, Dense.transpose(a2, n, n), Dense.transpose(outputMatrix(ldlt, n), n, n))) {
        throw new IllegalArgumentException(
            "(A, C) pair where Q = CᵀC was not detectable.\n\nA =\n"
                + Fmt.matrix(a2, n, n)
                + "\nQ =\n"
                + Fmt.matrix(q2, n, n)
                + "\n");
      }
    }

    double[] h = solve(a2, b, q2, rl, n, m);
    // Eigen hands the column-major result straight to the output array
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        out[j * n + i] = h[i * n + j];
      }
    }
  }

  /** The doubling algorithm (SDA) of Chu, Fan, Lin and Wang. */
  private static double[] solve(double[] a, double[] b, double[] q, double[] rl, int n, int m) {
    double[] ak = a.clone();
    double[] bt = Dense.transpose(b, n, m);
    Dense.lltSolve(rl, m, bt, n);
    double[] gk = Dense.mul(b, bt, n, m, n);
    double[] h1 = q.clone();
    double[] h;
    int iterations = 0;
    do {
      if (++iterations > MAX_ITERATIONS) {
        throw new IllegalArgumentException("DARE solver did not converge");
      }
      h = h1;
      double[] w = Dense.identity(n);
      double[] gh = Dense.mul(gk, h, n, n, n);
      for (int i = 0; i < w.length; i++) {
        w[i] += gh[i];
      }
      Dense.Lu lu = new Dense.Lu(w, n);
      double[] v1 = ak.clone();
      lu.solve(v1, n);
      double[] v2 = gk.clone();
      lu.solve(v2, n);

      double[] akT = Dense.transpose(ak, n, n);
      double[] gAdd = Dense.mul(Dense.mul(ak, v2, n, n, n), akT, n, n, n);
      double[] hAdd = Dense.mul(Dense.mul(Dense.transpose(v1, n, n), h, n, n, n), ak, n, n, n);
      h1 = new double[n * n];
      for (int i = 0; i < h1.length; i++) {
        gk[i] += gAdd[i];
        h1[i] = h[i] + hAdd[i];
      }
      ak = Dense.mul(ak, v1, n, n, n);
    } while (diffNorm(h1, h) > 1e-10 * Dense.norm(h1));
    return h1;
  }

  private static double diffNorm(double[] a, double[] b) {
    double s = 0.0;
    for (int i = 0; i < a.length; i++) {
      double d = a[i] - b[i];
      s += d * d;
    }
    return Math.sqrt(s);
  }

  private static double[] sub(double[] a, double[] b) {
    double[] c = new double[a.length];
    for (int i = 0; i < c.length; i++) {
      c[i] = a[i] - b[i];
    }
    return c;
  }

  private static boolean asymmetric(double[] a, int n) {
    return diffNorm(a, Dense.transpose(a, n, n)) > 1e-10;
  }

  /** C = sqrt(D) L^T P for Q = P^T L D L^T P. */
  private static double[] outputMatrix(Dense.Ldlt ldlt, int n) {
    double[] c = new double[n * n];
    for (int i = 0; i < n; i++) {
      double sd = Math.sqrt(ldlt.d(i));
      for (int j = i; j < n; j++) {
        c[i * n + j] = sd * (i == j ? 1.0 : ldlt.m[j * n + i]);
      }
    }
    for (int k = 0; k < n; k++) {
      int j = ldlt.trans[k];
      if (j != k) {
        for (int i = 0; i < n; i++) {
          double t = c[i * n + k];
          c[i * n + k] = c[i * n + j];
          c[i * n + j] = t;
        }
      }
    }
    return c;
  }

  private static IllegalArgumentException qError(String what, double[] q, int n) {
    return new IllegalArgumentException(what + "\n\nQ =\n" + Fmt.matrix(q, n, n) + "\n");
  }

  private static IllegalArgumentException rError(String what, double[] r, int m) {
    return new IllegalArgumentException(what + "\n\nR =\n" + Fmt.matrix(r, m, m) + "\n");
  }
}
