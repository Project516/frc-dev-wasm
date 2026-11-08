package dev.project516.frcdevwasm.shim.math.optimization;

import dev.project516.frcdevwasm.shim.math.autodiff.Sparse;
import java.util.List;

/** sleipnir/optimization/solver/newton.hpp: unconstrained problems. */
final class Newton {
  private Newton() {}

  static int solve(Callbacks m, List<Callbacks.Iteration> callbacks, Settings o, double[] x) {
    long start = System.nanoTime();
    double f = m.f(x);
    Sparse gS = m.g(x);
    Sparse h = m.h(x, null, null);
    double[] g = gS.toDenseVector();
    if (!Double.isFinite(f) || !gS.allFinite() || !h.allFinite()) {
      return Status.NONFINITE_INITIAL_GUESS;
    }
    int n = m.numVars;
    Filter filter = new Filter(0.0);
    Ldlt solver = new Ldlt(n, 0, 0.0);
    final double alphaMin = 1e-20;
    double e0 = Kkt.unscaled(m.scaling, g);
    while (e0 > o.tolerance) {
      if (Vec.normInf(x) > 1e10 || !Vec.allFinite(x)) {
        return Status.DIVERGING_ITERATES;
      }
      if (Callbacks.run(callbacks, new Callbacks.Info(o.iterations, x, null, gS, h, null, null))) {
        return Status.CALLBACK_REQUESTED_STOP;
      }
      solver.compute(dense(h, n), n);
      double[] px = solver.solve(Vec.scale(-1.0, g));
      double alpha = 1.0;
      double dPhi = Vec.dot(g, px);
      double[] trialX;
      double trialF;
      while (true) {
        trialX = Vec.axpy(x, alpha, px);
        trialF = m.f(trialX);
        if (!Double.isFinite(trialF)) {
          alpha *= 0.5;
          if (alpha < alphaMin) {
            return Status.LINE_SEARCH_FAILED;
          }
          continue;
        }
        if (filter.tryAdd(new Filter.Entry(f, 0.0), new Filter.Entry(trialF, 0.0), dPhi, alpha)) {
          break;
        }
        alpha *= 0.5;
        if (alpha < alphaMin) {
          double current = Kkt.error(Kkt.ONE_NORM, g);
          trialX = Vec.axpy(x, 1.0, px);
          double next = Kkt.error(Kkt.ONE_NORM, m.g(trialX).toDenseVector());
          if (next <= 0.999 * current) {
            trialF = m.f(trialX);
            break;
          }
          return Status.LINE_SEARCH_FAILED;
        }
      }
      x = copyInto(x, trialX);
      f = trialF;
      gS = m.g(x);
      g = gS.toDenseVector();
      h = m.h(x, null, null);
      e0 = Kkt.unscaled(m.scaling, g);
      o.print(' ', e0, f, 0.0, alpha);
      o.iterations++;
      if (o.iterations >= o.maxIterations) {
        return Status.MAX_ITERATIONS_EXCEEDED;
      }
      if (o.timedOut(start)) {
        return Status.TIMEOUT;
      }
    }
    return Status.SUCCESS;
  }

  /** Copies src into the caller's x so the solution reaches it. */
  static double[] copyInto(double[] x, double[] src) {
    System.arraycopy(src, 0, x, 0, x.length);
    return x;
  }

  /** The lower triangle of a square sparse matrix as dense row-major. */
  static double[] dense(Sparse a, int size) {
    double[] out = new double[size * size];
    for (int c = 0; c < a.cols; c++) {
      for (int i = a.colStart[c]; i < a.colStart[c + 1]; i++) {
        int r = a.rowIndex[i];
        if (r >= c) {
          out[r * size + c] += a.values[i];
        }
      }
    }
    return out;
  }
}
