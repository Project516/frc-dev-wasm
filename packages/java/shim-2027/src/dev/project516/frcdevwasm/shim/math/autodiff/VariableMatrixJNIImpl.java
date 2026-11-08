package dev.project516.frcdevwasm.shim.math.autodiff;

import static dev.project516.frcdevwasm.shim.math.autodiff.Expr.constant;
import static dev.project516.frcdevwasm.shim.math.autodiff.Expr.div;
import static dev.project516.frcdevwasm.shim.math.autodiff.Expr.minus;
import static dev.project516.frcdevwasm.shim.math.autodiff.Expr.mul;
import static dev.project516.frcdevwasm.shim.math.autodiff.Expr.neg;
import static dev.project516.frcdevwasm.shim.math.autodiff.Expr.plus;

/**
 * slp::VariableMatrix exp() and solve() on row-major expression arrays. Where Sleipnir runs
 * Eigen's LU or QR on Variables, pivots and branches are chosen from current values as Eigen does.
 */
public final class VariableMatrixJNIImpl {
  private VariableMatrixJNIImpl() {}

  private static final double[] P = {
    1.0,
    0.499999999999999999999987615564159,
    0.119999999999999999999993719796468,
    0.0183333333333333333333318078905823,
    0.00199275362318840579710121408775205,
    0.00016304347826086956521736560868541,
    1.03519668737060041407846479512981e-05,
    5.17598343685300207039205144279119e-07,
    2.04315135665250081725989398668247e-08,
    6.30602270571759511499920806570486e-10,
    1.48377004840414002705850442238441e-11,
    2.52915349159796595521307855480881e-13,
    2.81017054621996217245857568431558e-15,
    1.54404975067030888596595580141692e-17
  };
  private static final double[] Q = {
    1.0,
    -0.500000000000000000000012384435841,
    0.120000000000000000000006104232309,
    -0.0183333333333333333333347707904729,
    0.00199275362318840579710166350137732,
    -0.000163043478260869565217413851346892,
    1.03519668737060041407885187519331e-05,
    -5.17598343685300207039443859549262e-07,
    2.04315135665250081726103781506842e-08,
    -6.30602270571759511500345035969254e-10,
    1.48377004840414002705969744674248e-11,
    -2.52915349159796595521550648156349e-13,
    2.81017054621996217246180850207342e-15,
    -1.54404975067030888596810607146175e-17
  };

  /** (13, 13) Padé approximant: exp(A) = (Qᵀ \ Pᵀ)ᵀ. */
  public static long[] exp(long[] mat, int rows) {
    int n = rows;
    Expr[] a = Handles.exprs(mat);
    Expr[] p = diagonal(n, P[0]);
    Expr[] q = diagonal(n, Q[0]);
    Expr[] aPow = a.clone();
    for (int k = 1; k < P.length; k++) {
      Expr pk = constant(P[k]);
      Expr qk = constant(Q[k]);
      for (int i = 0; i < n * n; i++) {
        p[i] = plus(p[i], mul(aPow[i], pk));
        q[i] = plus(q[i], mul(aPow[i], qk));
      }
      aPow = times(aPow, n, n, a, n);
    }
    Expr[] x = luSolve(transpose(q, n, n), n, transpose(p, n, n), n);
    return Handles.addAll(transpose(x, n, n));
  }

  /**
   * Solves AX = B. Like Sleipnir, a 1 x 1 A returns the single element B[0, 0] / A[0, 0], whatever
   * B's width.
   */
  public static long[] solve(long[] aHandles, int aCols, long[] bHandles, int bCols) {
    Expr[] a = Handles.exprs(aHandles);
    Expr[] b = Handles.exprs(bHandles);
    int aRows = a.length / aCols;
    int bRows = b.length / bCols;
    Expr[] x;
    if (aRows == 1 && aCols == 1) {
      x = new Expr[] {div(b[0], a[0])};
    } else if (aRows == 2 && aCols == 2) {
      x = solve2(a, b, bCols);
    } else if (aRows == 3 && aCols == 3) {
      x = solve3(a, b, bCols);
    } else if (aRows == 4 && aCols == 4) {
      x = solve4(a, b, bCols);
    } else {
      x = qrSolve(a, aRows, aCols, b, bRows, bCols);
    }
    return Handles.addAll(x);
  }

