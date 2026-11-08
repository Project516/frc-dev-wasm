package dev.project516.frcdevwasm.shim.math.optimization;

import dev.project516.frcdevwasm.shim.math.autodiff.Sparse;
import java.util.List;

/**
 * sleipnir/optimization/solver/util/feasibility_restoration.hpp: minimizes constraint violation
 * with elastic variables p, n ≥ 0 and the interior-point method.
 */
final class FeasibilityRestoration {
  private FeasibilityRestoration() {}

  private static final double RHO = 1e3;

  /** {p, n} for c(x) − p + n = 0, from equation (33) of the IPOPT paper. */
  private static double[][] pn(double[] c, double mu) {
    double[] p = new double[c.length];
    double[] n = new double[c.length];
    for (int row = 0; row < c.length; row++) {
      double a = RHO;
      double b = RHO * c[row] - mu;
      double cc = -mu * c[row] / 2.0;
      n[row] = (-b + Math.sqrt(b * b - 4.0 * a * cc)) / (2.0 * a);
      p[row] = c[row] + n[row];
    }
    return new double[][] {p, n};
  }

  private static double[] scaleDiag(double[] x) {
    double[] d = new double[x.length];
    for (int i = 0; i < x.length; i++) {
      d[i] = Vec.min(1.0 / (x[i] * x[i]), 1.0);
    }
    return d;
  }

  /** ρΣ(elastics) + ζ/2 (x − xᵣ)ᵀDᵣ(x − xᵣ); its gradient and Hessian. */
  private abstract static class Restoration extends Callbacks {
    final Callbacks m;
    final double[] xR;
    final double[] dR;
    final double zeta;

    Restoration(Callbacks m, int vars, int eq, int ineq, Kkt.Scaling sc, double[] xR, double zeta) {
      super(vars, eq, ineq, sc);
      this.m = m;
      this.xR = xR;
      this.dR = scaleDiag(xR);
      this.zeta = zeta;
    }

    double[] x(double[] xp) {
      return Vec.segment(xp, 0, m.numVars);
    }

    @Override
    double f(double[] xp) {
      int n = m.numVars;
      double sum = 0.0;
      for (int i = n; i < xp.length; i++) {
        sum += xp[i];
      }
      double quad = 0.0;
      for (int i = 0; i < n; i++) {
        double d = xp[i] - xR[i];
        quad += zeta / 2.0 * d * dR[i] * d;
      }
      return RHO * sum + quad;
    }

    @Override
    Sparse g(double[] xp) {
      int n = m.numVars;
      Sparse.Triplets t = new Sparse.Triplets(xp.length);
      for (int i = 0; i < xp.length; i++) {
        double v = i < n ? zeta * dR[i] * (xp[i] - xR[i]) : RHO;
        if (v != 0.0) {
          t.add(i, 0, v);
        }
      }
      return Sparse.fromTriplets(t, xp.length, 1);
    }

    /**
     * Only the cost Hessian ζDᵣ: Sleipnir resizes the constraint Hessian before adding it, and
     * Eigen's SparseMatrix::resize() clears it.
     */
    @Override
    Sparse h(double[] xp, double[] y, double[] z) {
      Sparse.Triplets t = new Sparse.Triplets(m.numVars);
      for (int i = 0; i < m.numVars; i++) {
        t.add(i, i, zeta * dR[i]);
      }
      return Sparse.fromTriplets(t, xp.length, xp.length);
    }

    /** [Aₑ  −I  I  0...]. */
    @Override
    Sparse ae(double[] xp) {
      Sparse a = m.ae(x(xp));
      int n = m.numVars;
      int ne = m.numEq;
      Sparse.Triplets t = new Sparse.Triplets(a.nonZeros() + 2 * ne);
      for (int c = 0; c < a.cols; c++) {
        for (int i = a.colStart[c]; i < a.colStart[c + 1]; i++) {
          t.add(a.rowIndex[i], c, a.values[i]);
        }
      }
      for (int i = 0; i < ne; i++) {
        t.add(i, n + i, -1.0);
      }
      for (int i = 0; i < ne; i++) {
        t.add(i, n + ne + i, 1.0);
      }
      return Sparse.fromTriplets(t, ne, xp.length);
    }

    /** cₑ(x) − pₑ + nₑ. */
    @Override
    double[] ce(double[] xp) {
      double[] c = m.ce(x(xp));
      int n = m.numVars;
      int ne = m.numEq;
      double[] out = new double[ne];
      for (int i = 0; i < ne; i++) {
        out[i] = c[i] - xp[n + i] + xp[n + ne + i];
      }
      return out;
    }
  }

