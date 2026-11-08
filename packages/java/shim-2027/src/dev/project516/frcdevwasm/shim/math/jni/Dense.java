package dev.project516.frcdevwasm.shim.math.jni;

/** Row-major dense real linear algebra following the Eigen routines wpimath relies on. */
final class Dense {
  static final double EPS = 2.220446049250313e-16;
  static final double MIN = Double.MIN_NORMAL;

  private Dense() {}

  static double[] mul(double[] a, double[] b, int n, int k, int m) {
    double[] c = new double[n * m];
    for (int i = 0; i < n; i++) {
      int co = i * m;
      for (int p = 0; p < k; p++) {
        double v = a[i * k + p];
        int bo = p * m;
        for (int j = 0; j < m; j++) {
          c[co + j] += v * b[bo + j];
        }
      }
    }
    return c;
  }

  static double[] transpose(double[] a, int rows, int cols) {
    double[] t = new double[a.length];
    for (int i = 0; i < rows; i++) {
      for (int j = 0; j < cols; j++) {
        t[j * rows + i] = a[i * cols + j];
      }
    }
    return t;
  }

  static double[] identity(int n) {
    double[] a = new double[n * n];
    for (int i = 0; i < n; i++) {
      a[i * n + i] = 1.0;
    }
    return a;
  }

  /** Frobenius norm. */
  static double norm(double[] a) {
    double s = 0.0;
    for (double v : a) {
      s += v * v;
    }
    return Math.sqrt(s);
  }

  /** LU with partial pivoting. */
  static final class Lu {
    final int n;
    final double[] lu;
    final int[] piv;

    Lu(double[] a, int n) {
      this.n = n;
      lu = a.clone();
      piv = new int[n];
      for (int k = 0; k < n; k++) {
        int p = k;
        double big = Math.abs(lu[k * n + k]);
        for (int i = k + 1; i < n; i++) {
          double v = Math.abs(lu[i * n + k]);
          if (v > big) {
            big = v;
            p = i;
          }
        }
        piv[k] = p;
        if (big != 0.0) {
          if (p != k) {
            swapRows(lu, n, k, p);
          }
          double d = lu[k * n + k];
          for (int i = k + 1; i < n; i++) {
            lu[i * n + k] /= d;
          }
        }
        for (int i = k + 1; i < n; i++) {
          double l = lu[i * n + k];
          int io = i * n;
          int ko = k * n;
          for (int j = k + 1; j < n; j++) {
            lu[io + j] -= l * lu[ko + j];
          }
        }
      }
    }

    /** Solves A X = B in place; b is n x m. */
    void solve(double[] b, int m) {
      for (int k = 0; k < n; k++) {
        if (piv[k] != k) {
          swapRows(b, m, k, piv[k]);
        }
      }
      for (int i = 0; i < n; i++) {
        for (int k = 0; k < i; k++) {
          double l = lu[i * n + k];
          for (int j = 0; j < m; j++) {
            b[i * m + j] -= l * b[k * m + j];
          }
        }
      }
      for (int i = n - 1; i >= 0; i--) {
        for (int k = i + 1; k < n; k++) {
          double u = lu[i * n + k];
          for (int j = 0; j < m; j++) {
            b[i * m + j] -= u * b[k * m + j];
          }
        }
        double d = lu[i * n + i];
        for (int j = 0; j < m; j++) {
          b[i * m + j] /= d;
        }
      }
    }

    double[] inverse() {
      double[] x = identity(n);
      solve(x, n);
      return x;
    }
  }

  static void swapRows(double[] a, int cols, int r1, int r2) {
    for (int j = 0; j < cols; j++) {
      double t = a[r1 * cols + j];
      a[r1 * cols + j] = a[r2 * cols + j];
      a[r2 * cols + j] = t;
    }
  }

  /** In-place Cholesky of the lower triangle. Returns false if not positive definite. */
  static boolean llt(double[] a, int n) {
    for (int k = 0; k < n; k++) {
      double x = a[k * n + k];
      for (int j = 0; j < k; j++) {
        x -= a[k * n + j] * a[k * n + j];
      }
      if (x <= 0.0) {
        return false;
      }
      x = Math.sqrt(x);
      a[k * n + k] = x;
      for (int i = k + 1; i < n; i++) {
        double v = a[i * n + k];
        for (int j = 0; j < k; j++) {
          v -= a[i * n + j] * a[k * n + j];
        }
        a[i * n + k] = v / x;
      }
    }
    return true;
  }

