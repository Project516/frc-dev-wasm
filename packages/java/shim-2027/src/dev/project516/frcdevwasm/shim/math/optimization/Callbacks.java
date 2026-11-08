package dev.project516.frcdevwasm.shim.math.optimization;

import dev.project516.frcdevwasm.shim.math.autodiff.Sparse;

/**
 * The matrix callbacks a solver evaluates (Sleipnir's *MatrixCallbacks): the cost, its gradient,
 * the Lagrangian Hessian (lower triangle), and the constraints with their Jacobians.
 */
abstract class Callbacks {
  final int numVars;
  final int numEq;
  final int numIneq;
  final Kkt.Scaling scaling;

  Callbacks(int numVars, int numEq, int numIneq, Kkt.Scaling scaling) {
    this.numVars = numVars;
    this.numEq = numEq;
    this.numIneq = numIneq;
    this.scaling = scaling;
  }

  abstract double f(double[] x);

  abstract Sparse g(double[] x);

  abstract Sparse h(double[] x, double[] y, double[] z);

  abstract double[] ce(double[] x);

  abstract Sparse ae(double[] x);

  abstract double[] ci(double[] x);

  abstract Sparse ai(double[] x);

  /** Solver state passed to iteration callbacks; missing parts are empty. */
  static final class Info {
    final int iteration;
    final double[] x;
    final double[] s;
    final Sparse g;
    final Sparse h;
    final Sparse ae;
    final Sparse ai;

    Info(int iteration, double[] x, double[] s, Sparse g, Sparse h, Sparse ae, Sparse ai) {
      this.iteration = iteration;
      this.x = x;
      this.s = s;
      this.g = g;
      this.h = h;
      this.ae = ae != null ? ae : Sparse.empty(0, 0);
      this.ai = ai != null ? ai : Sparse.empty(0, 0);
    }
  }

  interface Iteration {
    /** Returns true to stop the solver. */
    boolean call(Info info);
  }

  static boolean run(java.util.List<Iteration> callbacks, Info info) {
    for (Iteration c : callbacks) {
      if (c.call(info)) {
        return true;
      }
    }
    return false;
  }
}
