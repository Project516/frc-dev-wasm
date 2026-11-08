package dev.project516.frcdevwasm.shim.math.jni;

/** Row-major dense complex routines (separate re and im arrays) following Eigen. */
final class CDense {
  private static final double EPS = Dense.EPS;
  private static final double MIN = Dense.MIN;

  private CDense() {}

  /** In-plane rotation: x' = c x + conj(s) y, y' = -s x + conj(c) y over strided entries. */
  static void rotate(
      double[] re,
      double[] im,
      int xs,
      int ys,
      int stride,
      int count,
      double cr,
      double ci,
      double sr,
      double si) {
    for (int t = 0; t < count; t++) {
      int xi = xs + t * stride;
      int yi = ys + t * stride;
      double xr = re[xi];
      double xm = im[xi];
      double yr = re[yi];
      double ym = im[yi];
      re[xi] = (cr * xr - ci * xm) + (sr * yr + si * ym);
      im[xi] = (cr * xm + ci * xr) + (sr * ym - si * yr);
      re[yi] = -(sr * xr - si * xm) + (cr * yr + ci * ym);
      im[yi] = -(sr * xm + si * xr) + (cr * ym - ci * yr);
    }
  }

  /** Complex Givens rotation, returns {cr, ci, sr, si, rr, ri}. */
  static double[] givens(Cx p, Cx q) {
    Cx c;
    Cx s;
    Cx r;
    if (q.isZero()) {
      c = Cx.real(p.re() < 0 ? -1.0 : 1.0);
      s = Cx.ZERO;
      r = c.times(p);
    } else if (p.isZero()) {
      c = Cx.ZERO;
      s = q.neg().div(q.abs());
      r = Cx.real(q.abs());
    } else {
      double p1 = p.norm1();
      double q1 = q.norm1();
      if (p1 >= q1) {
        Cx ps = p.div(p1);
        double p2 = ps.abs2();
        Cx qs = q.div(p1);
        double q2 = qs.abs2();
        double u = Math.sqrt(1.0 + q2 / p2);
        if (p.re() < 0.0) {
          u = -u;
        }
        c = Cx.real(1.0 / u);
        s = qs.neg().times(ps.conj()).times(c.re() / p2);
        r = p.times(u);
      } else {
        Cx ps = p.div(q1);
        double p2 = ps.abs2();
        Cx qs = q.div(q1);
        double q2 = qs.abs2();
        double u = q1 * Math.sqrt(p2 + q2);
        if (p.re() < 0.0) {
          u = -u;
        }
        double pa = p.abs();
        ps = p.div(pa);
        c = Cx.real(pa / u);
        s = ps.conj().neg().times(q.div(u));
        r = ps.times(u);
      }
    }
    return new double[] {c.re(), c.im(), s.re(), s.im(), r.re(), r.im()};
  }