  /** Solves (L L^T) X = B in place; b is n x m. */
  static void lltSolve(double[] l, int n, double[] b, int m) {
    for (int i = 0; i < n; i++) {
      for (int k = 0; k < i; k++) {
        double v = l[i * n + k];
        for (int j = 0; j < m; j++) {
          b[i * m + j] -= v * b[k * m + j];
        }
      }
      double d = l[i * n + i];
      for (int j = 0; j < m; j++) {
        b[i * m + j] /= d;
      }
    }
    for (int i = n - 1; i >= 0; i--) {
      for (int k = i + 1; k < n; k++) {
        double v = l[k * n + i];
        for (int j = 0; j < m; j++) {
          b[i * m + j] -= v * b[k * m + j];
        }
      }
      double d = l[i * n + i];
      for (int j = 0; j < m; j++) {
        b[i * m + j] /= d;
      }
    }
  }

  /**
   * Rank one update or downdate of a Cholesky factor stored row-major, lower or upper. Returns the
   * failing column, or -1.
   */
  static int lltRankUpdate(double[] mat, int n, double[] vec, double sigma, boolean lower) {
    // element (i, j) of the lower-triangle view
    int si = lower ? n : 1;
    int sj = lower ? 1 : n;
    double[] temp = new double[n];
    if (sigma > 0) {
      double rs = Math.sqrt(sigma);
      for (int i = 0; i < n; i++) {
        temp[i] = rs * vec[i];
      }
      for (int i = 0; i < n; i++) {
        int d = i * si + i * sj;
        double[] g = givens(mat[d], -temp[i]);
        double c = g[0];
        double s = g[1];
        mat[d] = g[2];
        if (c == 1.0 && s == 0.0) {
          continue;
        }
        for (int r = i + 1; r < n; r++) {
          int idx = r * si + i * sj;
          double x = mat[idx];
          double y = temp[r];
          mat[idx] = c * x + s * y;
          temp[r] = -s * x + c * y;
        }
      }
    } else {
      System.arraycopy(vec, 0, temp, 0, n);
      double beta = 1.0;
      for (int j = 0; j < n; j++) {
        int d = j * si + j * sj;
        double ljj = mat[d];
        double dj = ljj * ljj;
        double wj = temp[j];
        double swj2 = sigma * wj * wj;
        double gamma = dj * beta + swj2;
        double x = dj + swj2 / beta;
        if (x <= 0.0) {
          return j;
        }
        double nljj = Math.sqrt(x);
        mat[d] = nljj;
        beta += swj2 / dj;
        for (int r = j + 1; r < n; r++) {
          int idx = r * si + j * sj;
          temp[r] -= (wj / ljj) * mat[idx];
        }
        if (gamma != 0.0) {
          for (int r = j + 1; r < n; r++) {
            int idx = r * si + j * sj;
            mat[idx] = (nljj / ljj) * mat[idx] + (nljj * sigma * wj / gamma) * temp[r];
          }
        }
      }
    }
    return -1;
  }

  /** Real Givens rotation, returns {c, s, r}. */
  static double[] givens(double p, double q) {
    double c;
    double s;
    double r;
    if (q == 0.0) {
      c = p < 0 ? -1.0 : 1.0;
      s = 0.0;
      r = Math.abs(p);
    } else if (p == 0.0) {
      c = 0.0;
      s = q < 0 ? 1.0 : -1.0;
      r = Math.abs(q);
    } else if (Math.abs(p) > Math.abs(q)) {
      double t = q / p;
      double u = Math.sqrt(1.0 + t * t);
      if (p < 0) {
        u = -u;
      }
      c = 1.0 / u;
      s = -t * c;
      r = p * u;
    } else {
      double t = p / q;
      double u = Math.sqrt(1.0 + t * t);
      if (q < 0) {
        u = -u;
      }
      s = -1.0 / u;
      c = -t * s;
      r = q * u;
    }
    return new double[] {c, s, r};
  }

