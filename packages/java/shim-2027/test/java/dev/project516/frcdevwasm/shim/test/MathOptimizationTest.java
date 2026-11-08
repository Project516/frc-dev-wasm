package dev.project516.frcdevwasm.shim.test;

import java.util.function.BiConsumer;
import org.ejml.simple.SimpleMatrix;
import org.wpilib.math.autodiff.Variable;
import org.wpilib.math.autodiff.VariableMatrix;
import org.wpilib.math.controller.ArmFeedforward;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.math.jni.WPIMathJNI;
import org.wpilib.math.optimization.Constraints;
import org.wpilib.math.optimization.OCP;
import org.wpilib.math.optimization.Problem;
import org.wpilib.math.optimization.ocp.DynamicsType;
import org.wpilib.math.optimization.ocp.TimestepMethod;
import org.wpilib.math.optimization.ocp.TranscriptionMethod;
import org.wpilib.math.optimization.solver.ExitStatus;
import org.wpilib.math.optimization.solver.Options;
import org.wpilib.math.path.TravelingSalesman;
import org.wpilib.math.shape.Ellipse2d;

/**
 * The Sleipnir solvers. Expected statuses, solutions and iteration counts come from the wpimath
 * 2027.0.0-alpha-7 linuxarm64 JNI library on the same problems.
 */