  /** Rank of the complex rows x cols matrix as ColPivHouseholderQR reports it. */
  static int colPivQrRank(double[] re, double[] im, int rows, int cols) {
    double[] qr = re.clone();
    double[] qi = im.clone();
    int size = Math.min(rows, cols);
    double[] updated = new double[cols];
    double[] direct = new double[cols];
    double maxNorm = 0.0;
    for (int k = 0; k < cols; k++) {
      double s = 0.0;
      for (int i = 0; i < rows; i++) {
        s += qr[i * cols + k] * qr[i * cols + k] + qi[i * cols + k] * qi[i * cols + k];
      }
      direct[k] = Math.sqrt(s);
      updated[k] = direct[k];
      maxNorm = Math.max(maxNorm, updated[k]);
    }
    double thresholdHelper = (maxNorm * EPS) * (maxNorm * EPS) / rows;
    double downdate = Math.sqrt(EPS);
    int nonzero = size;
    double maxPivot = 0.0;
    double[] essR = new double[rows];
    double[] essI = new double[rows];
    double[] tmpR = new double[cols];
    double[] tmpI = new double[cols];
    for (int k = 0; k < size; k++) {
      int big = k;
      double max = updated[k];
      for (int j = k + 1; j < cols; j++) {
        if (updated[j] > max) {
          max = updated[j];
          big = j;
        }
      }
      double bigSq = max * max;
      if (nonzero == size && bigSq < thresholdHelper * (rows - k)) {
        nonzero = k;
      }
      if (k != big) {
        for (int i = 0; i < rows; i++) {
          double t = qr[i * cols + k];
          qr[i * cols + k] = qr[i * cols + big];
          qr[i * cols + big] = t;
          t = qi[i * cols + k];
          qi[i * cols + k] = qi[i * cols + big];
          qi[i * cols + big] = t;
        }
        double t = updated[k];
        updated[k] = updated[big];
        updated[big] = t;
        t = direct[k];
        direct[k] = direct[big];
        direct[big] = t;
      }
      double c0r = qr[k * cols + k];
      double c0i = qi[k * cols + k];
      double tailSq = 0.0;
      for (int i = k + 1; i < rows; i++) {
        tailSq += qr[i * cols + k] * qr[i * cols + k] + qi[i * cols + k] * qi[i * cols + k];
      }
      double beta;
      double tauR;
      double tauI;
      if (tailSq <= MIN && c0i * c0i <= MIN) {
        tauR = 0.0;
        tauI = 0.0;
        beta = c0r;
        for (int i = k + 1; i < rows; i++) {
          essR[i - k - 1] = 0.0;
          essI[i - k - 1] = 0.0;
        }
      } else {
        beta = Math.sqrt(c0r * c0r + c0i * c0i + tailSq);
        if (c0r >= 0.0) {
          beta = -beta;
        }
        Cx den = new Cx(c0r - beta, c0i);
        for (int i = k + 1; i < rows; i++) {
          Cx e = new Cx(qr[i * cols + k], qi[i * cols + k]).div(den);
          essR[i - k - 1] = e.re();
          essI[i - k - 1] = e.im();
        }
        tauR = (beta - c0r) / beta;
        tauI = c0i / beta;
      }
      for (int i = k + 1; i < rows; i++) {
        qr[i * cols + k] = essR[i - k - 1];
        qi[i * cols + k] = essI[i - k - 1];
      }
      qr[k * cols + k] = beta;
      qi[k * cols + k] = 0.0;
      if (Math.abs(beta) > maxPivot) {
        maxPivot = Math.abs(beta);
      }
      houseLeft(qr, qi, cols, k, k + 1, rows - k, cols - k - 1, essR, essI, tauR, tauI, tmpR, tmpI);
      for (int j = k + 1; j < cols; j++) {
        if (updated[j] != 0.0) {
          double temp = Math.hypot(qr[k * cols + j], qi[k * cols + j]) / updated[j];
          temp = (1.0 + temp) * (1.0 - temp);
          temp = temp < 0.0 ? 0.0 : temp;
          double ratio = updated[j] / direct[j];
          double temp2 = temp * (ratio * ratio);
          if (temp2 <= downdate) {
            double s = 0.0;
            for (int i = k + 1; i < rows; i++) {
              s += qr[i * cols + j] * qr[i * cols + j] + qi[i * cols + j] * qi[i * cols + j];
            }
            direct[j] = Math.sqrt(s);
            updated[j] = direct[j];
          } else {
            updated[j] *= Math.sqrt(temp);
          }
        }
      }
    }
    double premultiplied = Math.abs(maxPivot) * (EPS * size);
    int rank = 0;
    for (int i = 0; i < nonzero; i++) {
      if (Math.hypot(qr[i * cols + i], qi[i * cols + i]) > premultiplied) {
        rank++;
      }
    }
    return rank;
  }

  /** Applies H = I - tau v v^H, v = [1, ess], to the block from the left. */
  private static void houseLeft(
      double[] mr,
      double[] mi,
      int ld,
      int r0,
      int c0,
      int nr,
      int nc,
      double[] er,
      double[] ei,
      double tr,
      double ti,
      double[] tmpR,
      double[] tmpI) {
    if (nr == 1) {
      double fr = 1.0 - tr;
      double fi = -ti;
      for (int j = 0; j < nc; j++) {
        int idx = r0 * ld + c0 + j;
        double a = mr[idx];
        double b = mi[idx];
        mr[idx] = a * fr - b * fi;
        mi[idx] = a * fi + b * fr;
      }
      return;
    }
    if (tr == 0.0 && ti == 0.0) {
      return;
    }
    for (int j = 0; j < nc; j++) {
      double sr = 0.0;
      double si = 0.0;
      for (int r = 1; r < nr; r++) {
        int idx = (r0 + r) * ld + c0 + j;
        double xr = er[r - 1];
        double xi = -ei[r - 1];
        sr += xr * mr[idx] - xi * mi[idx];
        si += xr * mi[idx] + xi * mr[idx];
      }
      int top = r0 * ld + c0 + j;
      tmpR[j] = sr + mr[top];
      tmpI[j] = si + mi[top];
      mr[top] -= tr * tmpR[j] - ti * tmpI[j];
      mi[top] -= tr * tmpI[j] + ti * tmpR[j];
    }
    for (int r = 1; r < nr; r++) {
      double ter = tr * er[r - 1] - ti * ei[r - 1];
      double tei = tr * ei[r - 1] + ti * er[r - 1];
      for (int j = 0; j < nc; j++) {
        int idx = (r0 + r) * ld + c0 + j;
        mr[idx] -= ter * tmpR[j] - tei * tmpI[j];
        mi[idx] -= ter * tmpI[j] + tei * tmpR[j];
      }
    }
  }