  /** LDLT with diagonal pivoting of the lower triangle. */
  static final class Ldlt {
    final int n;
    final double[] m;
    final int[] trans;
    boolean ok = true;

    Ldlt(double[] a, int n) {
      this.n = n;
      m = a.clone();
      trans = new int[n];
      factor();
    }

    private void factor() {
      if (n <= 1) {
        return;
      }
      double[] temp = new double[n];
      boolean foundZeroPivot = false;
      for (int k = 0; k < n; k++) {
        int big = k;
        double max = Math.abs(m[k * n + k]);
        for (int i = k + 1; i < n; i++) {
          double v = Math.abs(m[i * n + i]);
          if (v > max) {
            max = v;
            big = i;
          }
        }
        trans[k] = big;
        if (k != big) {
          for (int j = 0; j < k; j++) {
            double t = m[k * n + j];
            m[k * n + j] = m[big * n + j];
            m[big * n + j] = t;
          }
          for (int r = big + 1; r < n; r++) {
            double t = m[r * n + k];
            m[r * n + k] = m[r * n + big];
            m[r * n + big] = t;
          }
          double t = m[k * n + k];
          m[k * n + k] = m[big * n + big];
          m[big * n + big] = t;
          for (int i = k + 1; i < big; i++) {
            double tmp = m[i * n + k];
            m[i * n + k] = m[big * n + i];
            m[big * n + i] = tmp;
          }
        }
        int rs = n - k - 1;
        if (k > 0) {
          for (int j = 0; j < k; j++) {
            temp[j] = m[j * n + j] * m[k * n + j];
          }
          double dot = 0.0;
          for (int j = 0; j < k; j++) {
            dot += m[k * n + j] * temp[j];
          }
          m[k * n + k] -= dot;
          for (int i = k + 1; i < n; i++) {
            double s = 0.0;
            for (int j = 0; j < k; j++) {
              s += m[i * n + j] * temp[j];
            }
            m[i * n + k] -= s;
          }
        }
        double akk = m[k * n + k];
        boolean valid = Math.abs(akk) > 0.0;
        if (k == 0 && !valid) {
          for (int j = 0; j < n; j++) {
            trans[j] = j;
            for (int r = j + 1; r < n; r++) {
              ok = ok && m[r * n + j] == 0.0;
            }
          }
          return;
        }
        if (rs > 0 && valid) {
          for (int i = k + 1; i < n; i++) {
            m[i * n + k] /= akk;
          }
        } else if (rs > 0) {
          for (int i = k + 1; i < n; i++) {
            ok = ok && m[i * n + k] == 0.0;
          }
        }
        if (foundZeroPivot && valid) {
          ok = false;
        } else if (!valid) {
          foundZeroPivot = true;
        }
      }
    }

    double d(int i) {
      return m[i * n + i];
    }
  }

  /**
   * Eigenvalues of a general real matrix via Hessenberg reduction and Francis double-shift QR.
   * Returns interleaved {re0, im0, re1, im1, ...}. Not-converged entries are left as computed.
   */
  static double[] eigenvalues(double[] a, int n) {
    double[] ev = new double[2 * n];
    double scale = 0.0;
    for (double v : a) {
      scale = Math.max(scale, Math.abs(v));
    }
    if (scale < MIN) {
      return ev;
    }
    double[] t = new double[n * n];
    for (int i = 0; i < t.length; i++) {
      t[i] = a[i] / scale;
    }
    double[] tau = new double[Math.max(n - 1, 0)];
    hessenberg(t, n, tau);
    zeroBelowSubdiagonal(t, n);
    new RealSchur(t, n).run();
    for (int i = 0; i < t.length; i++) {
      t[i] *= scale;
    }
    int i = 0;
    while (i < n) {
      if (i == n - 1 || t[(i + 1) * n + i] == 0.0) {
        ev[2 * i] = t[i * n + i];
        i++;
      } else {
        double tii = t[i * n + i];
        double t11 = t[(i + 1) * n + i + 1];
        double p = 0.5 * (tii - t11);
        double t0 = t[(i + 1) * n + i];
        double t1 = t[i * n + i + 1];
        double maxval = Math.max(Math.abs(p), Math.max(Math.abs(t0), Math.abs(t1)));
        t0 /= maxval;
        t1 /= maxval;
        double p0 = p / maxval;
        double z = maxval * Math.sqrt(Math.abs(p0 * p0 + t0 * t1));
        ev[2 * i] = t11 + p;
        ev[2 * i + 1] = z;
        ev[2 * i + 2] = t11 + p;
        ev[2 * i + 3] = -z;
        i += 2;
      }
    }
    return ev;
  }

