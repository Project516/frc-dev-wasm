package dev.project516.frcdevwasm.shim.math.optimization;

import dev.project516.frcdevwasm.shim.math.autodiff.Sparse;

/** sleipnir/optimization/solver/util/is_locally_infeasible.hpp. */
final class Infeasibility {
  private Infeasibility() {}

  static boolean equality(Sparse ae, double[] ce) {
    return ae.rows > 0 && Vec.norm2(ae.transposeTimes(ce)) < 1e-6 && Vec.norm2(ce) > 1e-2;
  }

  static boolean inequality(Sparse ai, double[] ci) {
    if (ai.rows > 0) {
      double[] plus = new double[ci.length];
      for (int i = 0; i < ci.length; i++) {
        plus[i] = Vec.min(ci[i], 0.0);
      }
      return Vec.norm2(ai.transposeTimes(plus)) < 1e-6 && Vec.norm2(plus) > 1e-6;
    }
    return false;
  }
}
