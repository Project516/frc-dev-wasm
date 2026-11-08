package dev.project516.frcdevwasm.shim.math.optimization;

/** slp::Options, plus the iteration counter that feasibility restoration shares. */
final class Settings {
  final double tolerance;
  final int maxIterations;
  final double timeout;
  final boolean feasibleIpm;
  final boolean diagnostics;
  int iterations;

  Settings(
      double tolerance, int maxIterations, double timeout, boolean feasibleIpm, boolean diagnostics) {
    this.tolerance = tolerance;
    this.maxIterations = maxIterations;
    this.timeout = timeout;
    this.feasibleIpm = feasibleIpm;
    this.diagnostics = diagnostics;
  }

  boolean timedOut(long startNanos) {
    return (System.nanoTime() - startNanos) / 1e9 > timeout;
  }

  /** A plain-text stand-in for Sleipnir's iteration table. */
  void print(char type, double error, double cost, double infeasibility, double alpha) {
    if (!diagnostics) {
      return;
    }
    if (iterations % 20 == 0) {
      System.out.println("iter      error         cost          infeas.       alpha");
    }
    System.out.println(
        String.format(
            "%4d%c  %12.6e  %12.6e  %12.6e  %8.2e",
            iterations, type, error, cost, infeasibility, alpha));
  }
}
