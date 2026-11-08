package dev.project516.frcdevwasm.shim.math.optimization;

import dev.project516.frcdevwasm.shim.math.autodiff.Sparse;
import java.util.ArrayList;
import java.util.List;

/** sleipnir/optimization/solver/sqp.hpp: equality-constrained problems. */
final class Sqp {
  private Sqp() {}

  static int solve(Callbacks m, List<Callbacks.Iteration> callbacks, Settings o, double[] x) {
    return solve(m, callbacks, o, x, new double[m.numEq]);
  }

  static int solve(
      Callbacks m, List<Callbacks.Iteration> callbacks, Settings o, double[] x, double[] y) {
    long start = System.nanoTime();
    int n = m.numVars;
    int ne = m.numEq;
    double f = m.f(x);
    Sparse gS = m.g(x);
    double[] g = gS.toDenseVector();
    Sparse h = m.h(x, y, null);
    double[] ce = m.ce(x);
    Sparse ae = m.ae(x);
    if (ne > n) {
      if (o.diagnostics) {
        printViolations("The problem has too few degrees of freedom.", ce);
      }
      return Status.TOO_FEW_DOFS;
    }
    if (!Double.isFinite(f)
        || !gS.allFinite()
        || !h.allFinite()
        || !Vec.allFinite(ce)
        || !ae.allFinite()) {
      return Status.NONFINITE_INITIAL_GUESS;
    }
    Filter filter = new Filter(Vec.norm1(ce));
    int size = n + ne;
    Ldlt solver = new Ldlt(n, ne, 1e-10);
    final double alphaMin = 1e-7;
    int fullStepRejected = 0;
    double e0 = Kkt.unscaled(m.scaling, g, ae, ce, y);
    while (e0 > o.tolerance) {
      if (Infeasibility.equality(ae, ce)) {
        if (o.diagnostics) {
          printViolations(
              "The problem is locally infeasible due to violated equality constraints.", ce);
        }
        return Status.LOCALLY_INFEASIBLE;
      }
      if (Vec.normInf(x) > 1e10 || !Vec.allFinite(x)) {
        return Status.DIVERGING_ITERATES;
      }
      if (Callbacks.run(callbacks, new Callbacks.Info(o.iterations, x, null, gS, h, ae, null))) {
        return Status.CALLBACK_REQUESTED_STOP;
      }
      double[] lhs = Newton.dense(h, size);
      for (int c = 0; c < ae.cols; c++) {
        for (int i = ae.colStart[c]; i < ae.colStart[c + 1]; i++) {
          lhs[(n + ae.rowIndex[i]) * size + c] = ae.values[i];
        }
      }
      double[] aeTy = ae.transposeTimes(y);
      double[] rhs = new double[size];
      for (int i = 0; i < n; i++) {
        rhs[i] = -g[i] + aeTy[i];
      }
      for (int i = 0; i < ne; i++) {
        rhs[n + i] = -ce[i];
      }
      if (!solver.compute(lhs, size).success()) {
        return Status.FACTORIZATION_FAILED;
      }
      double[][] step = step(solver, rhs, n, ne);
      double alpha = 1.0;
      boolean restore = false;
      Filter.Entry current = Filter.Entry.of(f, ce);
      double dPhi = Vec.dot(g, step[0]);
      double[] trialX;
      double[] trialY;
      double trialF;
      double[] trialCe;
      while (true) {
        trialX = Vec.axpy(x, alpha, step[0]);
        trialY = Vec.axpy(y, alpha, step[1]);
        trialF = m.f(trialX);
        trialCe = m.ce(trialX);
        if (!Double.isFinite(trialF) || !Vec.allFinite(trialCe)) {
          alpha *= 0.5;
          if (alpha < alphaMin) {
            restore = true;
            break;
          }
          continue;
        }
        if (filter.tryAdd(current, Filter.Entry.of(trialF, trialCe), dPhi, alpha)) {
          break;
        }
        double prevViolation = Vec.norm1(ce);
        double nextViolation = Vec.norm1(trialCe);
        if (alpha == 1.0 && nextViolation >= prevViolation) {
          double[][] socStep = step;
          double alphaSoc = alpha;
          double[] ceSoc = ce;
          double socViolation = nextViolation;
          boolean acceptable = false;
          for (int it = 0; it < 5 && !acceptable; it++) {
            ceSoc = Vec.axpy(trialCe, alphaSoc, ceSoc);
            for (int i = 0; i < ne; i++) {
              rhs[n + i] = -ceSoc[i];
            }
            socStep = step(solver, rhs, n, ne);
            trialX = Vec.axpy(x, alphaSoc, socStep[0]);
            trialY = Vec.axpy(y, alphaSoc, socStep[1]);
            trialF = m.f(trialX);
            trialCe = m.ce(trialX);
            if (filter.tryAdd(current, Filter.Entry.of(trialF, trialCe), dPhi, alpha)) {
              step = socStep;
              alpha = alphaSoc;
              acceptable = true;
              break;
            }
            nextViolation = Vec.norm1(trialCe);
            if (nextViolation > 0.99 * socViolation) {
              break;
            }
            socViolation = nextViolation;
          }
          if (acceptable) {
            break;
          }
        }
        if (alpha == 1.0) {
          fullStepRejected++;
        }
        if (fullStepRejected >= 4
            && filter.maxConstraintViolation > current.violation / 10.0
            && filter.lastRejectionDueToFilter()) {
          filter.maxConstraintViolation *= 0.1;
          filter.reset();
          continue;
        }
        alpha *= 0.5;
        if (alpha < alphaMin) {
          double currentError = Kkt.error(Kkt.ONE_NORM, g, ae, ce, y);
          trialX = Vec.axpy(x, 1.0, step[0]);
          trialY = Vec.axpy(y, 1.0, step[1]);
          trialF = m.f(trialX);
          trialCe = m.ce(trialX);
          double nextError =
              Kkt.error(
                  Kkt.ONE_NORM, m.g(trialX).toDenseVector(), m.ae(trialX), trialCe, trialY);
          if (nextError <= 0.999 * currentError) {
            break;
          }
          restore = true;
          break;
        }
      }
      if (restore) {
        Filter.Entry initial = Filter.Entry.of(m.f(x), ce);
        double[] xR = x.clone();
        double[] gR = g;
        double alphaR = alpha;
        List<Callbacks.Iteration> frCallbacks = new ArrayList<>(callbacks);
        frCallbacks.add(
            info -> {
              double[] tx = Vec.segment(info.x, 0, n);
              double[] tce = m.ce(tx);
              Filter.Entry trial = Filter.Entry.of(m.f(tx), tce);
              double d = Vec.dot(gR, Vec.minus(tx, xR));
              return trial.violation < 0.9 * initial.violation
                  && filter.tryAdd(initial, trial, d, alphaR);
            });
        int status = FeasibilityRestoration.sqp(m, frCallbacks, o, x, y);
        if (status != Status.SUCCESS) {
          return status;
        }
        f = m.f(x);
        ce = m.ce(x);
      } else {
        if (alpha == 1.0) {
          fullStepRejected = 0;
        }
        Newton.copyInto(x, trialX);
        System.arraycopy(trialY, 0, y, 0, ne);
        f = trialF;
        ce = trialCe;
      }
      ae = m.ae(x);
      gS = m.g(x);
      g = gS.toDenseVector();
      h = m.h(x, y, null);
      e0 = Kkt.unscaled(m.scaling, g, ae, ce, y);
      o.print(restore ? 'r' : ' ', e0, f, Vec.norm1(ce), alpha);
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

  /** [pˣ; pʸ] from p = solve(rhs), whose second block is −pʸ. */
  private static double[][] step(Ldlt solver, double[] rhs, int n, int ne) {
    double[] p = solver.solve(rhs);
    double[] px = Vec.segment(p, 0, n);
    double[] py = new double[ne];
    for (int i = 0; i < ne; i++) {
      py[i] = -p[n + i];
    }
    return new double[][] {px, py};
  }

  static void printViolations(String message, double[] c) {
    System.out.println(message);
    for (int row = 0; row < c.length; row++) {
      if (c[row] < 0.0) {
        System.out.println("  " + (row + 1) + "/" + c.length + ": " + c[row]);
      }
    }
  }
}
