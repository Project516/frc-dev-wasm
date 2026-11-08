package dev.project516.frcdevwasm.shim.test;

import dev.project516.frcdevwasm.shim.math.autodiff.GradientJNIImpl;
import dev.project516.frcdevwasm.shim.math.autodiff.HessianJNIImpl;
import dev.project516.frcdevwasm.shim.math.autodiff.JacobianJNIImpl;
import java.util.function.BinaryOperator;
import java.util.function.DoubleBinaryOperator;
import org.wpilib.math.autodiff.ExpressionType;
import org.wpilib.math.autodiff.Gradient;
import org.wpilib.math.autodiff.Hessian;
import org.wpilib.math.autodiff.Jacobian;
import org.wpilib.math.autodiff.NativeSparseTriplets;
import org.wpilib.math.autodiff.Variable;
import org.wpilib.math.autodiff.VariableMatrix;
import org.wpilib.math.autodiff.VariablePool;
import org.wpilib.math.jni.WPIMathJNI;

/**
 * Sleipnir autodiff. Expected numbers marked native come from the wpimath 2027.0.0-alpha-7
 * linuxarm64 JNI library.
 */
public final class MathAutodiffTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  private record Fn(String name, BinaryOperator<Variable> f, DoubleBinaryOperator d, double a, double b) {}

  private static final Fn[] FUNCTIONS = {
    new Fn("plus", Variable::plus, Double::sum, 0.5, 1.5),
    new Fn("minus", Variable::minus, (a, b) -> a - b, 0.5, 1.5),
    new Fn("times", Variable::times, (a, b) -> a * b, -0.3, 0.7),
    new Fn("div", Variable::div, (a, b) -> a / b, 2.0, -1.0),
    new Fn("neg", (a, b) -> a.unaryMinus().times(b), (a, b) -> -a * b, 0.5, 1.5),
    new Fn("abs", (a, b) -> Variable.abs(a.times(b)), (a, b) -> Math.abs(a * b), -0.3, 0.7),
    new Fn("acos", (a, b) -> Variable.acos(a.times(b)), (a, b) -> Math.acos(a * b), 0.5, 0.8),
    new Fn("asin", (a, b) -> Variable.asin(a.times(b)), (a, b) -> Math.asin(a * b), 0.5, 0.8),
    new Fn("atan", (a, b) -> Variable.atan(a.times(b)), (a, b) -> Math.atan(a * b), 0.5, 1.5),
    new Fn("atan2", Variable::atan2, Math::atan2, 0.5, 1.5),
    new Fn("cbrt", (a, b) -> Variable.cbrt(a.times(b)), (a, b) -> Math.cbrt(a * b), 2.0, -1.0),
    new Fn("cos", (a, b) -> Variable.cos(a.times(b)), (a, b) -> Math.cos(a * b), 0.5, 1.5),
    new Fn("cosh", (a, b) -> Variable.cosh(a.times(b)), (a, b) -> Math.cosh(a * b), 0.5, 1.5),
    new Fn("exp", (a, b) -> Variable.exp(a.times(b)), (a, b) -> Math.exp(a * b), 0.5, 1.5),
    new Fn("hypot", Variable::hypot, Math::hypot, -0.3, 0.7),
    new Fn("log", (a, b) -> Variable.log(a.times(b)), (a, b) -> Math.log(a * b), 0.5, 1.5),
    new Fn("log10", (a, b) -> Variable.log10(a.times(b)), (a, b) -> Math.log10(a * b), 0.5, 1.5),
    new Fn("max", Variable::max, Math::max, 0.5, 1.5),
    new Fn("min", Variable::min, Math::min, 0.5, 1.5),
    new Fn("pow", Variable::pow, Math::pow, 1.3, 0.7),
    new Fn("sin", (a, b) -> Variable.sin(a.times(b)), (a, b) -> Math.sin(a * b), 0.5, 1.5),
    new Fn("sinh", (a, b) -> Variable.sinh(a.times(b)), (a, b) -> Math.sinh(a * b), 0.5, 1.5),
    new Fn("sqrt", (a, b) -> Variable.sqrt(a.times(b)), (a, b) -> Math.sqrt(a * b), 0.5, 1.5),
    new Fn("tan", (a, b) -> Variable.tan(a.times(b)), (a, b) -> Math.tan(a * b), 0.5, 1.5),
    new Fn("tanh", (a, b) -> Variable.tanh(a.times(b)), (a, b) -> Math.tanh(a * b), 0.5, 1.5),
    new Fn("hypot3", (a, b) -> Variable.hypot(a, b, a.times(b)), (a, b) -> Math.sqrt(a * a + b * b + a * a * b * b), 0.5, 1.5),
  };

  private static double[] grad(DoubleBinaryOperator d, double a, double b) {
    double h = 1e-6;
    return new double[] {
      (d.applyAsDouble(a + h, b) - d.applyAsDouble(a - h, b)) / (2 * h),
      (d.applyAsDouble(a, b + h) - d.applyAsDouble(a, b - h)) / (2 * h)
    };
  }

  private static double[] hess(DoubleBinaryOperator d, double a, double b) {
    double h = 1e-4;
    double[] ga = grad(d, a + h, b);
    double[] gam = grad(d, a - h, b);
    double[] gb = grad(d, a, b + h);
    double[] gbm = grad(d, a, b - h);
    return new double[] {
      (ga[0] - gam[0]) / (2 * h), (gb[0] - gbm[0]) / (2 * h),
      (ga[1] - gam[1]) / (2 * h), (gb[1] - gbm[1]) / (2 * h)
    };
  }

  public static void testValuesAndDerivativesMatchFiniteDifferences() {
    try (var pool = new VariablePool()) {
      for (Fn fn : FUNCTIONS) {
        var a = new Variable();
        var b = new Variable();
        a.setValue(fn.a());
        b.setValue(fn.b());
        var y = fn.f().apply(a, b);
        var wrt = new VariableMatrix(new Variable[][] {{a}, {b}});
        var g = new Gradient(y, wrt);
        var h = new Hessian(y, wrt);
        for (double shift : new double[] {0.0, 0.05}) {
          double av = fn.a() + shift;
          double bv = fn.b() - shift;
          a.setValue(av);
          b.setValue(bv);
          String n = fn.name() + " at " + av + ", " + bv;
          T.near(fn.d().applyAsDouble(av, bv), y.value(), 1e-14, n + " value");
          double[] gfd = grad(fn.d(), av, bv);
          var gv = g.value();
          T.near(gfd[0], gv.get(0, 0), 1e-7, n + " d/da");
          T.near(gfd[1], gv.get(1, 0), 1e-7, n + " d/db");
          double[] hfd = hess(fn.d(), av, bv);
          var hv = h.value();
          for (int i = 0; i < 4; i++) {
            T.near(hfd[i], hv.get(i / 2, i % 2), 1e-4, n + " hessian " + i);
          }
          // The gradient is itself an expression: differentiating it again gives the Hessian.
          var gExpr = g.get();
          var hOfG = new Jacobian(gExpr, wrt).value();
          for (int i = 0; i < 4; i++) {
            T.near(hv.get(i / 2, i % 2), hOfG.get(i / 2, i % 2), 1e-12, n + " jacobian of gradient " + i);
          }
          T.near(gv.get(0, 0), gExpr.get(0).value(), 1e-15, n + " gradient expression");
        }
      }
    }
  }

  public static void testNativeReferenceValues() {
    try (var pool = new VariablePool()) {
      var x = new Variable();
      x.setValue(0.5);
      var mix = Variable.sin(x).times(Variable.exp(x)).div(Variable.hypot(x, 1.5));
      var g = new Gradient(mix, x);
      var h = new Hessian(mix, x);
      T.near(0.4999175709140778, mix.value(), 1e-14, "native mix value");
      T.near(1.3150270321577957, g.value().get(0, 0), 1e-14, "native mix gradient");
      T.near(1.1242048124608808, h.value().get(0, 0), 1e-14, "native mix hessian");
      x.setValue(0.75);
      T.near(0.8604564087875556, mix.value(), 1e-14, "native mix value after setValue");
      T.near(1.5546377753236622, g.value().get(0, 0), 1e-14, "native mix gradient after setValue");
      T.near(0.773380625753385, h.value().get(0, 0), 1e-14, "native mix hessian after setValue");
      T.near(-1.623167099228778, new Hessian(g.get().get(0), x).value().get(0, 0), 1e-14, "native hessian of gradient");

      var e2 = new VariableMatrix(new double[][] {{0.0, 1.0}, {-2.0, -0.5}}).exp();
      double[] exp2 = {0.2761965770253718, 0.5505817408185057, -1.1011634816370115, 9.057066161193778E-4};
      for (int i = 0; i < 4; i++) {
        T.near(exp2[i], e2.value(i), 1e-15, "native exp " + i);
      }

      double[][] expectedSolve = {
        {0.3333333333333333},
        {0.2987865744699888, 0.32710083486869634, 0.7344123488574806, 0.13249358224227833},
        {0.38188434891969397, 0.3121680528412287, 0.5734545437098697, 0.16141791668812575, 0.8109033251551303, -0.14572042007237154},
        {0.1894374812043226, 0.3664371894512288, 0.7415626380061344, 0.11401220287778824, 1.2568243353971233, -0.27146810570310553, 1.64567825284609, -0.4640737413918289},
        {0.49254225853112993, 0.3201383098621734, 0.5880809776847814, 0.13745633653024128, 0.7222992186370725, -0.18982005569063012, 0.9932005321104032, -0.3644085771100017, 1.4315136767934529, -0.21866194237013217},
        {0.08247803501136221, 0.2937472407861108, 0.6654614321831391, 0.14243641721680925, 1.2490380403336845, -0.1559199985017373, 1.7100392478664674, -0.318273998722423, 1.985627502714954, -0.18300007425987821, 2.104224672154925, 0.13542449082178612}
      };
      for (int n = 1; n <= 6; n++) {
        double[][] a = new double[n][n];
        double[][] b = new double[n][n == 1 ? 1 : 2];
        for (int i = 0; i < n; i++) {
          for (int j = 0; j < n; j++) {
            a[i][j] = (i == j ? 3.0 : 0.0) + Math.sin(i * 7 + j * 3);
          }
          b[i][0] = i + 1;
          if (n > 1) {
            b[i][1] = Math.cos(i);
          }
        }
        var sol = VariableMatrix.solve(new VariableMatrix(a), new VariableMatrix(b));
        for (int i = 0; i < expectedSolve[n - 1].length; i++) {
          T.near(expectedSolve[n - 1][i], sol.value(i), 1e-14, "native solve " + n + " element " + i);
        }
      }

      var t = new Variable();
      t.setValue(0.3);
      var a = new VariableMatrix(new Variable[][] {{t.plus(2), new Variable(1)}, {new Variable(0.5), t.times(t).plus(1)}});
      var sol = VariableMatrix.solve(a, new VariableMatrix(new double[][] {{1}, {2}}));
      T.near(-0.4534130543099152, sol.value(0), 1e-15, "native solve with variables");
      T.near(-0.6606424102586634, new Gradient(sol.get(0).plus(sol.get(1)), t).value().get(0, 0), 1e-15, "native solve gradient");
    }
  }

  public static void testExpressionTypes() {
    try (var pool = new VariablePool()) {
      var x = new Variable();
      var c = new Variable(2.0);
      ExpressionType[] actual = {
        c.type(), x.type(), x.times(c).type(), x.times(x).type(), x.times(x).times(x).type(),
        x.div(c).type(), c.div(x).type(), x.plus(c).type(), Variable.pow(x, 2).type(),
        Variable.pow(x.times(x), 2).type(), Variable.sin(c).type(), Variable.sin(x).type(),
        x.times(0.0).type(), x.minus(x).type(), new Variable(3).type()
      };
      String expected =
          "CONSTANT LINEAR LINEAR QUADRATIC NONLINEAR LINEAR NONLINEAR LINEAR QUADRATIC NONLINEAR"
              + " CONSTANT NONLINEAR CONSTANT LINEAR CONSTANT";
      T.eq(expected, String.join(" ", java.util.Arrays.stream(actual).map(Enum::name).toArray(String[]::new)), "native types");
    }
  }

  private static void triplets(NativeSparseTriplets t, int[] rows, int[] cols, double[] vals, String msg) {
    T.eq(rows, t.rows(), msg + " rows");
    T.eq(cols, t.cols(), msg + " cols");
    T.near(vals, t.values(), 1e-15, msg + " values");
  }

  public static void testSparsityMatchesNative() {
    try (var pool = new VariablePool()) {
      var a = new Variable();
      var b = new Variable();
      var d = new Variable();
      a.setValue(1.0);
      b.setValue(0.0);
      d.setValue(2.0);
      Variable[] f = {a.times(b), d.plus(a), Variable.sin(b), new Variable(4.0), b.times(b).times(d)};
      long[] fh = new long[f.length];
      for (int i = 0; i < f.length; i++) {
        fh[i] = f[i].getHandle();
      }
      long[] wrt = {a.getHandle(), b.getHandle(), d.getHandle()};
      long j = JacobianJNIImpl.create(fh, wrt);
      int[] rows = {0, 1, 0, 2, 4, 1, 4};
      int[] cols = {0, 0, 1, 1, 1, 2, 2};
      // Explicit zeros stay, as in Eigen's setFromTriplets.
      triplets(JacobianJNIImpl.value(j), rows, cols, new double[] {0.0, 1.0, 1.0, 1.0, 0.0, 1.0, 0.0}, "native jacobian");
      b.setValue(0.5);
      triplets(JacobianJNIImpl.value(j), rows, cols, new double[] {0.5, 1.0, 1.0, 0.8775825618903728, 2.0, 1.0, 0.25}, "native jacobian after setValue");

      var jac = new Jacobian(new VariableMatrix(new Variable[][] {{f[0]}, {f[1]}, {f[2]}, {f[3]}, {f[4]}}), new VariableMatrix(new Variable[][] {{a}, {b}, {d}}));
      var got = jac.get();
      int[] types = {2, 2, 1, 1, 1, 1, 1, 4, 1, 1, 1, 1, 1, 3, 3};
      for (int i = 0; i < 15; i++) {
        T.eq(types[i], got.get(i).type().value, "native jacobian get() type " + i);
      }
      JacobianJNIImpl.destroy(j);

      var sum = a.times(b).plus(b.times(b).times(d)).plus(Variable.cos(a.times(d)));
      long h = HessianJNIImpl.create(sum.getHandle(), wrt);
      triplets(
          HessianJNIImpl.value(h),
          new int[] {0, 1, 2, 0, 1, 2, 0, 1, 2},
          new int[] {0, 0, 0, 1, 1, 1, 2, 2, 2},
          new double[] {1.6645873461885696, 1.0, -0.0770037537313969, 1.0, 4.0, 1.0, -0.0770037537313969, 1.0, 0.4161468365471424},
          "native hessian");
      long g = GradientJNIImpl.create(sum.getHandle(), wrt);
      triplets(GradientJNIImpl.value(g), new int[] {0, 1, 2}, new int[] {0, 0, 0}, new double[] {-1.3185948536513634, 3.0, -0.6592974268256817}, "native gradient");
      HessianJNIImpl.destroy(h);
      GradientJNIImpl.destroy(g);

      // max/min pick the left operand on ties; their second derivatives are zero.
      var p = new Variable();
      var q = new Variable();
      p.setValue(1.0);
      q.setValue(1.0);
      var m = Variable.max(p, q);
      long mg = GradientJNIImpl.create(m.getHandle(), new long[] {p.getHandle(), q.getHandle()});
      triplets(GradientJNIImpl.value(mg), new int[] {0, 1}, new int[] {0, 0}, new double[] {1.0, 0.0}, "native max gradient");
      GradientJNIImpl.destroy(mg);
    }
  }

  public static void testMemoryIsReleasedWithThePool() {
    long before = Variable.totalNativeMemoryUsage();
    try (var pool = new VariablePool()) {
      var x = new Variable();
      var y = Variable.sin(x).times(x);
      var h = new Hessian(y, x);
      T.check(Variable.totalNativeMemoryUsage() > before, "usage grows");
      h.close();
    }
    T.eq(before, Variable.totalNativeMemoryUsage(), "usage after the pool closes");
  }
}
