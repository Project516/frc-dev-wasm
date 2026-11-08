package dev.project516.frcdevwasm.shim.math.optimization;

/** slp::ExitStatus values, as ExitStatus.fromInt() reads them. */
final class Status {
  private Status() {}

  static final int SUCCESS = 0;
  static final int CALLBACK_REQUESTED_STOP = 1;
  static final int TOO_FEW_DOFS = -1;
  static final int LOCALLY_INFEASIBLE = -2;
  static final int GLOBALLY_INFEASIBLE = -3;
  static final int FACTORIZATION_FAILED = -4;
  static final int LINE_SEARCH_FAILED = -5;
  static final int FEASIBILITY_RESTORATION_FAILED = -6;
  static final int NONFINITE_INITIAL_GUESS = -7;
  static final int DIVERGING_ITERATES = -8;
  static final int MAX_ITERATIONS_EXCEEDED = -9;
  static final int TIMEOUT = -10;

  static String name(int status) {
    switch (status) {
      case SUCCESS:
        return "success";
      case CALLBACK_REQUESTED_STOP:
        return "callback requested stop";
      case TOO_FEW_DOFS:
        return "too few degrees of freedom";
      case LOCALLY_INFEASIBLE:
        return "locally infeasible";
      case GLOBALLY_INFEASIBLE:
        return "globally infeasible";
      case FACTORIZATION_FAILED:
        return "factorization failed";
      case LINE_SEARCH_FAILED:
        return "line search failed";
      case FEASIBILITY_RESTORATION_FAILED:
        return "feasibility restoration failed";
      case NONFINITE_INITIAL_GUESS:
        return "nonfinite initial guess";
      case DIVERGING_ITERATES:
        return "diverging iterates";
      case MAX_ITERATIONS_EXCEEDED:
        return "max iterations exceeded";
      default:
        return "timeout";
    }
  }
}
