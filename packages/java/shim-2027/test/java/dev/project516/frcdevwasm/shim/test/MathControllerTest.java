package dev.project516.frcdevwasm.shim.test;

import org.wpilib.math.linalg.MatBuilder;
import org.wpilib.math.linalg.Matrix;
import org.wpilib.math.util.Nat;
import org.wpilib.math.util.Num;
import org.wpilib.math.linalg.VecBuilder;
import org.wpilib.math.controller.LinearQuadraticRegulator;
import org.wpilib.math.jni.WPIMathJNI;
import org.wpilib.math.system.Discretization;
import org.wpilib.math.system.DCMotor;
import org.wpilib.math.system.Models;

/** LinearQuadraticRegulator gains (DARE, matrix exponential), from allwpilib tests. */
public final class MathControllerTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  public static void testLqrOnElevator() {
    var motors = DCMotor.getVex775Pro(2);
    var plant = Models.elevatorFromPhysicalConstants(motors, 5.0, 0.0181864, 1.0);
    var k = new LinearQuadraticRegulator<>(plant, VecBuilder.fill(0.02, 0.4), VecBuilder.fill(12.0), 0.005).getK();
    T.near(522.87006795347486, k.get(0, 0), 1e-6, "K0");
    T.near(38.239878385020411, k.get(0, 1), 1e-6, "K1");
  }

  public static void testFourMotorElevator() {
    var plant = Models.elevatorFromPhysicalConstants(DCMotor.getVex775Pro(4), 8.0, 0.75 * 25.4 / 1000.0, 14.67);
    var k = new LinearQuadraticRegulator<>(plant, VecBuilder.fill(0.1, 0.2), VecBuilder.fill(12.0), 0.020).getK();
    T.near(10.381, k.get(0, 0), 1e-2, "K0");
    T.near(0.6929, k.get(0, 1), 1e-2, "K1");
  }

  public static void testLqrOnArm() {
    var motors = DCMotor.getVex775Pro(2);
    double m = 4.0;
    double r = 0.4;
    var plant = Models.singleJointedArmFromPhysicalConstants(motors, 1d / 3d * m * r * r, 100.0);
    var k = new LinearQuadraticRegulator<>(plant, VecBuilder.fill(0.01745, 0.08726), VecBuilder.fill(12.0), 0.005).getK();
    T.near(19.339349883583761, k.get(0, 0), 1e-6, "K0");
    T.near(3.3542559517421582, k.get(0, 1), 1e-6, "K1");
  }

  private static <S extends Num, I extends Num> Matrix<I, S> getImplicitModelFollowingK(
      Matrix<S, S> a, Matrix<S, I> b, Matrix<S, S> q, Matrix<I, I> r, Matrix<S, S> aref, double dt) {
    var discAB = Discretization.discretizeAB(a, b, dt);
    var discA = discAB.getFirst();
    var discB = discAB.getSecond();
    var discAref = Discretization.discretizeA(aref, dt);

    Matrix<S, S> qimf = (discA.minus(discAref)).transpose().times(q).times(discA.minus(discAref));
    Matrix<I, I> rimf = discB.transpose().times(q).times(discB).plus(r);
    Matrix<S, I> nimf = (discA.minus(discAref)).transpose().times(q).times(discB);
    return new LinearQuadraticRegulator<>(a, b, qimf, rimf, nimf, dt).getK();
  }

  public static void testMatrixOverloadsWithSingleIntegrator() {
    var a = MatBuilder.fill(Nat.N2(), Nat.N2(), 0, 0, 0, 0);
    var b = MatBuilder.fill(Nat.N2(), Nat.N2(), 1, 0, 0, 1);
    var q = MatBuilder.fill(Nat.N2(), Nat.N2(), 1, 0, 0, 1);
    var r = MatBuilder.fill(Nat.N2(), Nat.N2(), 1, 0, 0, 1);

    var k = new LinearQuadraticRegulator<>(a, b, q, r, 0.005).getK();
    T.near(0.9975031249951226, k.get(0, 0), 1e-10, "K00");
    T.near(0.0, k.get(0, 1), 1e-10, "K01");
    T.near(0.0, k.get(1, 0), 1e-10, "K10");
    T.near(0.9975031249951226, k.get(1, 1), 1e-10, "K11");

    var n = MatBuilder.fill(Nat.N2(), Nat.N2(), 1, 0, 0, 1);
    var kimf = new LinearQuadraticRegulator<>(a, b, q, r, n, 0.005).getK();
    T.near(1.0, kimf.get(0, 0), 1e-10, "Kimf00");
    T.near(0.0, kimf.get(0, 1), 1e-10, "Kimf01");
    T.near(0.0, kimf.get(1, 0), 1e-10, "Kimf10");
    T.near(1.0, kimf.get(1, 1), 1e-10, "Kimf11");
  }

  public static void testMatrixOverloadsWithDoubleIntegrator() {
    double kv = 3.02;
    double ka = 0.642;
    var a = MatBuilder.fill(Nat.N2(), Nat.N2(), 0, 1, 0, -kv / ka);
    var b = MatBuilder.fill(Nat.N2(), Nat.N1(), 0, 1.0 / ka);
    var q = MatBuilder.fill(Nat.N2(), Nat.N2(), 1, 0, 0, 0.2);
    var r = MatBuilder.fill(Nat.N1(), Nat.N1(), 0.25);

    var k = new LinearQuadraticRegulator<>(a, b, q, r, 0.005).getK();
    T.near(1.9960017786537287, k.get(0, 0), 1e-10, "K0");
    T.near(0.5118212835109273, k.get(0, 1), 1e-10, "K1");

    var aref = MatBuilder.fill(Nat.N2(), Nat.N2(), 0, 1, 0, -kv / (ka * 5.0));
    var kimf = getImplicitModelFollowingK(a, b, q, r, aref, 0.005);
    T.near(0.0, kimf.get(0, 0), 1e-10, "Kimf0");
    T.near(-6.919050011675146e-05, kimf.get(0, 1), 1e-10, "Kimf1");
  }

  public static void testLatencyCompensate() {
    var dt = 0.02;
    var plant = Models.elevatorFromPhysicalConstants(DCMotor.getVex775Pro(4), 8.0, 0.75 * 25.4 / 1000.0, 14.67);
    var regulator = new LinearQuadraticRegulator<>(plant, VecBuilder.fill(0.1, 0.2), VecBuilder.fill(12.0), dt);
    regulator.latencyCompensate(plant, dt, 0.01);
    T.near(8.97115941, regulator.getK().get(0, 0), 1e-3, "K0");
    T.near(0.07904881, regulator.getK().get(0, 1), 1e-3, "K1");
  }

  // ---- discretization, from allwpilib DiscretizationTest

  public static void testDiscretizeA() {
    var contA = MatBuilder.fill(Nat.N2(), Nat.N2(), 0, 1, 0, 0);
    var x0 = VecBuilder.fill(1, 1);
    var x1 = Discretization.discretizeA(contA, 1.0).times(x0);
    T.near(new double[] {2.0, 1.0}, x1.getData(), 1e-12, "x1");
  }

  public static void testDiscretizeAB() {
    var contA = MatBuilder.fill(Nat.N2(), Nat.N2(), 0, 1, 0, 0);
    var contB = MatBuilder.fill(Nat.N2(), Nat.N1(), 0, 1);
    var x0 = VecBuilder.fill(1, 1);
    var u = VecBuilder.fill(1);
    var pair = Discretization.discretizeAB(contA, contB, 1.0);
    var x1 = pair.getFirst().times(x0).plus(pair.getSecond().times(u));
    T.near(new double[] {2.5, 2.0}, x1.getData(), 1e-12, "x1");
  }

  public static void testDiscretizeSlowModelAq() {
    var contA = MatBuilder.fill(Nat.N2(), Nat.N2(), 0, 1, 0, 0);
    var contQ = MatBuilder.fill(Nat.N2(), Nat.N2(), 1, 0, 0, 1);
    // analytic: integral of e^(A t) Q e^(A^T t) over [0, 1] for a double integrator
    var discQ = Discretization.discretizeAQ(contA, contQ, 1.0).getSecond();
    T.near(new double[] {1.0 + 1.0 / 3.0, 0.5, 0.5, 1.0}, discQ.getData(), 1e-10, "Qd");
  }

  public static void testDiscretizeFastModelAq() {
    var contA = MatBuilder.fill(Nat.N2(), Nat.N2(), 0, 1, 0, -1406.29);
    var contQ = MatBuilder.fill(Nat.N2(), Nat.N2(), 0.0025, 0, 0, 1);
    double dt = 0.005;
    var discQ = Discretization.discretizeAQ(contA, contQ, dt).getSecond();
    // Simpson integration of e^(A t) Q e^(A^T t) against the van Loan result
    int steps = 400;
    double[] acc = new double[4];
    for (int i = 0; i <= steps; i++) {
      double t = dt * i / steps;
      double w = (i == 0 || i == steps) ? 1 : (i % 2 == 1 ? 4 : 2);
      var e = contA.times(t).exp();
      var term = e.times(contQ).times(e.transpose());
      for (int k = 0; k < 4; k++) {
        acc[k] += w * term.getData()[k];
      }
    }
    for (int k = 0; k < 4; k++) {
      acc[k] *= dt / steps / 3;
    }
    T.near(acc, discQ.getData(), 1e-8, "Qd");
  }

  public static void testDiscretizeR() {
    var contR = MatBuilder.fill(Nat.N2(), Nat.N2(), 2.0, 0.0, 0.0, 1.0);
    var discR = Discretization.discretizeR(contR, 0.5);
    T.near(new double[] {4.0, 0.0, 0.0, 2.0}, discR.getData(), 1e-10, "Rd");
  }
}
