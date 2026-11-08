package dev.project516.frcdevwasm.shim.math.optimization;

import dev.project516.frcdevwasm.shim.math.autodiff.Sparse;
import java.util.ArrayList;
import java.util.List;

/** sleipnir/optimization/solver/interior_point.hpp: problems with inequality constraints. */
final class InteriorPoint {
  private InteriorPoint() {}

  /** Iterates; the arrays are updated in place. mu[0] is the barrier parameter. */
  static final class State {
    final double[] x;
    final double[] s;
    final double[] y;
    final double[] z;
    final double[] mu;

    State(double[] x, double[] s, double[] y, double[] z, double mu) {
      this.x = x;
      this.s = s;
      this.y = y;
      this.z = z;
      this.mu = new double[] {mu};
    }
  }

  static int solve(Callbacks m, List<Callbacks.Iteration> callbacks, Settings o, double[] x) {
    State st =
        new State(
            x,
            Vec.filled(m.numIneq, 1.0),
            new double[m.numEq],
            Vec.filled(m.numIneq, 1.0),
            0.1 * m.scaling.f);
    return solve(m, callbacks, o, false, st);
  }

  static int solve(
      Callbacks m, List<Callbacks.Iteration> callbacks, Settings o, boolean inRestoration, State st) {
    long start = System.nanoTime();
    int n = m.numVars;
    int ne = m.numEq;
    int ni = m.numIneq;
    double[] x = st.x;
    double[] s = st.s;
    double[] y = st.y;
    double[] z = st.z;
    double f = m.f(x);
    Sparse gS = m.g(x);
    double[] g = gS.toDenseVector();
    Sparse h = m.h(x, y, z);
    double[] ce = m.ce(x);
    Sparse ae = m.ae(x);
    double[] ci = m.ci(x);
    Sparse ai = m.ai(x);
    if (ne > n) {
      if (o.diagnostics) {
        Sqp.printViolations("The problem has too few degrees of freedom.", ce);
      }
      return Status.TOO_FEW_DOFS;
    }
    if (!Double.isFinite(f)
        || !gS.allFinite()
        || !h.allFinite()
        || !Vec.allFinite(ce)
        || !ae.allFinite()
        || !Vec.allFinite(ci)
        || !ai.allFinite()) {
      return Status.NONFINITE_INITIAL_GUESS;
    }
    final double muMin = m.scaling.f * o.tolerance / 10.0;
    final double tauMin = 0.99;
    double tau = tauMin;
    Filter filter = new Filter(Vec.norm1(ce) + Vec.norm1(Vec.minus(ci, s)));
    int size = n + ne;
    Ldlt solver = new Ldlt(n, ne, inRestoration ? 0.0 : 1e-10);
    final double alphaMin = 1e-7;
    int fullStepRejected = 0;
    double e0 = Kkt.unscaled(m.scaling, g, ae, ce, ai, ci, s, y, z, 0.0);
    Sparse aiT = ai.transpose();
    while (e0 > o.tolerance) {
      double mu = st.mu[0];
      if (Infeasibility.equality(ae, ce)) {
        if (o.diagnostics) {
          Sqp.printViolations(
              "The problem is locally infeasible due to violated equality constraints.", ce);
        }
        return Status.LOCALLY_INFEASIBLE;
      }
      if (Infeasibility.inequality(ai, ci)) {
        if (o.diagnostics) {
          Sqp.printViolations(
              "The problem is locally infeasible due to violated inequality constraints.", ci);
        }
        return Status.LOCALLY_INFEASIBLE;
      }
      if (Vec.normInf(x) > 1e10
          || !Vec.allFinite(x)
          || Vec.normInf(s) > 1e10
          || !Vec.allFinite(s)) {
        return Status.DIVERGING_ITERATES;
      }
      if (Callbacks.run(callbacks, new Callbacks.Info(o.iterations, x, s, gS, h, ae, ai))) {
        return Status.CALLBACK_REQUESTED_STOP;
      }
      double[] sigma = new double[ni];
      double[] sInv = Vec.inverse(s);
      for (int i = 0; i < ni; i++) {
        sigma[i] = sInv[i] * z[i];
      }
      double[] lhs = new double[size * size];
      addAtSigmaA(lhs, size, aiT, sigma);
      double[] hDense = Newton.dense(h, size);
      for (int i = 0; i < n * size; i++) {
        lhs[i] += hDense[i];
      }
      for (int c = 0; c < ae.cols; c++) {
        for (int i = ae.colStart[c]; i < ae.colStart[c + 1]; i++) {
          lhs[(n + ae.rowIndex[i]) * size + c] = ae.values[i];
        }
      }
      double[] inner = new double[ni];
      for (int i = 0; i < ni; i++) {
        inner[i] = -sigma[i] * ci[i] + mu * sInv[i] + z[i];
      }
      double[] rhs = rhs(g, ae, y, ai, inner, ce, n, ne);
      if (!solver.compute(lhs, size).success()) {
        return Status.FACTORIZATION_FAILED;
      }
      double[][] step = step(solver, rhs, Vec.minus(ci, s), ai, sigma, sInv, z, mu, n, ne);
      double alphaMax = fractionToBoundary(s, step[1], tau);
      double alpha = alphaMax;
      boolean restore = alpha < alphaMin;
      double alphaZ = fractionToBoundary(z, step[3], tau);
      Filter.Entry current = Filter.Entry.of(f, s, ce, ci, mu);
      double dPhi = Vec.dot(g, step[0]) - mu * Vec.dot(sInv, step[1]);
      double[] trialX;
      double[] trialS;
      double[] trialY;
      double[] trialZ;
      double trialF;
      double[] trialCe;
      double[] trialCi;
      while (true) {
        trialX = Vec.axpy(x, alpha, step[0]);
        trialCi = m.ci(trialX);
        if (o.feasibleIpm && allPositive(ci)) {
          trialS = trialCi;
        } else {
          trialS = Vec.axpy(s, alpha, step[1]);
        }
        trialY = Vec.axpy(y, alphaZ, step[2]);
        trialZ = Vec.axpy(z, alphaZ, step[3]);
        trialF = m.f(trialX);
        trialCe = m.ce(trialX);
        if (!Double.isFinite(trialF) || !Vec.allFinite(trialCe) || !Vec.allFinite(trialCi)) {
          alpha *= 0.5;
          if (alpha < alphaMin) {
            restore = true;
            break;
          }
          continue;
        }
        if (filter.tryAdd(current, Filter.Entry.of(trialF, trialS, trialCe, trialCi, mu), dPhi, alpha)) {
          break;
        }
        double prevViolation = Vec.norm1(ce) + Vec.norm1(Vec.minus(ci, s));
        double nextViolation = Vec.norm1(trialCe) + Vec.norm1(Vec.minus(trialCi, trialS));
        if (alpha == alphaMax && nextViolation >= prevViolation) {
          double[][] socStep = step;
          double alphaSoc = alpha;
          double alphaZSoc = alphaZ;
          double[] ceSoc = ce;
          double[] ciMinusSSoc = Vec.minus(ci, s);
          double socViolation = nextViolation;
          boolean acceptable = false;
          for (int it = 0; it < 5 && !acceptable; it++) {
            ceSoc = Vec.axpy(trialCe, alphaSoc, ceSoc);
            double[] next = new double[ni];
            for (int i = 0; i < ni; i++) {
              next[i] = alphaSoc * ciMinusSSoc[i] + trialCi[i] - trialS[i];
            }
            ciMinusSSoc = next;
            double[] socInner = new double[ni];
            for (int i = 0; i < ni; i++) {
              socInner[i] = mu * sInv[i] - sigma[i] * ciMinusSSoc[i];
            }
            rhs = rhs(g, ae, y, ai, socInner, ceSoc, n, ne);
            socStep = step(solver, rhs, ciMinusSSoc, ai, sigma, sInv, z, mu, n, ne);
            alphaSoc = fractionToBoundary(s, socStep[1], tau);
            alphaZSoc = fractionToBoundary(z, socStep[3], tau);
            trialX = Vec.axpy(x, alphaSoc, socStep[0]);
            trialS = Vec.axpy(s, alphaSoc, socStep[1]);
            trialY = Vec.axpy(y, alphaZSoc, socStep[2]);
            trialZ = Vec.axpy(z, alphaZSoc, socStep[3]);
            trialF = m.f(trialX);
            trialCe = m.ce(trialX);
            trialCi = m.ci(trialX);
            if (filter.tryAdd(
                current, Filter.Entry.of(trialF, trialS, trialCe, trialCi, mu), dPhi, alpha)) {
              step = socStep;
              alpha = alphaSoc;
              alphaZ = alphaZSoc;
              acceptable = true;
              break;
            }
            nextViolation = Vec.norm1(trialCe) + Vec.norm1(Vec.minus(trialCi, trialS));
            if (nextViolation > 0.99 * socViolation) {
              break;
            }
            socViolation = nextViolation;
          }
          if (acceptable) {
            break;
          }
        }
        if (alpha == alphaMax) {
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
          double currentError = Kkt.error(Kkt.ONE_NORM, g, ae, ce, ai, ci, s, y, z, mu);
          trialX = Vec.axpy(x, alphaMax, step[0]);
          trialS = Vec.axpy(s, alphaMax, step[1]);
          trialY = Vec.axpy(y, alphaZ, step[2]);
          trialZ = Vec.axpy(z, alphaZ, step[3]);
          trialF = m.f(trialX);
          trialCe = m.ce(trialX);
          trialCi = m.ci(trialX);
          double nextError =
              Kkt.error(
                  Kkt.ONE_NORM,
                  m.g(trialX).toDenseVector(),
                  m.ae(trialX),
                  trialCe,
                  m.ai(trialX),
                  trialCi,
                  trialS,
                  trialY,
                  trialZ,
                  mu);
          if (nextError <= 0.999 * currentError) {
            break;
          }
          restore = true;
          break;
        }
      }
      if (restore) {
        if (inRestoration) {
          return Status.FEASIBILITY_RESTORATION_FAILED;
        }
        Filter.Entry initial = Filter.Entry.of(m.f(x), s, ce, ci, mu);
        double[] xR = x.clone();
        double[] sR = s.clone();
        double[] gR = g;
        double alphaR = alpha;
        double muR = mu;
        List<Callbacks.Iteration> frCallbacks = new ArrayList<>(callbacks);
        frCallbacks.add(
            info -> {
              double[] tx = Vec.segment(info.x, 0, n);
              double[] ts = Vec.segment(info.s, 0, ni);
              Filter.Entry trial = Filter.Entry.of(m.f(tx), ts, m.ce(tx), m.ci(tx), muR);
              double d =
                  Vec.dot(gR, Vec.minus(tx, xR))
                      - muR * Vec.dot(Vec.inverse(sR), Vec.minus(ts, sR));
              return trial.violation < 0.9 * initial.violation
                  && filter.tryAdd(initial, trial, d, alphaR);
            });
        int status = FeasibilityRestoration.interiorPoint(m, frCallbacks, o, st, mu);
        if (status != Status.SUCCESS) {
          return status;
        }
        f = m.f(x);
        ce = m.ce(x);
        ci = m.ci(x);
      } else {
        if (alpha == alphaMax) {
          fullStepRejected = 0;
        }
        System.arraycopy(trialX, 0, x, 0, n);
        System.arraycopy(trialS, 0, s, 0, ni);
        System.arraycopy(trialY, 0, y, 0, ne);
        System.arraycopy(trialZ, 0, z, 0, ni);
        for (int row = 0; row < ni; row++) {
          z[row] = clamp(z[row], 1.0 / 1e10 * mu / s[row], 1e10 * mu / s[row]);
        }
        f = trialF;
        ce = trialCe;
        ci = trialCi;
      }
      ae = m.ae(x);
      ai = m.ai(x);
      aiT = ai.transpose();
      gS = m.g(x);
      g = gS.toDenseVector();
      h = m.h(x, y, z);
      e0 = Kkt.unscaled(m.scaling, g, ae, ce, ai, ci, s, y, z, 0.0);
      if (e0 > o.tolerance) {
        double eMu = Kkt.error(Kkt.INF_NORM_SCALED, g, ae, ce, ai, ci, s, y, z, st.mu[0]);
        while (st.mu[0] > muMin && eMu <= 10.0 * st.mu[0]) {
          // μⱼ₊₁ = max(εₜₒₗ/10, min(κ_μ μⱼ, μⱼ^θ_μ)), then τ and the filter follow
          st.mu[0] = Vec.max(muMin, Vec.min(0.2 * st.mu[0], Math.pow(st.mu[0], 1.5)));
          tau = Vec.max(tauMin, 1.0 - st.mu[0]);
          filter.reset();
          eMu = Kkt.error(Kkt.INF_NORM_SCALED, g, ae, ce, ai, ci, s, y, z, st.mu[0]);
        }
      }
      o.print(
          inRestoration ? 'r' : ' ',
          e0,
          f,
          Vec.norm1(ce) + Vec.norm1(Vec.minus(ci, s)),
          alpha);
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

  /** std::clamp. */
  static double clamp(double v, double lo, double hi) {
    return v < lo ? lo : hi < v ? hi : v;
  }

  private static boolean allPositive(double[] v) {
    for (double x : v) {
      if (!(x > 0.0)) {
        return false;
      }
    }
    return true;
  }

  /** −[∇f − Aₑᵀy − Aᵢᵀ inner; cₑ]. */
  private static double[] rhs(
      double[] g, Sparse ae, double[] y, Sparse ai, double[] inner, double[] ce, int n, int ne) {
    double[] aeTy = ae.transposeTimes(y);
    double[] aiT = ai.transposeTimes(inner);
    double[] rhs = new double[n + ne];
    for (int i = 0; i < n; i++) {
      rhs[i] = -g[i] + aeTy[i] + aiT[i];
    }
    for (int i = 0; i < ne; i++) {
      rhs[n + i] = -ce[i];
    }
    return rhs;
  }

  /** {pˣ, pˢ, pʸ, pᶻ}. */
  private static double[][] step(
      Ldlt solver,
      double[] rhs,
      double[] ciMinusS,
      Sparse ai,
      double[] sigma,
      double[] sInv,
      double[] z,
      double mu,
      int n,
      int ne) {
    double[] p = solver.solve(rhs);
    double[] px = Vec.segment(p, 0, n);
    double[] py = new double[ne];
    for (int i = 0; i < ne; i++) {
      py[i] = -p[n + i];
    }
    double[] aipx = ai.times(px);
    double[] ps = new double[ciMinusS.length];
    double[] pz = new double[ciMinusS.length];
    for (int i = 0; i < ps.length; i++) {
      ps[i] = ciMinusS[i] + aipx[i];
      pz[i] = mu * sInv[i] - z[i] - sigma[i] * ps[i];
    }
    return new double[][] {px, ps, py, pz};
  }

  /** max α ∈ (0, 1] with x + αp ≥ (1 − τ)x. */
  static double fractionToBoundary(double[] x, double[] p, double tau) {
    double alpha = 1.0;
    for (int i = 0; i < x.length; i++) {
      if (alpha * p[i] < -tau * x[i]) {
        alpha = -tau / p[i] * x[i];
      }
    }
    return alpha;
  }

  /** Adds the lower triangle of Aᵢᵀ Σ Aᵢ, accumulated by constraint as Eigen's product does. */
  private static void addAtSigmaA(double[] lhs, int size, Sparse aiT, double[] sigma) {
    for (int i = 0; i < aiT.cols; i++) {
      int from = aiT.colStart[i];
      int to = aiT.colStart[i + 1];
      for (int a = from; a < to; a++) {
        double va = aiT.values[a] * sigma[i];
        int ra = aiT.rowIndex[a];
        for (int b = from; b < to; b++) {
          int rb = aiT.rowIndex[b];
          if (ra >= rb) {
            lhs[ra * size + rb] += va * aiT.values[b];
          }
        }
      }
    }
  }
}