  static int sqp(Callbacks m, List<Callbacks.Iteration> callbacks, Settings o, double[] x, double[] y) {
    int n = m.numVars;
    int ne = m.numEq;
    double mu = o.tolerance / 10.0;
    double[] ce = m.ce(x);
    double frMu = Vec.max(mu, Vec.normInf(ce));
    double zeta = Math.sqrt(frMu);
    double[][] pnE = pn(ce, frMu);
    double[] frX = Vec.concat(x, pnE[0], pnE[1]);
    double[] frZ = Vec.concat(Vec.scale(frMu, Vec.inverse(pnE[0])), Vec.scale(frMu, Vec.inverse(pnE[1])));
    Kkt.Scaling sc = new Kkt.Scaling(1.0, m.scaling.ce, Vec.filled(2 * ne, 1.0));
    Restoration fr =
        new Restoration(m, frX.length, ne, 2 * ne, sc, x.clone(), zeta) {
          @Override
          double[] ci(double[] xp) {
            return Vec.segment(xp, n, 2 * ne);
          }

          @Override
          Sparse ai(double[] xp) {
            Sparse.Triplets t = new Sparse.Triplets(2 * ne);
            for (int i = 0; i < 2 * ne; i++) {
              t.add(i, n + i, 1.0);
            }
            return Sparse.fromTriplets(t, 2 * ne, xp.length);
          }
        };
    InteriorPoint.State st =
        new InteriorPoint.State(frX, Vec.filled(2 * ne, 1.0), new double[ne], frZ, frMu);
    int status = InteriorPoint.solve(fr, callbacks, o, true, st);
    System.arraycopy(frX, 0, x, 0, n);
    if (status == Status.CALLBACK_REQUESTED_STOP) {
      double[] estimate = multipliers(m.g(x).toDenseVector(), m.ae(x));
      System.arraycopy(estimate, 0, y, 0, ne);
      return Status.SUCCESS;
    } else if (status == Status.SUCCESS) {
      return Status.LOCALLY_INFEASIBLE;
    }
    return Status.FEASIBILITY_RESTORATION_FAILED;
  }

  static int interiorPoint(
      Callbacks m, List<Callbacks.Iteration> callbacks, Settings o, InteriorPoint.State outer, double mu) {
    int n = m.numVars;
    int ne = m.numEq;
    int ni = m.numIneq;
    double[] x = outer.x;
    double[] s = outer.s;
    double[] ce = m.ce(x);
    double[] ci = m.ci(x);
    double[] ciMinusS = Vec.minus(ci, s);
    double frMu = Vec.max(Vec.max(mu, Vec.normInf(ce)), Vec.normInf(ciMinusS));
    double zeta = Math.sqrt(frMu);
    double[][] pnE = pn(ce, frMu);
    double[][] pnI = pn(ciMinusS, frMu);
    double[] frX = Vec.concat(x, pnE[0], pnE[1], pnI[0], pnI[1]);
    int elastic = 2 * ne + 2 * ni;
    double[] frS = Vec.concat(s, Vec.filled(elastic, 1.0));
    double[] frZ =
        Vec.concat(
            Vec.scale(frMu, Vec.inverse(s)),
            Vec.scale(frMu, Vec.inverse(pnE[0])),
            Vec.scale(frMu, Vec.inverse(pnE[1])),
            Vec.scale(frMu, Vec.inverse(pnI[0])),
            Vec.scale(frMu, Vec.inverse(pnI[1])));
    Kkt.Scaling sc =
        new Kkt.Scaling(1.0, m.scaling.ce, Vec.concat(m.scaling.ci, Vec.filled(elastic, 1.0)));
    Restoration fr =
        new Restoration(m, frX.length, ne, ni + elastic, sc, x.clone(), zeta) {
          /** [cᵢ(x) − pᵢ + nᵢ; pₑ; nₑ; pᵢ; nᵢ]. */
          @Override
          double[] ci(double[] xp) {
            double[] c = m.ci(x(xp));
            double[] out = new double[ni + elastic];
            for (int i = 0; i < ni; i++) {
              out[i] = c[i] - xp[n + 2 * ne + i] + xp[n + 2 * ne + ni + i];
            }
            System.arraycopy(xp, n, out, ni, elastic);
            return out;
          }

          /** [Aᵢ 0 0 −I I; 0 I 0 0 0; 0 0 I 0 0; 0 0 0 I 0; 0 0 0 0 I]. */
          @Override
          Sparse ai(double[] xp) {
            Sparse a = m.ai(x(xp));
            Sparse.Triplets t = new Sparse.Triplets(a.nonZeros() + 2 * ne + 4 * ni);
            for (int c = 0; c < a.cols; c++) {
              for (int i = a.colStart[c]; i < a.colStart[c + 1]; i++) {
                t.add(a.rowIndex[i], c, a.values[i]);
              }
            }
            for (int i = 0; i < 2 * ne; i++) {
              t.add(ni + i, n + i, 1.0);
            }
            for (int i = 0; i < ni; i++) {
              t.add(i, n + 2 * ne + i, -1.0);
              t.add(ni + 2 * ne + i, n + 2 * ne + i, 1.0);
            }
            for (int i = 0; i < ni; i++) {
              t.add(i, n + 2 * ne + ni + i, 1.0);
              t.add(2 * ni + 2 * ne + i, n + 2 * ne + ni + i, 1.0);
            }
            return Sparse.fromTriplets(t, ni + elastic, xp.length);
          }
        };
    InteriorPoint.State st = new InteriorPoint.State(frX, frS, new double[ne], frZ, frMu);
    int status = InteriorPoint.solve(fr, callbacks, o, true, st);
    System.arraycopy(frX, 0, x, 0, n);
    System.arraycopy(frS, 0, s, 0, ni);
    if (status == Status.CALLBACK_REQUESTED_STOP) {
      double[][] yz = multipliers(m.g(x).toDenseVector(), m.ae(x), m.ai(x), s, mu);
      System.arraycopy(yz[0], 0, outer.y, 0, ne);
      System.arraycopy(yz[1], 0, outer.z, 0, ni);
      return Status.SUCCESS;
    } else if (status == Status.SUCCESS) {
      return Status.LOCALLY_INFEASIBLE;
    }
    return Status.FEASIBILITY_RESTORATION_FAILED;
  }