  /** Complex Schur decomposition A = U T U^H of a real matrix, as Eigen's ComplexSchur. */
  static final class Schur {
    final int n;
    final double[] tr;
    final double[] ti;
    final double[] ur;
    final double[] ui;

    Schur(double[] a, int n) {
      this.n = n;
      double[] h = a.clone();
      double[] tau = new double[n - 1];
      Dense.hessenberg(h, n, tau);
      ur = Dense.hessenbergQ(h, n, tau);
      Dense.zeroBelowSubdiagonal(h, n);
      tr = h;
      ti = new double[n * n];
      ui = new double[n * n];
      reduce();
    }

    private Cx t(int i, int j) {
      return new Cx(tr[i * n + j], ti[i * n + j]);
    }

    private boolean negligible(int i) {
      double d = t(i, i).norm1() + t(i + 1, i + 1).norm1();
      double sd = t(i + 1, i).norm1();
      if (sd <= d * EPS) {
        tr[(i + 1) * n + i] = 0.0;
        ti[(i + 1) * n + i] = 0.0;
        return true;
      }
      return false;
    }

    private Cx shift(int iu, int iter) {
      if ((iter == 10 || iter == 20) && iu > 1) {
        return Cx.real(Math.abs(tr[iu * n + iu - 1]) + Math.abs(tr[(iu - 1) * n + iu - 2]));
      }
      Cx t00 = t(iu - 1, iu - 1);
      Cx t01 = t(iu - 1, iu);
      Cx t10 = t(iu, iu - 1);
      Cx t11 = t(iu, iu);
      double normt = t00.abs() + t01.abs() + t10.abs() + t11.abs();
      t00 = t00.div(normt);
      t01 = t01.div(normt);
      t10 = t10.div(normt);
      t11 = t11.div(normt);
      Cx b = t01.times(t10);
      Cx c = t00.minus(t11);
      Cx disc = c.times(c).plus(b.times(4.0)).sqrt();
      Cx det = t00.times(t11).minus(b);
      Cx trace = t00.plus(t11);
      Cx e1 = trace.plus(disc).div(2.0);
      Cx e2 = trace.minus(disc).div(2.0);
      double n1 = e1.norm1();
      double n2 = e2.norm1();
      if (n1 > n2) {
        e2 = det.div(e1);
      } else if (n2 != 0.0) {
        e1 = det.div(e2);
      }
      if (e1.minus(t11).norm1() < e2.minus(t11).norm1()) {
        return e1.times(normt);
      }
      return e2.times(normt);
    }

    private void apply(int p, int q, int rowEnd, int colStart, double[] g) {
      double cr = g[0];
      double ci = g[1];
      double sr = g[2];
      double si = g[3];
      rotate(tr, ti, p * n + colStart, q * n + colStart, 1, n - colStart, cr, -ci, -sr, -si);
      rotate(tr, ti, p, q, n, rowEnd, cr, ci, -sr, si);
      rotate(ur, ui, p, q, n, n, cr, ci, -sr, si);
    }

    private void reduce() {
      int maxIters = 30 * n;
      int iu = n - 1;
      int iter = 0;
      int total = 0;
      while (true) {
        while (iu > 0) {
          if (!negligible(iu - 1)) {
            break;
          }
          iter = 0;
          --iu;
        }
        if (iu == 0) {
          break;
        }
        iter++;
        total++;
        if (total > maxIters) {
          break;
        }
        int il = iu - 1;
        while (il > 0 && !negligible(il - 1)) {
          --il;
        }
        Cx s = shift(iu, iter);
        double[] g = givens(t(il, il).minus(s), t(il + 1, il));
        apply(il, il + 1, Math.min(il + 2, iu) + 1, il, g);
        for (int i = il + 1; i < iu; i++) {
          g = givens(t(i, i - 1), t(i + 1, i - 1));
          tr[i * n + i - 1] = g[4];
          ti[i * n + i - 1] = g[5];
          tr[(i + 1) * n + i - 1] = 0.0;
          ti[(i + 1) * n + i - 1] = 0.0;
          apply(i, i + 1, Math.min(i + 2, iu) + 1, i, g);
        }
      }
    }
  }
}
