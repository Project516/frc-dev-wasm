package dev.project516.frcdevwasm.shim.math.optimization;

/**
 * Eigen::LDLT (diagonal pivoting, lower triangle only) on a dense row-major matrix, plus
 * Sleipnir's DenseRegularizedLDLT, which adds [δI, 0; 0, −γI] until the inertia is right.
 */
final class Ldlt {
  private final int numVars;
  private final int numEq;
  private final double gammaMin;
  private double[] lu;
  private int[] transpositions;
  private int n;
  private boolean ok = true;
  private double prevDelta;
  private double prevGamma;

  Ldlt(int numVars, int numEq, double gammaMin) {
    this.numVars = numVars;
    this.numEq = numEq;
    this.gammaMin = gammaMin;
  }

  /** Plain Eigen::LDLT of the lower triangle of a. */
  static Ldlt of(double[] a, int n) {
    Ldlt l = new Ldlt(n, 0, 0.0);
    l.factor(a, n);
    return l;
  }

  boolean success() {
    return ok;
  }

  double hessianRegularization() {
    return prevDelta;
  }

  double constraintJacobianRegularization() {
    return prevGamma;
  }

  /** lhs is dense row-major with its lower triangle set; it is not modified. */
  Ldlt compute(double[] lhs, int size) {
    factor(lhs, size);
    if (ok && idealInertia() && minAbsD() >= 1e-4) {
      prevDelta = 0.0;
      prevGamma = 0.0;
      return this;
    }
    double delta = prevDelta == 0.0 ? 1e-4 : Vec.max(prevDelta / 2.0, Vec.EPSILON);
    double gamma = gammaMin;
    double[] reg = new double[lhs.length];
    while (true) {
      System.arraycopy(lhs, 0, reg, 0, lhs.length);
      for (int i = 0; i < size; i++) {
        reg[i * size + i] += i < numVars ? delta : -gamma;
      }
      factor(reg, size);
      if (ok) {
        int[] inertia = inertia();
        if (inertia[0] == numVars && inertia[1] == numEq && inertia[2] == 0) {
          prevDelta = delta;
          prevGamma = gamma;
          return this;
        } else if (inertia[2] > 0) {
          if (gamma == 0.0) {
            gamma = 1e-10;
          } else {
            delta *= 10.0;
            gamma *= 10.0;
          }
        } else if (inertia[1] > numEq) {
          delta *= 10.0;
        } else if (inertia[0] > numVars) {
          gamma = gamma == 0.0 ? 1e-10 : gamma * 10.0;
        }
      } else {
        delta *= 10.0;
        gamma = gamma == 0.0 ? 1e-10 : gamma * 10.0;
      }
      if (delta > 1e20 || gamma > 1e20) {
        ok = false;
        prevDelta = delta;
        prevGamma = gamma;
        return this;
      }
    }
  }

  private boolean idealInertia() {
    int[] i = inertia();
    return i[0] == numVars && i[1] == numEq && i[2] == 0;
  }

  private double minAbsD() {
    double m = Double.POSITIVE_INFINITY;
    for (int i = 0; i < n; i++) {
      m = Math.min(m, Math.abs(lu[i * n + i]));
    }
    return m;
  }

  /** {positive, negative, zero} counts of D. */
  private int[] inertia() {
    int[] out = new int[3];
    for (int i = 0; i < n; i++) {
      double d = lu[i * n + i];
      if (d > Vec.EPSILON) {
        out[0]++;
      } else if (d < -Vec.EPSILON) {
        out[1]++;
      } else {
        out[2]++;
      }
    }
    return out;
  }

  /** ldlt_inplace<Lower>::unblocked from Eigen/src/Cholesky/LDLT.h. */
  private void factor(double[] a, int size) {
    n = size;
    double[] m = a.clone();
    lu = m;
    int[] t = new int[size];
    transpositions = t;
    ok = true;
    if (size <= 1) {
      if (size == 1) {
        t[0] = 0;
      }
      return;
    }
    boolean foundZeroPivot = false;
    double[] temp = new double[size];
    for (int k = 0; k < size; k++) {
      int big = k;
      double bigAbs = Math.abs(m[k * size + k]);
      for (int i = k + 1; i < size; i++) {
        double v = Math.abs(m[i * size + i]);
        if (v > bigAbs) {
          bigAbs = v;
          big = i;
        }
      }
      t[k] = big;
      if (k != big) {
        for (int j = 0; j < k; j++) {
          swap(m, k * size + j, big * size + j);
        }
        for (int i = big + 1; i < size; i++) {
          swap(m, i * size + k, i * size + big);
        }
        swap(m, k * size + k, big * size + big);
        for (int i = k + 1; i < big; i++) {
          double tmp = m[i * size + k];
          m[i * size + k] = m[big * size + i];
          m[big * size + i] = tmp;
        }
      }
      int rs = size - k - 1;
      if (k > 0) {
        for (int j = 0; j < k; j++) {
          temp[j] = m[j * size + j] * m[k * size + j];
        }
        double sum = 0.0;
        for (int j = 0; j < k; j++) {
          sum += m[k * size + j] * temp[j];
        }
        m[k * size + k] -= sum;
        for (int i = k + 1; i < size; i++) {
          double s = 0.0;
          for (int j = 0; j < k; j++) {
            s += m[i * size + j] * temp[j];
          }
          m[i * size + k] -= s;
        }
      }
      double akk = m[k * size + k];
      boolean valid = Math.abs(akk) > 0.0;
      if (k == 0 && !valid) {
        for (int j = 0; j < size; j++) {
          t[j] = j;
          for (int i = j + 1; i < size; i++) {
            ok &= m[i * size + j] == 0.0;
          }
        }
        return;
      }
      if (rs > 0 && valid) {
        for (int i = k + 1; i < size; i++) {
          m[i * size + k] /= akk;
        }
      } else if (rs > 0) {
        for (int i = k + 1; i < size; i++) {
          ok &= m[i * size + k] == 0.0;
        }
      }
      if (foundZeroPivot && valid) {
        ok = false;
      } else if (!valid) {
        foundZeroPivot = true;
      }
    }
  }

  private static void swap(double[] m, int a, int b) {
    double t = m[a];
    m[a] = m[b];
    m[b] = t;
  }

  /** Solves with D's pseudo-inverse, as Eigen does. */
  double[] solve(double[] b) {
    double[] x = b.clone();
    for (int k = 0; k < n; k++) {
      swap(x, k, transpositions[k]);
    }
    for (int i = 0; i < n; i++) {
      double s = x[i];
      for (int j = 0; j < i; j++) {
        s -= lu[i * n + j] * x[j];
      }
      x[i] = s;
    }
    for (int i = 0; i < n; i++) {
      double d = lu[i * n + i];
      x[i] = Math.abs(d) > Double.MIN_NORMAL ? x[i] / d : 0.0;
    }
    for (int i = n - 1; i >= 0; i--) {
      double s = x[i];
      for (int j = i + 1; j < n; j++) {
        s -= lu[j * n + i] * x[j];
      }
      x[i] = s;
    }
    for (int k = n - 1; k >= 0; k--) {
      swap(x, k, transpositions[k]);
    }
    return x;
  }
}