public final class MathOptimizationTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  private static final double TOL = 1e-9;

  /** Counts callback calls. */
  private static int[] counter(Problem p) {
    int[] n = {0};
    p.addCallback(info -> {
      n[0]++;
      return false;
    });
    return n;
  }

  private static Variable rosenbrock(Variable x, Variable y) {
    return Variable.pow(new Variable(1).minus(x), 2).plus(Variable.pow(y.minus(x.times(x)), 2).times(100));
  }

  public static void testUnconstrainedQuadraticUsesNewton() {
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      var y = p.decisionVariable();
      p.minimize(x.minus(3).times(x.minus(3)).plus(y.plus(1).times(y.plus(1)).times(2)).plus(x.times(y)));
      double[][] first = new double[1][];
      int[] n = {0};
      p.addCallback(info -> {
        if (n[0]++ == 0) {
          first[0] = new double[] {
            info.g.get(0, 0), info.g.get(1, 0), info.H.get(0, 0), info.H.get(1, 0),
            info.H.get(0, 1), info.H.get(1, 1), info.A_e.getNumRows(), info.A_i.getNumRows()
          };
        }
        return false;
      });
      T.eq(ExitStatus.SUCCESS, p.solve());
      T.near(4.0, x.value(), TOL, "x");
      T.near(-2.0, y.value(), TOL, "y");
      T.eq(1, n[0], "native iterations");
      T.eq("QUADRATIC NONE NONE", p.costFunctionType() + " " + p.equalityConstraintType() + " " + p.inequalityConstraintType());
      // The callback sees the lower triangle of the Hessian only, as natively.
      T.near(new double[] {-6.0, 4.0, 2.0, 1.0, 0.0, 4.0, 0.0, 0.0}, first[0], 1e-12, "native first iteration");
    }
  }

  public static void testRosenbrock() {
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      var y = p.decisionVariable();
      x.setValue(-1.2);
      y.setValue(1.0);
      p.minimize(rosenbrock(x, y));
      int[] n = counter(p);
      T.eq(ExitStatus.SUCCESS, p.solve());
      T.near(0.9999999999400667, x.value(), TOL, "native x");
      T.near(0.9999999998789006, y.value(), TOL, "native y");
      T.eq(21, n[0], "native iterations");
    }
  }

  public static void testEqualityConstrainedUsesSqp() {
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      var y = p.decisionVariable();
      x.setValue(1.0);
      y.setValue(1.0);
      p.minimize(x.times(x).plus(y.times(y).times(2)));
      p.subjectTo(Constraints.eq(x.plus(y), 1.0));
      double[][] first = new double[1][];
      int[] n = {0};
      p.addCallback(info -> {
        if (n[0]++ == 0) {
          first[0] = new double[] {info.A_e.getNumRows(), info.A_e.get(0, 0), info.A_e.get(0, 1), info.x.get(0, 0)};
        }
        return false;
      });
      T.eq(ExitStatus.SUCCESS, p.solve());
      T.near(2.0 / 3.0, x.value(), TOL, "x");
      T.near(1.0 / 3.0, y.value(), TOL, "y");
      T.eq(1, n[0], "native iterations");
      T.near(new double[] {1.0, 1.0, 1.0, 1.0}, first[0], 1e-12, "native first iteration");
    }
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      var y = p.decisionVariable();
      x.setValue(2.0);
      y.setValue(0.5);
      p.minimize(x.plus(y));
      p.subjectTo(Constraints.eq(x.times(x).plus(y.times(y)), 1.0));
      int[] n = counter(p);
      T.eq(ExitStatus.SUCCESS, p.solve());
      T.near(-0.7071067811865478, x.value(), TOL, "native nonlinear x");
      T.near(-0.7071067811865475, y.value(), TOL, "native nonlinear y");
      T.eq(15, n[0], "native nonlinear iterations");
    }
  }

  public static void testInequalityConstrainedUsesInteriorPoint() {
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      var y = p.decisionVariable();
      p.minimize(x.minus(2).times(x.minus(2)).plus(y.minus(2).times(y.minus(2))));
      p.subjectTo(Constraints.le(x.plus(y), 1.0));
      p.subjectTo(Constraints.ge(x, 0.0));
      int[] n = counter(p);
      T.eq(ExitStatus.SUCCESS, p.solve());
      T.near(0.5000000008361989, x.value(), TOL, "native x");
      T.near(0.49999999832798964, y.value(), TOL, "native y");
      T.eq(6, n[0], "native iterations");
    }
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      var y = p.decisionVariable();
      var z = p.decisionVariable();
      x.setValue(1);
      y.setValue(1);
      z.setValue(1);
      p.minimize(Variable.exp(x).plus(y.times(y)).plus(Variable.sin(z).times(z)));
      p.subjectTo(Constraints.eq(x.plus(y).plus(z), 2.0));
      p.subjectTo(Constraints.ge(x.times(y), 0.25));
      p.subjectTo(Constraints.bounds(-1.0, z, 3.0));
      int[] n = counter(p);
      T.eq(ExitStatus.SUCCESS, p.solve());
      T.near(0.3577659556442208, x.value(), TOL, "native mixed x");
      T.near(0.6987809272960888, y.value(), TOL, "native mixed y");
      T.near(0.9434531170596906, z.value(), TOL, "native mixed z");
      T.eq(8, n[0], "native mixed iterations");
    }
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      x.setValue(4.0);
      p.minimize(Variable.sqrt(x).plus(x.times(0.1)));
      p.subjectTo(Constraints.ge(x, 0.5));
      T.eq(ExitStatus.SUCCESS, p.solve(new Options().withFeasibleIPM(true)));
      T.near(0.5000000031002532, x.value(), TOL, "native feasible IPM x");
    }
  }

  private static ExitStatus solveOne(BiConsumer<Problem, Variable> setup, double x0, double[] out) {
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      x.setValue(x0);
      setup.accept(p, x);
      var status = p.solve();
      out[0] = x.value();
      return status;
    }
  }

  public static void testFailuresReportNativeStatuses() {
    double[] x = new double[1];
    T.eq(ExitStatus.GLOBALLY_INFEASIBLE, solveOne((p, v) -> {
      p.minimize(v.times(v));
      p.subjectTo(Constraints.ge(v, 2.0));
      p.subjectTo(Constraints.le(v, 1.0));
    }, 0.0, x));
    T.eq(ExitStatus.MAX_ITERATIONS_EXCEEDED, solveOne((p, v) -> {
      p.minimize(v);
      p.subjectTo(Constraints.eq(v.times(v), -1.0));
    }, 0.5, x));
    T.near(-1.802871392967344E-4, x[0], TOL, "native locally infeasible equality x");
    T.eq(ExitStatus.TOO_FEW_DOFS, solveOne((p, v) -> {
      p.subjectTo(Constraints.eq(v, 1.0));
      p.subjectTo(Constraints.eq(v.times(2), 3.0));
    }, 0.0, x));
    T.eq(ExitStatus.DIVERGING_ITERATES, solveOne((p, v) -> p.minimize(Variable.exp(v.times(-1)).plus(v.times(-1))), 1.0, x));
    T.near(1.0485750117693975E10, x[0], 1e-3, "native diverging x");
    T.eq(ExitStatus.DIVERGING_ITERATES, solveOne((p, v) -> p.minimize(v), 0.0, x));
    T.near(-1.048575E10, x[0], 1e-3, "native linear unbounded x");
    T.eq(ExitStatus.NONFINITE_INITIAL_GUESS, solveOne((p, v) -> p.minimize(Variable.log(v)), 0.0, x));
    T.eq(ExitStatus.SUCCESS, solveOne((p, v) -> p.minimize(new Variable(3.0)), 0.0, x));
    try (var p = new Problem()) {
      var a = p.decisionVariable();
      var b = p.decisionVariable();
      a.setValue(0.3);
      p.minimize(a.plus(b));
      p.subjectTo(Constraints.le(a.times(a).plus(b.times(b)), -1.0));
      // Natively too; the iterates it gives up at depend on rounding.
      T.eq(ExitStatus.FEASIBILITY_RESTORATION_FAILED, p.solve());
    }
  }

  public static void testIterationLimitAndCallbackStop() {
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      var y = p.decisionVariable();
      x.setValue(-1.2);
      y.setValue(1.0);
      p.minimize(rosenbrock(x, y));
      T.eq(ExitStatus.MAX_ITERATIONS_EXCEEDED, p.solve(new Options().withMaxIterations(3)));
      T.near(-0.7825400789708494, x.value(), TOL, "native x");
      T.near(0.5897363758123972, y.value(), TOL, "native y");
    }
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      var y = p.decisionVariable();
      x.setValue(-1.2);
      y.setValue(1.0);
      p.minimize(rosenbrock(x, y));
      p.addCallback(info -> info.iteration >= 2);
      T.eq(ExitStatus.CALLBACK_REQUESTED_STOP, p.solve());
      T.near(-0.9329814276197699, x.value(), TOL, "native x");
      T.near(0.8112106557968658, y.value(), TOL, "native y");
      p.clearCallbacks();
      T.eq(ExitStatus.SUCCESS, p.solve());
    }
  }

  public static void testMaximizeAndSymmetricVariables() {
    try (var p = new Problem()) {
      var x = p.decisionVariable();
      p.maximize(x.times(x).times(-1).plus(x.times(4)));
      T.eq(ExitStatus.SUCCESS, p.solve());
      T.near(2.0, x.value(), TOL, "x");
    }
    try (var p = new Problem()) {
      var s = p.symmetricDecisionVariable(2);
      p.minimize(s.get(0, 0).minus(1).times(s.get(0, 0).minus(1))
          .plus(s.get(0, 1).minus(2).times(s.get(1, 0).minus(2)))
          .plus(s.get(1, 1).times(s.get(1, 1))));
      T.eq(ExitStatus.SUCCESS, p.solve());
      T.near(new double[] {1.0, 2.0, 2.0, 0.0}, new double[] {s.value(0, 0), s.value(0, 1), s.value(1, 0), s.value(1, 1)}, TOL, "S");
    }
  }

  public static void testOptimalControlProblem() {
    try (var ocp = new OCP(2, 1, 0.1, 20,
        (VariableMatrix x, VariableMatrix u) -> new VariableMatrix(new Variable[][] {{x.get(1)}, {u.get(0)}}),
        DynamicsType.EXPLICIT_ODE, TimestepMethod.FIXED, TranscriptionMethod.DIRECT_TRANSCRIPTION)) {
      ocp.constrainInitialState(new SimpleMatrix(new double[][] {{0}, {0}}));
      ocp.constrainFinalState(new SimpleMatrix(new double[][] {{1}, {0}}));
      ocp.setLowerInputBound(-3.0);
      ocp.setUpperInputBound(3.0);
      var u = ocp.U();
      var cost = new Variable(0.0);
      for (int i = 0; i < 20; i++) {
        cost = cost.plus(u.get(0, i).times(u.get(0, i)));
      }
      ocp.minimize(cost);
      int[] n = counter(ocp);
      T.eq(ExitStatus.SUCCESS, ocp.solve());
      T.near(1.4285714285275344, u.value(0, 0), TOL, "native u0");
      T.near(-0.07518796992863706, u.value(0, 10), TOL, "native u10");
      T.near(1.0, ocp.X().value(0, 20), TOL, "final position");
      T.eq(6, n[0], "native iterations");
    }
  }

  private static final double[][] ARM = {
    {1.0471975511965976, 1.0, 1.05, 7.528732135532033}, {1.0471975511965976, 1.0, 0.95, -2.5461620597327626},
    {1.0471975511965976, 1.0, 0.0, -98.4255779827279}, {1.0471975511965976, 1.0, -1.2, -219.99098794394865},
    {1.0471975511965976, 0.0, 1.05, 106.70369353076107}, {1.0471975511965976, 0.0, 0.95, 96.62879810771713},
    {1.0471975511965976, 0.0, 0.0, 0.5000000000000001}, {1.0471975511965976, 0.0, -1.2, -120.8160546350226},
    {1.0471975511965976, -0.8, 1.05, 186.02709889087228}, {1.0471975511965976, -0.8, 0.95, 175.952202472694},
    {1.0471975511965976, -0.8, 0.0, 79.57402443054082}, {1.0471975511965976, -0.8, -1.2, -41.492672032802496},
    {-0.4, 1.0, 1.05, 7.962559957345246}, {-0.4, 1.0, 0.95, -2.112753669069657},
    {-0.4, 1.0, 0.0, -97.99616671911184}, {-0.4, 1.0, -1.2, -219.56663769564855},
    {-0.4, 0.0, 1.05, 107.12918237816838}, {-0.4, 0.0, 0.95, 97.05386642742903},
    {-0.4, 0.0, 0.0, 0.9210609940028851}, {-0.4, 0.0, -1.2, -120.40006741444341},
    {-0.4, -0.8, 1.05, 186.44590003858207}, {-0.4, -0.8, 0.95, 176.37058223449472},
    {-0.4, -0.8, 0.0, 79.98838892455555}, {-0.4, -0.8, -1.2, -41.08339132511454},
    {1.2, 1.0, 1.05, 7.390426623259417}, {1.2, 1.0, 0.95, -2.6844461002167748},
    {1.2, 1.0, 0.0, -98.56365593294755}, {1.2, 1.0, -1.2, -220.12880116439737},
    {1.2, 0.0, 1.05, 106.56582008028958}, {1.2, 0.0, 0.95, 96.49094647659935},
    {1.2, 0.0, 0.0, 0.3623577544766736}, {1.2, 0.0, -1.2, -120.9534280009843},
    {1.2, -0.8, 1.05, 185.88957680642008}, {1.2, -0.8, 0.95, 175.81470248461665},
    {1.2, -0.8, 0.0, 79.43673644851121}, {1.2, -0.8, -1.2, -41.629687822257225},
  };

  public static void testArmFeedforwardMatchesNative() {
    var ff = new ArmFeedforward(0.5, 1.0, 1.5, 2.0);
    for (double[] c : ARM) {
      T.near(c[3], ff.calculate(c[0], c[1], c[2]), 1e-9, "native calculate(" + c[0] + ", " + c[1] + ", " + c[2] + ")");
    }
    var ff2 = new ArmFeedforward(0.1, 0.3, 2.5, 0.4, 0.005);
    T.near(40.39614258960449, ff2.calculate(0.2, 3.0, 3.4), 1e-9, "native 5 ms period");
    T.near(35.689181976496144, ff2.calculate(-1.0, -2.0, -1.5), 1e-9, "native 5 ms period");
  }

  public static void testArmFeedforwardTiming() {
    var ff = new ArmFeedforward(0.5, 1.0, 1.5, 2.0);
    double sink = 0.0;
    for (int i = 0; i < 2000; i++) {
      sink += ff.calculate(0.1 * (i % 7), 1.0, 1.0 + 0.001 * (i % 11));
    }
    int calls = 2000;
    long start = System.nanoTime();
    for (int i = 0; i < calls; i++) {
      sink += ff.calculate(0.1 * (i % 7), 1.0, 1.0 + 0.001 * (i % 11));
    }
    double micros = (System.nanoTime() - start) / 1e3 / calls;
    System.out.println("     ArmFeedforward.calculate(angle, velocity, nextVelocity): " + String.format("%.1f", micros) + " us per call");
    T.check(Double.isFinite(sink), "finite");
    // A robot calls it every 20 ms loop, so half a loop is the most it may take, even on a slow machine
    T.check(micros < 10_000.0, "calculate takes " + micros + " us");
  }

  public static void testEllipseNearestMatchesNative() {
    var e = new Ellipse2d(new Pose2d(1.0, 2.0, Rotation2d.fromDegrees(30)), 2.0, 1.0);
    double[][] cases = {
      {4.0, 4.0, 2.70183581080325, 3.0462447650337885}, {-2.0, 1.0, -0.7957239463043893, 1.1842130236320174},
      {1.0, 5.0, 1.4330127012420784, 3.249999999929076}, {3.5, -1.0, 1.8235492890202465, 1.3423003797158988},
      {1.5, 2.1, 1.5, 2.1}, {0.0, -3.0, 0.012273663222616138, 0.6771339217056642},
    };
    for (double[] c : cases) {
      var n = e.nearest(new Translation2d(c[0], c[1]));
      T.near(c[2], n.getX(), TOL, "native nearest x to " + c[0] + ", " + c[1]);
      T.near(c[3], n.getY(), TOL, "native nearest y to " + c[0] + ", " + c[1]);
    }
    var n = new Ellipse2d(new Pose2d(), 1.0, 1.0).nearest(new Translation2d(3, 4));
    T.near(0.6000000000064467, n.getX(), TOL, "native circle x");
    T.near(0.8000000000085957, n.getY(), TOL, "native circle y");
  }

  public static void testTravelingSalesmanFindsTheCircle() {
    Pose2d[] poses = new Pose2d[8];
    int[] order = {0, 5, 2, 7, 4, 1, 6, 3};
    for (int i = 0; i < 8; i++) {
      double a = order[i] * Math.PI / 4;
      poses[i] = new Pose2d(Math.cos(a), Math.sin(a), new Rotation2d());
    }
    var tour = new TravelingSalesman().solve(poses, 2000);
    double length = 0.0;
    for (int i = 0; i < tour.length; i++) {
      length += tour[i].getTranslation().getDistance(tour[(i + 1) % tour.length].getTranslation());
    }
    T.near(16 * Math.sin(Math.PI / 8), length, 1e-9, "tour length");
  }
}
