package dev.project516.frcdevwasm.shim.math.jni;

public final class Stabilizability {
  private Stabilizability() {}

  /** A is states x states and B is states x inputs, both row-major. */
  public static boolean isStabilizable(int states, int inputs, double[] A, double[] B) {
    int n = states;
    int cols = n + inputs;
    double[] ev = Dense.eigenvalues(A, n);
    double[] re = new double[n * cols];
    double[] im = new double[n * cols];
    for (int i = 0; i < n; i++) {
      double lr = ev[2 * i];
      double li = ev[2 * i + 1];
      if (lr * lr + li * li < 1.0) {
        continue;
      }
      for (int r = 0; r < n; r++) {
        for (int c = 0; c < n; c++) {
          re[r * cols + c] = (r == c ? lr : 0.0) - A[r * n + c];
          im[r * cols + c] = r == c ? li : 0.0;
        }
        for (int c = 0; c < inputs; c++) {
          re[r * cols + n + c] = B[r * inputs + c];
          im[r * cols + n + c] = 0.0;
        }
      }
      if (CDense.colPivQrRank(re, im, n, cols) < n) {
        return false;
      }
    }
    return true;
  }
}
