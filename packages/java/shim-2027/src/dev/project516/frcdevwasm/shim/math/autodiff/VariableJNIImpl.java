package dev.project516.frcdevwasm.shim.math.autodiff;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class VariableJNIImpl {
  private VariableJNIImpl() {}

  /** sizeof(slp::detail::Expression<double>) on 64-bit targets. */
  private static final long EXPRESSION_BYTES = 56;

  private static long wrap(Expr e) {
    return Handles.add(e);
  }

  private static Expr x(long h) {
    return Handles.expr(h);
  }

  public static long createDefault() {
    return wrap(Expr.decision(0.0));
  }

  public static long createDouble(double value) {
    return wrap(Expr.constant(value));
  }

  public static long createInt(int value) {
    return wrap(Expr.constant(value));
  }

  public static void destroy(long handle) {
    Handles.remove(handle);
  }

  public static void setValue(long handle, double value) {
    Expr e = x(handle);
    if (e.isDependent()) {
      System.err.println(
          "WARNING: /work/wpimath/src/main/native/thirdparty/sleipnir/include/sleipnir/autodiff/"
              + "variable.hpp:130: void slp::Variable<Scalar_>::set_value(Scalar) [with Scalar_ ="
              + " double; Scalar = double]: Modified the value of a dependent variable");
    }
    e.val = value;
  }

  public static long times(long handle, long rhs) {
    return wrap(Expr.mul(x(handle), x(rhs)));
  }

  public static long div(long handle, long rhs) {
    return wrap(Expr.div(x(handle), x(rhs)));
  }

  public static long plus(long handle, long rhs) {
    return wrap(Expr.plus(x(handle), x(rhs)));
  }

  public static long minus(long handle, long rhs) {
    return wrap(Expr.minus(x(handle), x(rhs)));
  }

  public static long unaryMinus(long handle) {
    return wrap(Expr.neg(x(handle)));
  }

  public static double value(long handle) {
    return value(x(handle));
  }

  /** slp::Variable::value(): sorts the graph once, then updates it. */
  public static double value(Expr e) {
    Expr[] g = e.graph;
    if (g == null) {
      g = Graph.topologicalSort(e);
      e.graph = g;
    }
    Graph.updateValues(g);
    return e.val;
  }

  public static int type(long handle) {
    return x(handle).type;
  }

  public static long abs(long x) {
    return wrap(Expr.abs(x(x)));
  }

  public static long acos(long x) {
    return wrap(Expr.acos(x(x)));
  }

  public static long asin(long x) {
    return wrap(Expr.asin(x(x)));
  }

  public static long atan(long x) {
    return wrap(Expr.atan(x(x)));
  }

  public static long atan2(long y, long x) {
    return wrap(Expr.atan2(x(y), x(x)));
  }

  public static long cbrt(long x) {
    return wrap(Expr.cbrt(x(x)));
  }

  public static long cos(long x) {
    return wrap(Expr.cos(x(x)));
  }

  public static long cosh(long x) {
    return wrap(Expr.cosh(x(x)));
  }

  public static long exp(long x) {
    return wrap(Expr.exp(x(x)));
  }

  public static long hypot(long x, long y) {
    return wrap(Expr.hypot(x(x), x(y)));
  }

  public static long log(long x) {
    return wrap(Expr.log(x(x)));
  }

  public static long log10(long x) {
    return wrap(Expr.log10(x(x)));
  }

  public static long max(long a, long b) {
    return wrap(Expr.max(x(a), x(b)));
  }

  public static long min(long a, long b) {
    return wrap(Expr.min(x(a), x(b)));
  }

  public static long pow(long base, long power) {
    return wrap(Expr.pow(x(base), x(power)));
  }

  public static long signum(long x) {
    return wrap(Expr.sign(x(x)));
  }

  public static long sin(long x) {
    return wrap(Expr.sin(x(x)));
  }

  public static long sinh(long x) {
    return wrap(Expr.sinh(x(x)));
  }

  public static long sqrt(long x) {
    return wrap(Expr.sqrt(x(x)));
  }

  public static long tan(long x) {
    return wrap(Expr.tan(x(x)));
  }

  public static long tanh(long x) {
    return wrap(Expr.tanh(x(x)));
  }

  /** Counts the expression nodes that live handles keep reachable, as the native pool would. */
  public static long totalNativeMemoryUsage() {
    List<Expr> stack = new ArrayList<>();
    for (Object o : Handles.all()) {
      if (o instanceof Expr) {
        stack.add((Expr) o);
      } else if (o instanceof ExprOwner) {
        ((ExprOwner) o).roots(stack);
      }
    }
    Map<Expr, Boolean> seen = new IdentityHashMap<>();
    while (!stack.isEmpty()) {
      Expr e = stack.remove(stack.size() - 1);
      if (e == null || seen.put(e, Boolean.TRUE) != null) {
        continue;
      }
      stack.add(e.lhs);
      stack.add(e.rhs);
    }
    return seen.size() * EXPRESSION_BYTES;
  }
}