  static void zeroBelowSubdiagonal(double[] h, int n) {
    for (int i = 2; i < n; i++) {
      for (int j = 0; j < i - 1; j++) {
        h[i * n + j] = 0.0;
      }
    }
  }

  /**
   * In-place Householder reduction to Hessenberg form. Essential parts stay below the
   * subdiagonal, tau holds the n - 1 coefficients.
   */
  static void hessenberg(double[] a, int n, double[] tau) {
    double[] tmp = new double[n];
    double[] ess = new double[n];
    for (int i = 0; i < n - 1; i++) {
      int rem = n - i - 1;
      double c0 = a[(i + 1) * n + i];
      double tailSq = 0.0;
      for (int r = i + 2; r < n; r++) {
        tailSq += a[r * n + i] * a[r * n + i];
      }
      double h;
      double beta;
      if (tailSq <= MIN) {
        h = 0.0;
        beta = c0;
        for (int r = i + 2; r < n; r++) {
          a[r * n + i] = 0.0;
        }
      } else {
        beta = Math.sqrt(c0 * c0 + tailSq);
        if (c0 >= 0.0) {
          beta = -beta;
        }
        double den = c0 - beta;
        for (int r = i + 2; r < n; r++) {
          a[r * n + i] /= den;
        }
        h = (beta - c0) / beta;
      }
      a[(i + 1) * n + i] = beta;
      tau[i] = h;
      for (int r = 0; r < rem - 1; r++) {
        ess[r] = a[(i + 2 + r) * n + i];
      }
      houseLeft(a, n, i + 1, i + 1, rem, rem, ess, h, tmp);
      houseRight(a, n, 0, i + 1, n, rem, ess, h);
    }
  }

  /** Q of the Hessenberg decomposition, n x n. */
  static double[] hessenbergQ(double[] packed, int n, double[] tau) {
    double[] q = identity(n);
    double[] tmp = new double[n];
    double[] ess = new double[n];
    for (int k = n - 2; k >= 0; k--) {
      int corner = n - k - 1;
      for (int r = 0; r < corner - 1; r++) {
        ess[r] = packed[(k + 2 + r) * n + k];
      }
      houseLeft(q, n, k + 1, k + 1, corner, corner, ess, tau[k], tmp);
    }
    return q;
  }

  /** Applies H = I - tau v v^T, v = [1, ess], to the block from the left. */
  static void houseLeft(
      double[] m, int ld, int r0, int c0, int nr, int nc, double[] ess, double tau, double[] tmp) {
    if (nr == 1) {
      double f = 1.0 - tau;
      for (int j = 0; j < nc; j++) {
        m[r0 * ld + c0 + j] *= f;
      }
      return;
    }
    if (tau == 0.0) {
      return;
    }
    for (int j = 0; j < nc; j++) {
      tmp[j] = 0.0;
    }
    for (int r = 1; r < nr; r++) {
      double e = ess[r - 1];
      int base = (r0 + r) * ld + c0;
      for (int j = 0; j < nc; j++) {
        tmp[j] += e * m[base + j];
      }
    }
    int b0 = r0 * ld + c0;
    for (int j = 0; j < nc; j++) {
      tmp[j] += m[b0 + j];
      m[b0 + j] -= tau * tmp[j];
    }
    for (int r = 1; r < nr; r++) {
      double te = tau * ess[r - 1];
      int base = (r0 + r) * ld + c0;
      for (int j = 0; j < nc; j++) {
        m[base + j] -= te * tmp[j];
      }
    }
  }

