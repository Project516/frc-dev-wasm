package dev.project516.frcdevwasm.shim.math.autodiff;

/**
 * slp::Jacobian, and slp::Hessian as the Jacobian of the gradient tree. Linear rows are
 * differentiated once here; quadratic and nonlinear rows again on each value().
 */
public final class Jacobian implements ExprOwner {
  final Expr[] variables;
  final Expr[] wrt;
  private final Expr[][] graphs;
  private final int[][] outCols;
  private final Expr[][] outNodes;
  private final Sparse.Triplets cached;
  private final int[] nonlinearRows;
  private final boolean lower;
  private Sparse value;

  /** variables may contain null rows, which have no derivatives. */
  public Jacobian(Expr[] variables, Expr[] wrt, boolean lower) {
    this.variables = variables;
    this.wrt = wrt;
    this.lower = lower;
    int rows = variables.length;
    graphs = new Expr[rows][];
    for (int row = 0; row < rows; row++) {
      graphs[row] = Graph.topologicalSort(variables[row]);
    }
    for (int col = 0; col < wrt.length; col++) {
      wrt[col].scratch = col;
    }
    outCols = new int[rows][];
    outNodes = new Expr[rows][];
    for (int row = 0; row < rows; row++) {
      Expr[] g = graphs[row];
      int n = 0;
      for (Expr node : g) {
        if (node.scratch != -1) {
          n++;
        }
      }
      int[] cols = new int[n];
      Expr[] nodes = new Expr[n];
      n = 0;
      for (Expr node : g) {
        if (node.scratch != -1) {
          cols[n] = node.scratch;
          nodes[n++] = node;
        }
      }
      outCols[row] = cols;
      outNodes[row] = nodes;
    }
    for (Expr w : wrt) {
      w.scratch = -1;
    }
    cached = new Sparse.Triplets(rows);
    int[] nonlinear = new int[rows];
    int numNonlinear = 0;
    for (int row = 0; row < rows; row++) {
      Expr v = variables[row];
      if (v == null) {
        continue;
      }
      if (v.type == Expr.LINEAR) {
        append(row, cached);
      } else if (v.type > Expr.LINEAR) {
        nonlinear[numNonlinear++] = row;
      }
    }
    nonlinearRows = java.util.Arrays.copyOf(nonlinear, numNonlinear);
    if (numNonlinear == 0) {
      value = build(cached);
    }
  }

  /** The Hessian of f with respect to wrt. */
  public static Jacobian hessian(Expr f, Expr[] wrt, boolean lower) {
    return new Jacobian(Graph.gradientTree(Graph.topologicalSort(f), wrt), wrt, lower);
  }

  @Override
  public void roots(java.util.List<Expr> out) {
    java.util.Collections.addAll(out, variables);
    java.util.Collections.addAll(out, wrt);
  }

  public int rows() {
    return variables.length;
  }

  public int cols() {
    return wrt.length;
  }

  private void append(int row, Sparse.Triplets out) {
    Expr[] g = graphs[row];
    if (g.length == 0) {
      return;
    }
    Graph.computeAdjoints(g);
    int[] cols = outCols[row];
    Expr[] nodes = outNodes[row];
    for (int i = 0; i < cols.length; i++) {
      out.add(row, cols[i], nodes[i].adjoint);
    }
  }

  private Sparse build(Sparse.Triplets t) {
    Sparse s = Sparse.fromTriplets(t, variables.length, wrt.length);
    return lower ? s.lower() : s;
  }

  /** The derivatives as expressions, row-major, constant zero where there is none. */
  public Expr[] get() {
    int cols = wrt.length;
    Expr[] out = new Expr[variables.length * cols];
    for (int row = 0; row < variables.length; row++) {
      Expr[] grad = Graph.gradientTree(graphs[row], wrt);
      for (int col = 0; col < cols; col++) {
        Expr e = grad[col];
        out[row * cols + col] = e != null ? e : Expr.constant(0.0);
      }
    }
    return out;
  }

  /** The derivatives at the current values. */
  public Sparse value() {
    if (nonlinearRows.length == 0) {
      return value;
    }
    for (Expr[] g : graphs) {
      Graph.updateValues(g);
    }
    Sparse.Triplets t = cached.copy();
    for (int row : nonlinearRows) {
      append(row, t);
    }
    value = build(t);
    return value;
  }
}
