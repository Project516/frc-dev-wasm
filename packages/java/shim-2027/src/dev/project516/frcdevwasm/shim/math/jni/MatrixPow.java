package dev.project516.frcdevwasm.shim.math.jni;

import java.util.Arrays;

/** Real matrix power with a real exponent, following Eigen's MatrixPower. */
final class MatrixPow {
  private static final double MAX_NORM_FOR_PADE = 2.789358995219730e-1;
  private static final double[] PADE_NORMS = {
    1.884160592658218e-2, 6.038881904059573e-2, 1.239917516308172e-1, 1.999045567181744e-1,
    2.789358995219730e-1
  };

  private final double[] a;
  private final int n;
  private double cond;
  private int rank;
  private int nulls;
  private double[] tr;
  private double[] ti;
  private double[] ur;
  private double[] ui;

  private MatrixPow(double[] a, int n) {
    this.a = a;
    this.n = n;
    this.rank = n;
  }

  static void pow(double[] src, int n, double exponent, double[] dst) {
    if (n == 0) {
      return;
    }
    if (n == 1) {
      dst[0] = Math.pow(src[0], exponent);
      return;
    }
    if (!Double.isFinite(exponent)) {
      Arrays.fill(dst, 0, n * n, Double.NaN);
      return;
    }
    MatrixPow mp = new MatrixPow(src, n);
    double p = exponent;
    double intpart = Math.floor(p);
    p -= intpart;
    if (mp.cond == 0.0 && p != 0.0) {
      mp.initialize();
    }
    if (p > 0.5 && p > (1.0 - p) * Math.pow(mp.cond, p)) {
      p -= 1.0;
      intpart += 1.0;
    }
    double[] res = mp.intPower(Dense.identity(n), intpart);
    if (p != 0.0) {
      res = mp.fracPower(res, p);
    }
    System.arraycopy(res, 0, dst, 0, n * n);
  }

  private double[] intPower(double[] res, double p) {
    double pp = Math.abs(p);
    double[] tmp = p < 0 ? new Dense.Lu(a, n).inverse() : a.clone();
    while (true) {
      if (pp % 2 >= 1) {
        res = Dense.mul(tmp, res, n, n, n);
      }
      pp /= 2;
      if (pp < 1) {
        break;
      }
      tmp = Dense.mul(tmp, tmp, n, n, n);
    }
    return res;
  }

  private void initialize() {
    CDense.Schur s = new CDense.Schur(a, n);
    tr = s.tr;
    ti = s.ti;
    ur = s.ur;
    ui = s.ui;
    double max = 0.0;
    double min = Double.POSITIVE_INFINITY;
    for (int i = 0; i < n; i++) {
      double d = Math.hypot(tr[i * n + i], ti[i * n + i]);
      max = Math.max(max, d);
      min = Math.min(min, d);
    }
    cond = max / min;
    for (int i = n - 1; i >= 0; --i) {
      if (rank <= 2) {
        return;
      }
      if (tr[i * n + i] == 0.0 && ti[i * n + i] == 0.0) {
        for (int j = i + 1; j < rank; ++j) {
          Cx ev = new Cx(tr[j * n + j], ti[j * n + j]);
          double[] g = CDense.givens(new Cx(tr[(j - 1) * n + j], ti[(j - 1) * n + j]), ev);
          double cr = g[0];
          double ci = g[1];
          double sr = g[2];
          double si = g[3];
          CDense.rotate(tr, ti, j - 1, j, n, n, cr, ci, -sr, si);
          CDense.rotate(tr, ti, (j - 1) * n, j * n, 1, n, cr, -ci, -sr, -si);
          tr[(j - 1) * n + j - 1] = ev.re();
          ti[(j - 1) * n + j - 1] = ev.im();
          tr[j * n + j] = 0.0;
          ti[j * n + j] = 0.0;
          CDense.rotate(ur, ui, j - 1, j, n, n, cr, ci, -sr, si);
        }
        --rank;
      }
    }
    nulls = n - rank;
  }