  /** Applies H = I - tau v v^T, v = [1, ess], to the block from the right. */
  static void houseRight(
      double[] m, int ld, int r0, int c0, int nr, int nc, double[] ess, double tau) {
    if (nc == 1) {
      double f = 1.0 - tau;
      for (int i = 0; i < nr; i++) {
        m[(r0 + i) * ld + c0] *= f;
      }
      return;
    }
    if (tau == 0.0) {
      return;
    }
    for (int i = 0; i < nr; i++) {
      int base = (r0 + i) * ld + c0;
      double s = 0.0;
      for (int c = 1; c < nc; c++) {
        s += m[base + c] * ess[c - 1];
      }
      double t = s + m[base];
      m[base] -= tau * t;
      double tt = tau * t;
      for (int c = 1; c < nc; c++) {
        m[base + c] -= tt * ess[c - 1];
      }
    }
  }

  /** Real Schur iteration without accumulating the orthogonal factor. */
  private static final class RealSchur {
    final double[] t;
    final int n;
    double exshift;
    final double[] shift = new double[3];
    final double[] v = new double[3];
    final double[] ess = new double[2];
    final double[] ws;
    double tau;
    double beta;
    int im;

    RealSchur(double[] t, int n) {
      this.t = t;
      this.n = n;
      ws = new double[n];
    }

    double at(int i, int j) {
      return t[i * n + j];
    }

    void run() {
      int maxIters = 40 * n;
      int iu = n - 1;
      int iter = 0;
      int totalIter = 0;
      double norm = 0.0;
      for (int j = 0; j < n; j++) {
        for (int i = 0; i <= Math.min(n - 1, j + 1); i++) {
          norm += Math.abs(at(i, j));
        }
      }
      double considerAsZero = Math.max(norm * EPS * EPS, MIN);
      if (norm == 0.0) {
        return;
      }
      while (iu >= 0) {
        int il = findSmallSubdiagEntry(iu, considerAsZero);
        if (il == iu) {
          t[iu * n + iu] += exshift;
          if (iu > 0) {
            t[iu * n + iu - 1] = 0.0;
          }
          iu--;
          iter = 0;
        } else if (il == iu - 1) {
          splitOffTwoRows(iu);
          iu -= 2;
          iter = 0;
        } else {
          computeShift(iu, iter);
          iter++;
          totalIter++;
          if (totalIter > maxIters) {
            break;
          }
          initFrancisQRStep(il, iu);
          performFrancisQRStep(il, iu);
        }
      }
    }

    int findSmallSubdiagEntry(int iu, double considerAsZero) {
      int res = iu;
      while (res > 0) {
        double s = Math.abs(at(res - 1, res - 1)) + Math.abs(at(res, res));
        s = Math.max(s * EPS, considerAsZero);
        if (Math.abs(at(res, res - 1)) <= s) {
          break;
        }
        res--;
      }
      return res;
    }

    void splitOffTwoRows(int iu) {
      double p = 0.5 * (at(iu - 1, iu - 1) - at(iu, iu));
      double q = p * p + at(iu, iu - 1) * at(iu - 1, iu);
      t[iu * n + iu] += exshift;
      t[(iu - 1) * n + iu - 1] += exshift;
      if (q >= 0.0) {
        double z = Math.sqrt(Math.abs(q));
        double[] g = givens(p >= 0.0 ? p + z : p - z, at(iu, iu - 1));
        double c = g[0];
        double s = g[1];
        for (int j = iu - 1; j < n; j++) {
          double x = at(iu - 1, j);
          double y = at(iu, j);
          t[(iu - 1) * n + j] = c * x - s * y;
          t[iu * n + j] = s * x + c * y;
        }
        for (int i = 0; i <= iu; i++) {
          double x = at(i, iu - 1);
          double y = at(i, iu);
          t[i * n + iu - 1] = c * x - s * y;
          t[i * n + iu] = s * x + c * y;
        }
        t[iu * n + iu - 1] = 0.0;
      }
      if (iu > 1) {
        t[(iu - 1) * n + iu - 2] = 0.0;
      }
    }

