package dev.project516.frcdevwasm.shim.math.optimization;

import dev.project516.frcdevwasm.shim.math.autodiff.Sparse;

/** sleipnir/optimization/solver/util/kkt_error.hpp and problem_scaling.hpp. */
final class Kkt {
  private Kkt() {}

  /** Cost and constraint row scale factors; empty arrays mean unscaled rows. */
  static final class Scaling {
    final double f;
    final double[] ce;
    final double[] ci;

    Scaling(double f, double[] ce, double[] ci) {
      this.f = f;
      this.ce = ce;
      this.ci = ci;
    }

    static Scaling of(double[] g, Sparse ae, Sparse ai) {
      double f = Vec.min(1.0, 100.0 / Vec.normInf(g));
      return new Scaling(f, rows(ae), rows(ai));
    }

    private static double[] rows(Sparse a) {
      if (a == null) {
        return new double[0];
      }
      double[] norms = a.rowInfNorms();
      for (int i = 0; i < norms.length; i++) {
        norms[i] = Vec.min(100.0 / norms[i], 1.0);
      }
      return norms;
    }

    boolean isIdentity() {
      return f == 1.0 && ce.length == 0 && ci.length == 0;
    }
  }

  static final int INF_NORM_SCALED = 0;
  static final int ONE_NORM = 1;

  static double error(int type, double[] g) {
    return type == INF_NORM_SCALED ? Vec.normInf(g) : Vec.norm1(g);
  }

  static double error(int type, double[] g, Sparse ae, double[] ce, double[] y) {
    double[] r = Vec.minus(g, ae.transposeTimes(y));
    if (type == INF_NORM_SCALED) {
      double sD = Vec.max(100.0, Vec.norm1(y) / y.length) / 100.0;
      return Vec.max(Vec.normInf(r) / sD, Vec.normInf(ce));
    }
    return Vec.norm1(r) + Vec.norm1(ce);
  }

  static double error(
      int type,
      double[] g,
      Sparse ae,
      double[] ce,
      Sparse ai,
      double[] ci,
      double[] s,
      double[] y,
      double[] z,
      double mu) {
    double[] r = Vec.minus(Vec.minus(g, ae.transposeTimes(y)), ai.transposeTimes(z));
    double[] comp = new double[s.length];
    for (int i = 0; i < s.length; i++) {
      comp[i] = s[i] * z[i] - mu;
    }
    double[] ciMinusS = Vec.minus(ci, s);
    if (type == INF_NORM_SCALED) {
      double sD = Vec.max(100.0, (Vec.norm1(y) + Vec.norm1(z)) / (y.length + z.length)) / 100.0;
      double sC = Vec.max(100.0, Vec.norm1(z) / z.length) / 100.0;
      double e = Vec.max(Vec.normInf(r) / sD, Vec.normInf(comp) / sC);
      e = Vec.max(e, Vec.normInf(ce));
      return Vec.max(e, Vec.normInf(ciMinusS));
    }
    return Vec.norm1(r) + Vec.norm1(comp) + Vec.norm1(ce) + Vec.norm1(ciMinusS);
  }

  static double unscaled(Scaling sc, double[] g) {
    if (sc.isIdentity()) {
      return error(INF_NORM_SCALED, g);
    }
    return error(INF_NORM_SCALED, Vec.scale(1.0 / sc.f, g));
  }

  static double unscaled(Scaling sc, double[] g, Sparse ae, double[] ce, double[] y) {
    if (sc.isIdentity()) {
      return error(INF_NORM_SCALED, g, ae, ce, y);
    }
    double invF = 1.0 / sc.f;
    double[] invCe = Vec.inverse(sc.ce);
    double[] yU = Vec.scale(invF, Vec.times(sc.ce, y));
    return error(
        INF_NORM_SCALED, Vec.scale(invF, g), ae.scaleRows(invCe), Vec.times(invCe, ce), yU);
  }

  static double unscaled(
      Scaling sc,
      double[] g,
      Sparse ae,
      double[] ce,
      Sparse ai,
      double[] ci,
      double[] s,
      double[] y,
      double[] z,
      double mu) {
    if (sc.isIdentity()) {
      return error(INF_NORM_SCALED, g, ae, ce, ai, ci, s, y, z, mu);
    }
    double invF = 1.0 / sc.f;
    double[] invCe = Vec.inverse(sc.ce);
    double[] invCi = Vec.inverse(sc.ci);
    return error(
        INF_NORM_SCALED,
        Vec.scale(invF, g),
        ae.scaleRows(invCe),
        Vec.times(invCe, ce),
        ai.scaleRows(invCi),
        Vec.times(invCi, ci),
        Vec.times(invCi, s),
        timesScalar(Vec.times(sc.ce, y), invF),
        timesScalar(Vec.times(sc.ci, z), invF),
        invF * mu);
  }

  private static double[] timesScalar(double[] a, double s) {
    double[] out = new double[a.length];
    for (int i = 0; i < a.length; i++) {
      out[i] = a[i] * s;
    }
    return out;
  }
}
