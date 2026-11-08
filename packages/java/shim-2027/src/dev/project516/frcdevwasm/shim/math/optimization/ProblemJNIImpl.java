package dev.project516.frcdevwasm.shim.math.optimization;

import dev.project516.frcdevwasm.shim.math.autodiff.Expr;
import dev.project516.frcdevwasm.shim.math.autodiff.ExprOwner;
import dev.project516.frcdevwasm.shim.math.autodiff.Handles;
import dev.project516.frcdevwasm.shim.math.autodiff.Jacobian;
import dev.project516.frcdevwasm.shim.math.autodiff.Sparse;
import dev.project516.frcdevwasm.shim.math.autodiff.VariableJNIImpl;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.wpilib.math.autodiff.NativeSparseTriplets;
import org.wpilib.math.optimization.Problem;

/** slp::Problem: picks Newton, SQP or the interior-point method by problem structure. */
public final class ProblemJNIImpl {
  private ProblemJNIImpl() {}

  private static final class State implements ExprOwner {
    final List<Expr> decisionVariables = new ArrayList<>();
    Expr cost;
    final List<Expr> equalities = new ArrayList<>();
    final List<Expr> inequalities = new ArrayList<>();

    @Override
    public void roots(List<Expr> out) {
      out.addAll(decisionVariables);
      out.add(cost);
      out.addAll(equalities);
      out.addAll(inequalities);
    }
  }

  private static State state(long handle) {
    return (State) Handles.get(handle);
  }

  public static long create() {
    return Handles.add(new State());
  }

  public static void destroy(long handle) {
    Handles.remove(handle);
  }

  public static long[] decisionVariable(long handle, int rows, int cols) {
    State p = state(handle);
    long[] out = new long[rows * cols];
    for (int i = 0; i < out.length; i++) {
      Expr v = Expr.decision(0.0);
      p.decisionVariables.add(v);
      out[i] = Handles.add(v);
    }
    return out;
  }