  private double[] fracPower(double[] res, double p) {
    CMat tRR = new CMat(rank, rank);
    for (int i = 0; i < rank; i++) {
      for (int j = 0; j < rank; j++) {
        tRR.re[i * rank + j] = tr[i * n + j];
        tRR.im[i * rank + j] = ti[i * n + j];
      }
    }
    CMat blockTp = atomic(tRR, p);
    CMat fT = new CMat(n, n);
    for (int i = 0; i < rank; i++) {
      for (int j = 0; j < rank; j++) {
        fT.re[i * n + j] = blockTp.re[i * rank + j];
        fT.im[i * n + j] = blockTp.im[i * rank + j];
      }
    }
    if (nulls != 0) {
      CMat tRN = new CMat(rank, nulls);
      for (int i = 0; i < rank; i++) {
        for (int j = 0; j < nulls; j++) {
          tRN.re[i * nulls + j] = tr[i * n + rank + j];
          tRN.im[i * nulls + j] = ti[i * n + rank + j];
        }
      }
      CMat x = tRR.triu().solveUpper(blockTp.mul(tRN, false));
      for (int i = 0; i < rank; i++) {
        for (int j = 0; j < nulls; j++) {
          fT.re[i * n + rank + j] = x.re[i * nulls + j];
          fT.im[i * n + rank + j] = x.im[i * nulls + j];
        }
      }
    }
    CMat u = new CMat(n, n);
    CMat uh = new CMat(n, n);
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        u.re[i * n + j] = ur[i * n + j];
        u.im[i * n + j] = ui[i * n + j];
        uh.re[j * n + i] = ur[i * n + j];
        uh.im[j * n + i] = -ui[i * n + j];
      }
    }
    CMat full = u.mul(fT.mul(uh, true), false);
    return Dense.mul(full.re, res, n, n, n);
  }

  private static CMat atomic(CMat a, double p) {
    CMat res = new CMat(a.rows, a.cols);
    switch (a.rows) {
      case 0:
        return res;
      case 1:
        res.set(0, 0, a.get(0, 0).pow(p));
        return res;
      case 2:
        compute2x2(a, res, p);
        return res;
      default:
        return computeBig(a, p);
    }
  }

  private static void compute2x2(CMat a, CMat res, double p) {
    res.set(0, 0, a.get(0, 0).pow(p));
    for (int i = 1; i < a.cols; i++) {
      Cx curr = a.get(i, i);
      Cx prev = a.get(i - 1, i - 1);
      res.set(i, i, curr.pow(p));
      Cx sup;
      if (prev.same(curr)) {
        sup = Cx.real(p).times(curr.pow(p - 1));
      } else if (2 * prev.abs() < curr.abs() || 2 * curr.abs() < prev.abs()) {
        sup = res.get(i, i).minus(res.get(i - 1, i - 1)).div(curr.minus(prev));
      } else {
        sup = superDiag(curr, prev, p);
      }
      res.set(i - 1, i, sup.times(a.get(i - 1, i)));
    }
  }

  private static Cx superDiag(Cx curr, Cx prev, double p) {
    Cx logCurr = curr.log();
    Cx logPrev = prev.log();
    double unwinding = Math.ceil((logCurr.minus(logPrev).im() - Math.PI) / (2 * Math.PI));
    Cx diff = curr.minus(prev);
    Cx w = diff.div(prev).log1p().div(2.0).plus(new Cx(0.0, Math.PI * unwinding));
    return Cx.real(2.0).times(logCurr.plus(logPrev).times(0.5 * p).exp())
        .times(w.times(p).sinh())
        .div(diff);
  }

  private static int padeDegree(double norm) {
    int degree = 3;
    for (; degree <= 7; ++degree) {
      if (norm <= PADE_NORMS[degree - 3]) {
        break;
      }
    }
    return degree;
  }

  private static CMat computeBig(CMat a, double p) {
    int n = a.rows;
    CMat t = a.triu();
    CMat id = CMat.identity(n);
    CMat iMinusT;
    int degree;
    int squarings = 0;
    boolean extra = false;
    while (true) {
      iMinusT = id.minus(t);
      double norm = iMinusT.maxColAbsSum();
      if (norm < MAX_NORM_FOR_PADE) {
        degree = padeDegree(norm);
        int degree2 = padeDegree(norm / 2);
        if (degree - degree2 <= 1 || extra) {
          break;
        }
        extra = true;
      }
      if (++squarings > 100) {
        CMat nan = new CMat(n, n);
        Arrays.fill(nan.re, Double.NaN);
        return nan;
      }
      t = sqrtTriangular(t).triu();
    }
    CMat res = pade(degree, iMinusT, p);
    for (; squarings > 0; --squarings) {
      compute2x2(a, res, Math.scalb(p, -squarings));
      res = res.mul(res, true);
    }
    compute2x2(a, res, p);
    return res;
  }

  private static CMat pade(int degree, CMat iMinusT, double p) {
    int n = iMinusT.rows;
    CMat id = CMat.identity(n);
    int i = 2 * degree;
    CMat res = iMinusT.scale((p - degree) / (2 * i - 2));
    for (--i; i != 0; --i) {
      double coef;
      if (i == 1) {
        coef = -p;
      } else if ((i & 1) != 0) {
        coef = (-p - (i / 2)) / (2 * i);
      } else {
        coef = (p - (i / 2)) / (2 * i - 2);
      }
      res = id.plus(res).solveUpper(iMinusT.scale(coef));
    }
    return res.plus(id);
  }

  private static CMat sqrtTriangular(CMat t) {
    int n = t.rows;
    CMat r = new CMat(n, n);
    for (int i = 0; i < n; i++) {
      r.set(i, i, t.get(i, i).sqrt());
    }
    for (int j = 1; j < n; j++) {
      for (int i = j - 1; i >= 0; i--) {
        Cx tmp = Cx.ZERO;
        for (int k = i + 1; k < j; k++) {
          tmp = tmp.plus(r.get(i, k).times(r.get(k, j)));
        }
        r.set(i, j, t.get(i, j).minus(tmp).div(r.get(i, i).plus(r.get(j, j))));
      }
    }
    return r;
  }

  /** Dense complex matrix, row-major. */
  private static final class CMat {
    final int rows;
    final int cols;
    final double[] re;
    final double[] im;

    CMat(int rows, int cols) {
      this.rows = rows;
      this.cols = cols;
      re = new double[rows * cols];
      im = new double[rows * cols];
    }

    static CMat identity(int n) {
      CMat m = new CMat(n, n);
      for (int i = 0; i < n; i++) {
        m.re[i * n + i] = 1.0;
      }
      return m;
    }

    Cx get(int i, int j) {
      return new Cx(re[i * cols + j], im[i * cols + j]);
    }

    void set(int i, int j, Cx v) {
      re[i * cols + j] = v.re();
      im[i * cols + j] = v.im();
    }

    CMat triu() {
      CMat m = new CMat(rows, cols);
      for (int i = 0; i < rows; i++) {
        for (int j = i; j < cols; j++) {
          m.re[i * cols + j] = re[i * cols + j];
          m.im[i * cols + j] = im[i * cols + j];
        }
      }
      return m;
    }

    CMat plus(CMat o) {
      CMat m = new CMat(rows, cols);
      for (int i = 0; i < re.length; i++) {
        m.re[i] = re[i] + o.re[i];
        m.im[i] = im[i] + o.im[i];
      }
      return m;
    }

    CMat minus(CMat o) {
      CMat m = new CMat(rows, cols);
      for (int i = 0; i < re.length; i++) {
        m.re[i] = re[i] - o.re[i];
        m.im[i] = im[i] - o.im[i];
      }
      return m;
    }

    CMat scale(double s) {
      CMat m = new CMat(rows, cols);
      for (int i = 0; i < re.length; i++) {
        m.re[i] = s * re[i];
        m.im[i] = s * im[i];
      }
      return m;
    }

    /** this * o, reading only the upper triangle of this if upperLeft. */
    CMat mul(CMat o, boolean upperLeft) {
      CMat m = new CMat(rows, o.cols);
      for (int i = 0; i < rows; i++) {
        for (int k = upperLeft ? i : 0; k < cols; k++) {
          double ar = re[i * cols + k];
          double ai = im[i * cols + k];
          for (int j = 0; j < o.cols; j++) {
            double br = o.re[k * o.cols + j];
            double bi = o.im[k * o.cols + j];
            m.re[i * o.cols + j] += ar * br - ai * bi;
            m.im[i * o.cols + j] += ar * bi + ai * br;
          }
        }
      }
      return m;
    }

    /** Solves U X = b for upper triangular U = this. */
    CMat solveUpper(CMat b) {
      CMat x = new CMat(b.rows, b.cols);
      for (int j = 0; j < b.cols; j++) {
        for (int i = rows - 1; i >= 0; i--) {
          Cx s = b.get(i, j);
          for (int k = i + 1; k < rows; k++) {
            s = s.minus(get(i, k).times(x.get(k, j)));
          }
          x.set(i, j, s.div(get(i, i)));
        }
      }
      return x;
    }

    double maxColAbsSum() {
      double max = 0.0;
      for (int j = 0; j < cols; j++) {
        double s = 0.0;
        for (int i = 0; i < rows; i++) {
          s += Math.hypot(re[i * cols + j], im[i * cols + j]);
        }
        max = Math.max(max, s);
      }
      return max;
    }
  }
}