    void computeShift(int iu, int iter) {
      shift[0] = at(iu, iu);
      shift[1] = at(iu - 1, iu - 1);
      shift[2] = at(iu, iu - 1) * at(iu - 1, iu);
      if (iter > 0 && iter % 16 == 0) {
        if (iter % 32 != 0) {
          exshift += shift[0];
          for (int i = 0; i <= iu; i++) {
            t[i * n + i] -= shift[0];
          }
          double s = Math.abs(at(iu, iu - 1)) + Math.abs(at(iu - 1, iu - 2));
          shift[0] = 0.75 * s;
          shift[1] = 0.75 * s;
          shift[2] = -0.4375 * s * s;
        } else {
          double s = (shift[1] - shift[0]) / 2.0;
          s = s * s + shift[2];
          if (s > 0.0) {
            s = Math.sqrt(s);
            if (shift[1] < shift[0]) {
              s = -s;
            }
            s = s + (shift[1] - shift[0]) / 2.0;
            s = shift[0] - shift[2] / s;
            exshift += s;
            for (int i = 0; i <= iu; i++) {
              t[i * n + i] -= s;
            }
            shift[0] = 0.964;
            shift[1] = 0.964;
            shift[2] = 0.964;
          }
        }
      }
    }

    void initFrancisQRStep(int il, int iu) {
      for (im = iu - 2; im >= il; --im) {
        double tmm = at(im, im);
        double r = shift[0] - tmm;
        double s = shift[1] - tmm;
        v[0] = (r * s - shift[2]) / at(im + 1, im) + at(im, im + 1);
        v[1] = at(im + 1, im + 1) - tmm - r - s;
        v[2] = at(im + 2, im + 1);
        if (im == il) {
          break;
        }
        double lhs = at(im, im - 1) * (Math.abs(v[1]) + Math.abs(v[2]));
        double rhs = v[0] * (Math.abs(at(im - 1, im - 1)) + Math.abs(tmm) + Math.abs(at(im + 1, im + 1)));
        if (Math.abs(lhs) < EPS * rhs) {
          break;
        }
      }
    }

    /** Householder of v[0..len) into ess, tau, beta. */
    void makeHouseholder(double[] x, int len) {
      double tailSq = 0.0;
      for (int i = 1; i < len; i++) {
        tailSq += x[i] * x[i];
      }
      double c0 = x[0];
      if (tailSq <= MIN) {
        tau = 0.0;
        beta = c0;
        for (int i = 1; i < len; i++) {
          ess[i - 1] = 0.0;
        }
      } else {
        beta = Math.sqrt(c0 * c0 + tailSq);
        if (c0 >= 0.0) {
          beta = -beta;
        }
        double den = c0 - beta;
        for (int i = 1; i < len; i++) {
          ess[i - 1] = x[i] / den;
        }
        tau = (beta - c0) / beta;
      }
    }

    void performFrancisQRStep(int il, int iu) {
      double[] w = new double[3];
      double[] vv = new double[3];
      for (int k = im; k <= iu - 2; ++k) {
        boolean first = k == im;
        if (first) {
          System.arraycopy(v, 0, vv, 0, 3);
        } else {
          vv[0] = at(k, k - 1);
          vv[1] = at(k + 1, k - 1);
          vv[2] = at(k + 2, k - 1);
        }
        makeHouseholder(vv, 3);
        if (beta != 0.0) {
          if (first && k > il) {
            t[k * n + k - 1] = -at(k, k - 1);
          } else if (!first) {
            t[k * n + k - 1] = beta;
          }
          houseLeft(t, n, k, k, 3, n - k, ess, tau, ws);
          houseRight(t, n, 0, k, Math.min(iu, k + 3) + 1, 3, ess, tau);
        }
      }
      w[0] = at(iu - 1, iu - 2);
      w[1] = at(iu, iu - 2);
      makeHouseholder(w, 2);
      if (beta != 0.0) {
        t[(iu - 1) * n + iu - 2] = beta;
        houseLeft(t, n, iu - 1, iu - 1, 2, n - iu + 1, ess, tau, ws);
        houseRight(t, n, 0, iu - 1, iu + 1, 2, ess, tau);
      }
      for (int i = im + 2; i <= iu; ++i) {
        t[i * n + i - 2] = 0.0;
        if (i > im + 2) {
          t[i * n + i - 3] = 0.0;
        }
      }
    }
  }
}