  private static Expr[] diagonal(int n, double d) {
    Expr[] out = new Expr[n * n];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        out[i * n + j] = constant(i == j ? d : 0.0);
      }
    }
    return out;
  }

  private static Expr[] transpose(Expr[] m, int rows, int cols) {
    Expr[] out = new Expr[rows * cols];
    for (int i = 0; i < rows; i++) {
      for (int j = 0; j < cols; j++) {
        out[j * rows + i] = m[i * cols + j];
      }
    }
    return out;
  }

  /** Matrix product with Sleipnir's left-to-right sums starting from a constant zero. */
  private static Expr[] times(Expr[] l, int lRows, int lCols, Expr[] r, int rCols) {
    Expr[] out = new Expr[lRows * rCols];
    for (int i = 0; i < lRows; i++) {
      for (int j = 0; j < rCols; j++) {
        Expr sum = constant(0.0);
        for (int k = 0; k < lCols; k++) {
          sum = plus(sum, mul(l[i * lCols + k], r[k * rCols + j]));
        }
        out[i * rCols + j] = sum;
      }
    }
    return out;
  }

  /** adj(A) / det(A) * B. */
  private static Expr[] adjSolve(Expr[] adj, Expr det, int n, Expr[] b, int bCols) {
    Expr[] inv = new Expr[n * n];
    for (int i = 0; i < n * n; i++) {
      inv[i] = div(adj[i], det);
    }
    return times(inv, n, n, b, bCols);
  }

  private static Expr[] solve2(Expr[] m, Expr[] b, int bCols) {
    Expr a = m[0];
    Expr bb = m[1];
    Expr c = m[2];
    Expr d = m[3];
    Expr[] adj = {d, neg(bb), neg(c), a};
    Expr det = minus(mul(a, d), mul(bb, c));
    return adjSolve(adj, det, 2, b, bCols);
  }

  private static Expr[] solve3(Expr[] m, Expr[] rhs, int bCols) {
    Expr a = m[0];
    Expr b = m[1];
    Expr c = m[2];
    Expr d = m[3];
    Expr e = m[4];
    Expr f = m[5];
    Expr g = m[6];
    Expr h = m[7];
    Expr i = m[8];
    Expr ae = mul(a, e);
    Expr af = mul(a, f);
    Expr ah = mul(a, h);
    Expr ai = mul(a, i);
    Expr bd = mul(b, d);
    Expr bf = mul(b, f);
    Expr bg = mul(b, g);
    Expr bi = mul(b, i);
    Expr cd = mul(c, d);
    Expr ce = mul(c, e);
    Expr cg = mul(c, g);
    Expr ch = mul(c, h);
    Expr dh = mul(d, h);
    Expr di = mul(d, i);
    Expr eg = mul(e, g);
    Expr ei = mul(e, i);
    Expr fg = mul(f, g);
    Expr fh = mul(f, h);
    Expr adj00 = minus(ei, fh);
    Expr adj10 = minus(fg, di);
    Expr adj20 = minus(dh, eg);
    Expr[] adj = {
      adj00, minus(ch, bi), minus(bf, ce),
      adj10, minus(ai, cg), minus(cd, af),
      adj20, minus(bg, ah), minus(ae, bd)
    };
    Expr det = plus(plus(mul(a, adj00), mul(b, adj10)), mul(c, adj20));
    return adjSolve(adj, det, 3, rhs, bCols);
  }

  private static Expr m3(Expr x, Expr y, Expr z) {
    return mul(mul(x, y), z);
  }

  private static Expr[] solve4(Expr[] mm, Expr[] rhs, int bCols) {
    Expr a = mm[0];
    Expr b = mm[1];
    Expr c = mm[2];
    Expr d = mm[3];
    Expr e = mm[4];
    Expr f = mm[5];
    Expr g = mm[6];
    Expr h = mm[7];
    Expr i = mm[8];
    Expr j = mm[9];
    Expr k = mm[10];
    Expr l = mm[11];
    Expr m = mm[12];
    Expr n = mm[13];
    Expr o = mm[14];
    Expr p = mm[15];

    Expr afk = m3(a, f, k);
    Expr afl = m3(a, f, l);
    Expr afo = m3(a, f, o);
    Expr afp = m3(a, f, p);
    Expr agj = m3(a, g, j);
    Expr agl = m3(a, g, l);
    Expr agn = m3(a, g, n);
    Expr agp = m3(a, g, p);
    Expr ahj = m3(a, h, j);
    Expr ahk = m3(a, h, k);
    Expr ahn = m3(a, h, n);
    Expr aho = m3(a, h, o);
    Expr ajo = m3(a, j, o);
    Expr ajp = m3(a, j, p);
    Expr akn = m3(a, k, n);
    Expr akp = m3(a, k, p);
    Expr aln = m3(a, l, n);
    Expr alo = m3(a, l, o);
    Expr bek = m3(b, e, k);
    Expr bel = m3(b, e, l);
    Expr beo = m3(b, e, o);
    Expr bep = m3(b, e, p);
    Expr bgi = m3(b, g, i);
    Expr bgl = m3(b, g, l);
    Expr bgm = m3(b, g, m);
    Expr bgp = m3(b, g, p);
    Expr bhi = m3(b, h, i);
    Expr bhk = m3(b, h, k);
    Expr bhm = m3(b, h, m);
    Expr bho = m3(b, h, o);
    Expr bio = m3(b, i, o);
    Expr bip = m3(b, i, p);
    Expr bjp = m3(b, j, p);
    Expr bkm = m3(b, k, m);
    Expr bkp = m3(b, k, p);
    Expr blm = m3(b, l, m);
    Expr blo = m3(b, l, o);
    Expr cej = m3(c, e, j);
    Expr cel = m3(c, e, l);
    Expr cen = m3(c, e, n);
    Expr cep = m3(c, e, p);
    Expr cfi = m3(c, f, i);
    Expr cfl = m3(c, f, l);
    Expr cfm = m3(c, f, m);
    Expr cfp = m3(c, f, p);
    Expr chi = m3(c, h, i);
    Expr chj = m3(c, h, j);
    Expr chm = m3(c, h, m);
    Expr chn = m3(c, h, n);
    Expr cin = m3(c, i, n);
    Expr cip = m3(c, i, p);
    Expr cjm = m3(c, j, m);
    Expr cjp = m3(c, j, p);
    Expr clm = m3(c, l, m);
    Expr cln = m3(c, l, n);
    Expr dej = m3(d, e, j);
    Expr dek = m3(d, e, k);
    Expr den = m3(d, e, n);
    Expr deo = m3(d, e, o);
    Expr dfi = m3(d, f, i);
    Expr dfk = m3(d, f, k);
    Expr dfm = m3(d, f, m);
    Expr dfo = m3(d, f, o);
    Expr dgi = m3(d, g, i);
    Expr dgj = m3(d, g, j);
    Expr dgm = m3(d, g, m);
    Expr dgn = m3(d, g, n);
    Expr din = m3(d, i, n);
    Expr dio = m3(d, i, o);
    Expr djm = m3(d, j, m);
    Expr djo = m3(d, j, o);
    Expr dkm = m3(d, k, m);
    Expr dkn = m3(d, k, n);
    Expr ejo = m3(e, j, o);
    Expr ejp = m3(e, j, p);
    Expr ekn = m3(e, k, n);
    Expr ekp = m3(e, k, p);
    Expr eln = m3(e, l, n);
    Expr elo = m3(e, l, o);
    Expr fio = m3(f, i, o);
    Expr fip = m3(f, i, p);
    Expr fkm = m3(f, k, m);
    Expr fkp = m3(f, k, p);
    Expr flm = m3(f, l, m);
    Expr flo = m3(f, l, o);
    Expr gin = m3(g, i, n);
    Expr gip = m3(g, i, p);
    Expr gjm = m3(g, j, m);
    Expr gjp = m3(g, j, p);
    Expr glm = m3(g, l, m);
    Expr gln = m3(g, l, n);
    Expr hin = m3(h, i, n);
    Expr hio = m3(h, i, o);
    Expr hjm = m3(h, j, m);
    Expr hjo = m3(h, j, o);
    Expr hkm = m3(h, k, m);
    Expr hkn = m3(h, k, n);

    // Every cofactor has the sign pattern +t0 - t1 - t2 + t3 + t4 - t5, or its negation.
    Expr adj00 = cof(false, fkp, flo, gjp, gln, hjo, hkn);
    Expr adj01 = cof(true, bkp, blo, cjp, cln, djo, dkn);
    Expr adj02 = cof(false, bgp, bho, cfp, chn, dfo, dgn);
    Expr adj03 = cof(true, bgl, bhk, cfl, chj, dfk, dgj);
    Expr adj10 = cof(true, ekp, elo, gip, glm, hio, hkm);
    Expr adj11 = cof(false, akp, alo, cip, clm, dio, dkm);
    Expr adj12 = cof(true, agp, aho, cep, chm, deo, dgm);
    Expr adj13 = cof(false, agl, ahk, cel, chi, dek, dgi);
    Expr adj20 = cof(false, ejp, eln, fip, flm, hin, hjm);
    Expr adj21 = cof(true, ajp, aln, bip, blm, din, djm);
    Expr adj22 = cof(false, afp, ahn, bep, bhm, den, dfm);
    Expr adj23 = cof(true, afl, ahj, bel, bhi, dej, dfi);
    Expr adj30 = cof(true, ejo, ekn, fio, fkm, gin, gjm);
    Expr adj31 = cof(false, ajo, akn, bio, bkm, cin, cjm);
    Expr adj32 = cof(true, afo, agn, beo, bgm, cen, cfm);
    Expr adj33 = cof(false, afk, agj, bek, bgi, cej, cfi);

    Expr[] adj = {
      adj00, adj01, adj02, adj03,
      adj10, adj11, adj12, adj13,
      adj20, adj21, adj22, adj23,
      adj30, adj31, adj32, adj33
    };
    Expr det =
        plus(plus(plus(mul(a, adj00), mul(b, adj10)), mul(c, adj20)), mul(d, adj30));
    return adjSolve(adj, det, 4, rhs, bCols);
  }

  /**
   * t0 - t1 - t2 + t3 + t4 - t5, or with negated=true, -t0 + t1 + t2 - t3 - t4 + t5, both
   * evaluated left to right as Sleipnir writes them.
   */
  private static Expr cof(boolean negated, Expr t0, Expr t1, Expr t2, Expr t3, Expr t4, Expr t5) {
    if (!negated) {
      return minus(plus(plus(minus(minus(t0, t1), t2), t3), t4), t5);
    }
    return plus(minus(minus(plus(plus(neg(t0), t1), t2), t3), t4), t5);
  }

  /** Eigen's unblocked partial-pivot LU and triangular solves, on square A. */
  private static Expr[] luSolve(Expr[] aIn, int n, Expr[] bIn, int bCols) {
    Expr[] lu = aIn.clone();
    int[] transpositions = new int[n];
    for (int k = 0; k < n; k++) {
      int best = k;
      double bestScore = Math.abs(VariableJNIImpl.value(lu[k * n + k]));
      for (int i = k + 1; i < n; i++) {
        double s = Math.abs(VariableJNIImpl.value(lu[i * n + k]));
        // Sleipnir's Variable > is >=, so Eigen's maxCoeff keeps the last of equal scores.
        if (s >= bestScore) {
          best = i;
          bestScore = s;
        }
      }
      transpositions[k] = best;
      if (bestScore != 0.0) {
        if (best != k) {
          for (int j = 0; j < n; j++) {
            Expr t = lu[k * n + j];
            lu[k * n + j] = lu[best * n + j];
            lu[best * n + j] = t;
          }
        }
        for (int i = k + 1; i < n; i++) {
          lu[i * n + k] = div(lu[i * n + k], lu[k * n + k]);
        }
      }
      for (int i = k + 1; i < n; i++) {
        for (int j = k + 1; j < n; j++) {
          lu[i * n + j] = minus(lu[i * n + j], mul(lu[i * n + k], lu[k * n + j]));
        }
      }
    }
    Expr[] x = bIn.clone();
    for (int k = 0; k < n; k++) {
      int t = transpositions[k];
      if (t != k) {
        for (int j = 0; j < bCols; j++) {
          Expr tmp = x[k * bCols + j];
          x[k * bCols + j] = x[t * bCols + j];
          x[t * bCols + j] = tmp;
        }
      }
    }
    for (int j = 0; j < bCols; j++) {
      for (int k = 0; k < n; k++) {
        Expr xk = x[k * bCols + j];
        for (int i = k + 1; i < n; i++) {
          x[i * bCols + j] = minus(x[i * bCols + j], mul(xk, lu[i * n + k]));
        }
      }
      for (int k = n - 1; k >= 0; k--) {
        Expr xk = div(x[k * bCols + j], lu[k * n + k]);
        x[k * bCols + j] = xk;
        for (int i = 0; i < k; i++) {
          x[i * bCols + j] = minus(x[i * bCols + j], mul(xk, lu[i * n + k]));
        }
      }
    }
    return x;
  }

  /** Eigen's unblocked Householder QR solve, least squares when A is tall. */
  private static Expr[] qrSolve(Expr[] aIn, int rows, int cols, Expr[] bIn, int bRows, int bCols) {
    Expr[] qr = aIn.clone();
    int size = Math.min(rows, cols);
    Expr[] tau = new Expr[size];
    Expr[][] essentials = new Expr[size][];
    for (int k = 0; k < size; k++) {
      int remaining = rows - k;
      Expr c0 = qr[k * cols + k];
      Expr tailSqNorm = constant(0.0);
      if (remaining > 1) {
        tailSqNorm = null;
        for (int i = k + 1; i < rows; i++) {
          Expr v = qr[i * cols + k];
          Expr sq = mul(v, v);
          tailSqNorm = tailSqNorm == null ? sq : plus(tailSqNorm, sq);
        }
      }
      Expr[] ess = new Expr[remaining - 1];
      Expr beta;
      if (VariableJNIImpl.value(tailSqNorm) <= Double.MIN_NORMAL) {
        tau[k] = constant(0.0);
        beta = c0;
        for (int i = 0; i < ess.length; i++) {
          ess[i] = constant(0.0);
        }
      } else {
        beta = Expr.sqrt(plus(mul(c0, c0), tailSqNorm));
        if (VariableJNIImpl.value(c0) >= 0.0) {
          beta = neg(beta);
        }
        Expr denom = minus(c0, beta);
        for (int i = 0; i < ess.length; i++) {
          ess[i] = div(qr[(k + 1 + i) * cols + k], denom);
        }
        tau[k] = div(minus(beta, c0), beta);
      }
      essentials[k] = ess;
      qr[k * cols + k] = beta;
      applyHouseholder(qr, cols, k, k + 1, cols - k - 1, remaining, ess, tau[k]);
    }
    Expr[] c = bIn.clone();
    for (int k = 0; k < size; k++) {
      applyHouseholder(c, bCols, k, 0, bCols, bRows - k, essentials[k], tau[k]);
    }
    Expr[] x = new Expr[cols * bCols];
    for (int j = 0; j < bCols; j++) {
      for (int k = size - 1; k >= 0; k--) {
        Expr xk = div(c[k * bCols + j], qr[k * cols + k]);
        c[k * bCols + j] = xk;
        for (int i = 0; i < k; i++) {
          c[i * bCols + j] = minus(c[i * bCols + j], mul(xk, qr[i * cols + k]));
        }
      }
      for (int i = 0; i < cols; i++) {
        x[i * bCols + j] = i < size ? c[i * bCols + j] : constant(0.0);
      }
    }
    return x;
  }

  /**
   * Applies I − τvvᵀ, v = [1; essential], to the block of m (row stride stride) at rows
   * row0..row0+blockRows and columns col0..col0+blockCols.
   */
  private static void applyHouseholder(
      Expr[] m, int stride, int row0, int col0, int blockCols, int blockRows, Expr[] ess, Expr tau) {
    if (blockCols <= 0) {
      return;
    }
    if (blockRows == 1) {
      Expr f = minus(constant(1.0), tau);
      for (int j = 0; j < blockCols; j++) {
        int at = row0 * stride + col0 + j;
        m[at] = mul(m[at], f);
      }
      return;
    }
    if (VariableJNIImpl.value(tau) == 0.0) {
      return;
    }
    for (int j = 0; j < blockCols; j++) {
      Expr tmp = constant(0.0);
      for (int i = 0; i < ess.length; i++) {
        tmp = plus(tmp, mul(ess[i], m[(row0 + 1 + i) * stride + col0 + j]));
      }
      int top = row0 * stride + col0 + j;
      tmp = plus(tmp, m[top]);
      m[top] = minus(m[top], mul(tau, tmp));
      for (int i = 0; i < ess.length; i++) {
        int at = (row0 + 1 + i) * stride + col0 + j;
        m[at] = minus(m[at], mul(mul(tau, ess[i]), tmp));
      }
    }
  }
}
