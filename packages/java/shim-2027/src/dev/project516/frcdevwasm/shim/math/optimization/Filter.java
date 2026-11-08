package dev.project516.frcdevwasm.shim.math.optimization;

import java.util.ArrayList;
import java.util.List;

/** The step filter of sleipnir/optimization/solver/util/filter.hpp. */
final class Filter {
  private static final double GAMMA_COST = 1e-8;
  private static final double GAMMA_CONSTRAINT = 1e-5;

  static final class Entry {
    final double cost;
    final double violation;

    Entry(double cost, double violation) {
      this.cost = cost;
      this.violation = violation;
    }

    static Entry of(double f, double[] ce) {
      return new Entry(f, Vec.norm1(ce));
    }

    /** The barrier entry f − μΣln(s), ‖cₑ‖₁ + ‖cᵢ − s‖₁. */
    static Entry of(double f, double[] s, double[] ce, double[] ci, double mu) {
      double logSum = 0.0;
      for (double v : s) {
        logSum += Math.log(v);
      }
      return new Entry(f - mu * logSum, Vec.norm1(ce) + Vec.norm1(Vec.minus(ci, s)));
    }

    boolean dominatedBy(Entry e) {
      return e.cost <= cost && e.violation <= violation;
    }
  }

  final double minConstraintViolation;
  double maxConstraintViolation;
  private final List<Entry> entries = new ArrayList<>();
  private boolean lastRejectionDueToFilter;

  Filter(double initialConstraintViolation) {
    minConstraintViolation = 1e-4 * Vec.max(1.0, initialConstraintViolation);
    maxConstraintViolation = 1e4 * Vec.max(1.0, initialConstraintViolation);
  }

  void reset() {
    entries.clear();
    lastRejectionDueToFilter = false;
  }

  boolean lastRejectionDueToFilter() {
    return lastRejectionDueToFilter;
  }

  boolean tryAdd(Entry current, Entry trial, double dPhi, double alpha) {
    if (!Double.isFinite(trial.cost) || trial.violation > maxConstraintViolation) {
      return false;
    }
    boolean switching =
        dPhi < 0.0 && alpha * Math.pow(-dPhi, 2.3) > Math.pow(current.violation, 1.1);
    boolean armijo = trial.cost <= current.cost + 1e-8 * alpha * dPhi;
    double phi = Math.pow(alpha, 1.5);
    boolean sufficientDecrease =
        trial.cost <= current.cost - phi * GAMMA_COST * current.violation
            || trial.violation <= (1.0 - phi * GAMMA_CONSTRAINT) * current.violation;
    if (current.violation <= minConstraintViolation && switching) {
      if (!armijo) {
        lastRejectionDueToFilter = false;
        return false;
      }
    } else if (!sufficientDecrease) {
      lastRejectionDueToFilter = false;
      return false;
    }
    for (Entry e : entries) {
      if (trial.dominatedBy(e)) {
        lastRejectionDueToFilter = true;
        return false;
      }
    }
    if (!switching || !armijo) {
      Entry added =
          new Entry(
              current.cost - phi * GAMMA_COST * current.violation,
              (1.0 - phi * GAMMA_CONSTRAINT) * current.violation);
      entries.removeIf(e -> e.dominatedBy(added));
      entries.add(added);
    }
    return true;
  }
}