  /** One variable per lower-triangle element, shared across the diagonal. */
  public static long[] symmetricDecisionVariable(long handle, int rows) {
    State p = state(handle);
    Expr[] vars = new Expr[rows * rows];
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col <= row; col++) {
        Expr v = Expr.decision(0.0);
        p.decisionVariables.add(v);
        vars[row * rows + col] = v;
        vars[col * rows + row] = v;
      }
    }
    return Handles.addAll(vars);
  }

  public static void minimize(long handle, long costHandle) {
    state(handle).cost = Handles.expr(costHandle);
  }

  public static void maximize(long handle, long objectiveHandle) {
    state(handle).cost = Expr.neg(Handles.expr(objectiveHandle));
  }

  public static void subjectToEq(long handle, long[] constraintHandles) {
    for (long h : constraintHandles) {
      state(handle).equalities.add(Expr.minus(Handles.expr(h), Expr.constant(0.0)));
    }
  }

  public static void subjectToIneq(long handle, long[] constraintHandles) {
    for (long h : constraintHandles) {
      state(handle).inequalities.add(Expr.minus(Handles.expr(h), Expr.constant(0.0)));
    }
  }

  public static int costFunctionType(long handle) {
    Expr f = state(handle).cost;
    return f == null ? Expr.NONE : f.type();
  }

  public static int equalityConstraintType(long handle) {
    return maxType(state(handle).equalities);
  }

  public static int inequalityConstraintType(long handle) {
    return maxType(state(handle).inequalities);
  }

  private static int maxType(Collection<Expr> constraints) {
    int t = Expr.NONE;
    for (Expr c : constraints) {
      t = Math.max(t, c.type());
    }
    return t;
  }

  public static int solve(
      Problem obj,
      long handle,
      double tolerance,
      int maxIterations,
      double timeout,
      boolean feasibleIPM,
      boolean diagnostics) {
    Settings o = new Settings(tolerance, maxIterations, timeout, feasibleIPM, diagnostics);
    int status = new Solve(state(handle), o, javaCallbacks(obj)).run();
    if (diagnostics) {
      System.out.println("\nExit: " + Status.name(status));
    }
    return status;
  }

  private static Method runCallbacks;
  private static Field iterationCallbacks;

  /** Problem.runCallbacks(), which the native library calls through JNI on every iteration. */
  private static List<Callbacks.Iteration> javaCallbacks(Problem obj) {
    List<Callbacks.Iteration> out = new ArrayList<>();
    try {
      if (runCallbacks == null) {
        Method m =
            Problem.class.getDeclaredMethod(
                "runCallbacks",
                int.class,
                int.class,
                int.class,
                double[].class,
                NativeSparseTriplets.class,
                NativeSparseTriplets.class,
                NativeSparseTriplets.class,
                NativeSparseTriplets.class);
        m.setAccessible(true);
        Field f = Problem.class.getDeclaredField("m_iterationCallbacks");
        f.setAccessible(true);
        iterationCallbacks = f;
        runCallbacks = m;
      }
      // runCallbacks() returns false without callbacks; skip building its arguments.
      if (((Collection<?>) iterationCallbacks.get(obj)).isEmpty()) {
        return out;
      }
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    out.add(
        info -> {
          try {
            return (Boolean)
                runCallbacks.invoke(
                    obj,
                    info.ae.rows,
                    info.ai.rows,
                    info.iteration,
                    info.x.clone(),
                    info.g.triplets(),
                    info.h.triplets(),
                    info.ae.triplets(),
                    info.ai.triplets());
          } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) {
              throw (RuntimeException) c;
            }
            if (c instanceof Error) {
              throw (Error) c;
            }
            throw new IllegalStateException(c);
          } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
          }
        });
    return out;
  }

  /** slp::Problem::solve(). */
  private static final class Solve {
    final State p;
    final Settings o;
    final List<Callbacks.Iteration> callbacks;
    final Expr[] xAd;

    Solve(State p, Settings o, List<Callbacks.Iteration> callbacks) {
      this.p = p;
      this.o = o;
      this.callbacks = callbacks;
      xAd = p.decisionVariables.toArray(new Expr[0]);
    }

    void setX(double[] x) {
      for (int i = 0; i < xAd.length; i++) {
        xAd[i].val = x[i];
      }
    }

    static void set(Expr[] vars, double[] scale, double[] v) {
      for (int i = 0; i < vars.length; i++) {
        vars[i].val = scale[i] * v[i];
      }
    }

    static double[] values(Expr[] exprs) {
      double[] out = new double[exprs.length];
      for (int i = 0; i < exprs.length; i++) {
        out[i] = VariableJNIImpl.value(exprs[i]);
      }
      return out;
    }

    /** Σ ±yᵢcᵢ as Sleipnir's 1 x n by n x 1 VariableMatrix product. */
    static Expr dot(Expr[] y, Expr[] c, boolean negateY) {
      Expr sum = Expr.constant(0.0);
      for (int i = 0; i < y.length; i++) {
        sum = Expr.plus(sum, Expr.mul(negateY ? Expr.neg(y[i]) : y[i], c[i]));
      }
      return sum;
    }

    static Expr[] decisions(int n) {
      Expr[] out = new Expr[n];
      for (int i = 0; i < n; i++) {
        out[i] = Expr.decision(0.0);
      }
      return out;
    }

    int run() {
      int n = xAd.length;
      double[] x = new double[n];
      for (int i = 0; i < n; i++) {
        x[i] = VariableJNIImpl.value(xAd[i]);
      }
      int fType = p.cost == null ? Expr.NONE : p.cost.type();
      int eType = maxType(p.equalities);
      int iType = maxType(p.inequalities);
      if (o.diagnostics) {
        String[] names = {"no", "constant", "linear", "quadratic", "nonlinear"};
        System.out.println("\nProblem structure:");
        System.out.println("  ↳ " + names[fType] + " cost function");
        System.out.println("  ↳ " + names[eType] + " equality constraints");
        System.out.println("  ↳ " + names[iType] + " inequality constraints");
        System.out.println("\n" + n + (n == 1 ? " decision variable" : " decision variables"));
      }
      if (fType <= Expr.CONSTANT && eType <= Expr.CONSTANT && iType <= Expr.CONSTANT) {
        if (o.diagnostics) {
          System.out.println("\nInvoking no-op solver\n");
        }
        return Status.SUCCESS;
      }
      Expr f = p.cost != null ? p.cost : Expr.constant(0.0);
      Expr[] fRow = {f};
      int status;
      if (p.equalities.isEmpty() && p.inequalities.isEmpty()) {
        if (o.diagnostics) {
          System.out.println("\nInvoking Newton solver\n");
        }
        Jacobian g = new Jacobian(fRow, xAd, false);
        Jacobian h = Jacobian.hessian(f, xAd, true);
        setX(x);
        Kkt.Scaling sc = Kkt.Scaling.of(g.value().toDenseVector(), null, null);
        status = Newton.solve(new Base(n, 0, 0, sc, f, g, h, null, null, null), callbacks, o, x);
      } else if (p.inequalities.isEmpty()) {
        if (o.diagnostics) {
          System.out.println("\nInvoking SQP solver\n");
        }
        Expr[] ce = p.equalities.toArray(new Expr[0]);
        Expr[] y = decisions(ce.length);
        Jacobian g = new Jacobian(fRow, xAd, false);
        Jacobian hf = Jacobian.hessian(f, xAd, true);
        Jacobian hc = Jacobian.hessian(dot(y, ce, true), xAd, true);
        Jacobian ae = new Jacobian(ce, xAd, false);
        setX(x);
        Kkt.Scaling sc = Kkt.Scaling.of(g.value().toDenseVector(), ae.value(), null);
        Base m = new Base(n, ce.length, 0, sc, f, g, hf, hc, ae, null);
        m.ceAd = ce;
        m.yAd = y;
        status = Sqp.solve(m, callbacks, o, x);
      } else {
        if (o.diagnostics) {
          System.out.println("\nInvoking IPM solver\n");
        }
        Expr[] ce = p.equalities.toArray(new Expr[0]);
        Expr[] ci = p.inequalities.toArray(new Expr[0]);
        Expr[] y = decisions(ce.length);
        Expr[] z = decisions(ci.length);
        Jacobian g = new Jacobian(fRow, xAd, false);
        Jacobian hf = Jacobian.hessian(f, xAd, true);
        Jacobian hc =
            Jacobian.hessian(Expr.minus(dot(y, ce, true), dot(z, ci, false)), xAd, true);
        Jacobian ae = new Jacobian(ce, xAd, false);
        Jacobian ai = new Jacobian(ci, xAd, false);
        if (boundsConflict(ci, ai.value())) {
          return Status.GLOBALLY_INFEASIBLE;
        }
        setX(x);
        Kkt.Scaling sc =
            Kkt.Scaling.of(g.value().toDenseVector(), ae.value(), ai.value());
        Base m = new Base(n, ce.length, ci.length, sc, f, g, hf, hc, ae, ai);
        m.ceAd = ce;
        m.ciAd = ci;
        m.yAd = y;
        m.zAd = z;
        status = InteriorPoint.solve(m, callbacks, o, x);
      }
      setX(x);
      return status;
    }

    /**
     * get_bounds(): a linear inequality in one variable is a bound. Reports whether some lower
     * bound exceeds an upper one.
     */
    boolean boundsConflict(Expr[] ci, Sparse ai) {
      int n = xAd.length;
      double[] lower = Vec.filled(n, Double.NEGATIVE_INFINITY);
      double[] upper = Vec.filled(n, Double.POSITIVE_INFINITY);
      Sparse rows = ai.transpose();
      List<int[]> conflicts = new ArrayList<>();
      int[] lowerIndex = new int[n];
      int[] upperIndex = new int[n];
      java.util.Arrays.fill(lowerIndex, -1);
      java.util.Arrays.fill(upperIndex, -1);
      for (int j = 0; j < ci.length; j++) {
        if (ci[j].type() != Expr.LINEAR) {
          continue;
        }
        int from = rows.colStart[j];
        if (rows.colStart[j + 1] - from != 1) {
          continue;
        }
        double coefficient = rows.values[from];
        int index = rows.rowIndex[from];
        Expr v = xAd[index];
        double value = VariableJNIImpl.value(v);
        double constant;
        if (value != 0.0) {
          v.val = 0.0;
          constant = VariableJNIImpl.value(ci[j]);
          v.val = value;
        } else {
          constant = VariableJNIImpl.value(ci[j]);
        }
        double bound = -constant / coefficient;
        if (coefficient < 0.0 && bound < upper[index]) {
          upper[index] = bound;
          upperIndex[index] = j;
        } else if (coefficient > 0.0 && bound > lower[index]) {
          lower[index] = bound;
          lowerIndex[index] = j;
        }
        if (lower[index] > upper[index]) {
          conflicts.add(new int[] {lowerIndex[index], upperIndex[index]});
        }
      }
      if (!conflicts.isEmpty() && o.diagnostics) {
        System.out.println(
            "The problem is globally infeasible due to conflicting bound constraints:");
        for (int[] c : conflicts) {
          System.out.println(
              "  Inequality constraint "
                  + c[0]
                  + " gives a lower bound that is greater than the upper bound given by"
                  + " inequality constraint "
                  + c[1]);
        }
      }
      return !conflicts.isEmpty();
    }

    /** The problem's matrix callbacks, scaled; each sets the decision variables first. */
    final class Base extends Callbacks {
      final Expr f;
      final Jacobian g;
      final Jacobian hf;
      final Jacobian hc;
      final Jacobian ae;
      final Jacobian ai;
      Expr[] ceAd = new Expr[0];
      Expr[] ciAd = new Expr[0];
      Expr[] yAd = new Expr[0];
      Expr[] zAd = new Expr[0];

      Base(
          int n,
          int ne,
          int ni,
          Kkt.Scaling sc,
          Expr f,
          Jacobian g,
          Jacobian hf,
          Jacobian hc,
          Jacobian ae,
          Jacobian ai) {
        super(n, ne, ni, sc);
        this.f = f;
        this.g = g;
        this.hf = hf;
        this.hc = hc;
        this.ae = ae;
        this.ai = ai;
      }

      private void setMultipliers(double[] y, double[] z) {
        if (y != null) {
          set(yAd, scaling.ce, y);
        }
        if (z != null) {
          set(zAd, scaling.ci, z);
        }
      }

      @Override
      double f(double[] x) {
        setX(x);
        return scaling.f * VariableJNIImpl.value(f);
      }

      @Override
      Sparse g(double[] x) {
        setX(x);
        return g.value().transpose().scale(scaling.f);
      }

      @Override
      Sparse h(double[] x, double[] y, double[] z) {
        setX(x);
        if (hc == null) {
          return hf.value().scale(scaling.f);
        }
        setMultipliers(y, z);
        return hf.value().scale(scaling.f).plus(hc.value());
      }

      @Override
      double[] ce(double[] x) {
        setX(x);
        return Vec.times(scaling.ce, values(ceAd));
      }

      @Override
      Sparse ae(double[] x) {
        setX(x);
        return ae.value().scaleRows(scaling.ce);
      }

      @Override
      double[] ci(double[] x) {
        setX(x);
        return Vec.times(scaling.ci, values(ciAd));
      }

      @Override
      Sparse ai(double[] x) {
        setX(x);
        return ai.value().scaleRows(scaling.ci);
      }
    }
  }
}