  /** y = (AₑAₑᵀ)⁻¹Aₑ∇f. */
  static double[] multipliers(double[] g, Sparse ae) {
    int m = ae.rows;
    return Ldlt.of(lowerOfProduct(ae.transpose(), m), m).solve(ae.times(g));
  }

  /** [y; z] = (ÂÂᵀ)⁻¹Â[∇f; −μe] with Â = [Aₑ 0; Aᵢ −S], then z clamped. */
  static double[][] multipliers(double[] g, Sparse ae, Sparse ai, double[] s, double mu) {
    int ne = ae.rows;
    int ni = ai.rows;
    int n = ae.cols;
    Sparse.Triplets t = new Sparse.Triplets(ae.nonZeros() + ai.nonZeros() + ni);
    for (int c = 0; c < n; c++) {
      for (int i = ae.colStart[c]; i < ae.colStart[c + 1]; i++) {
        t.add(ae.rowIndex[i], c, ae.values[i]);
      }
      for (int i = ai.colStart[c]; i < ai.colStart[c + 1]; i++) {
        t.add(ne + ai.rowIndex[i], c, ai.values[i]);
      }
    }
    for (int i = 0; i < ni; i++) {
      t.add(ne + i, n + i, -s[i]);
    }
    Sparse aHat = Sparse.fromTriplets(t, ne + ni, n + ni);
    double[] rhs = aHat.times(Vec.concat(g, Vec.filled(ni, -mu)));
    double[] sol = Ldlt.of(lowerOfProduct(aHat.transpose(), ne + ni), ne + ni).solve(rhs);
    double[] y = Vec.segment(sol, 0, ne);
    double[] z = Vec.segment(sol, ne, ni);
    for (int row = 0; row < ni; row++) {
      z[row] = InteriorPoint.clamp(z[row], 1.0 / 1e10 * mu / s[row], 1e10 * mu / s[row]);
    }
    return new double[][] {y, z};
  }

  /** Dense lower triangle of AAᵀ, given Aᵀ (columns are the rows of A). */
  private static double[] lowerOfProduct(Sparse aT, int size) {
    double[] out = new double[size * size];
    for (int i = 0; i < size; i++) {
      for (int j = 0; j <= i; j++) {
        double sum = 0.0;
        int a = aT.colStart[i];
        int b = aT.colStart[j];
        while (a < aT.colStart[i + 1] && b < aT.colStart[j + 1]) {
          int ra = aT.rowIndex[a];
          int rb = aT.rowIndex[b];
          if (ra == rb) {
            sum += aT.values[a++] * aT.values[b++];
          } else if (ra < rb) {
            a++;
          } else {
            b++;
          }
        }
        out[i * size + j] = sum;
      }
    }
    return out;
  }
}
