package dev.project516.frcdevwasm.shim.test;

import dev.project516.frcdevwasm.shim.math.jni.Stabilizability;
import org.wpilib.math.jni.WPIMathJNI;
import org.wpilib.math.numbers.N1;
import org.wpilib.math.numbers.N2;
import org.wpilib.math.linalg.MatBuilder;
import org.wpilib.math.linalg.Matrix;
import org.wpilib.math.linalg.VecBuilder;
import org.wpilib.math.util.StateSpaceUtil;
import org.wpilib.math.system.LinearSystemUtil;
import org.wpilib.math.util.Nat;
import org.wpilib.math.util.StateSpaceUtil;
import java.util.Random;

/** Ported from allwpilib LinearSystemUtilTest and StateSpaceUtilTest, plus eigenvalue edge cases. */
public final class MathLinearSystemUtilTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  public static void testIsStabilizable() {
    Matrix<N2, N2> a;
    Matrix<N2, N1> b = VecBuilder.fill(0, 1);

    // uncontrollable and unstable
    a = MatBuilder.fill(Nat.N2(), Nat.N2(), 1.2, 0, 0, 0.5);
    T.check(!LinearSystemUtil.isStabilizable(a, b), "unstable uncontrollable");

    // uncontrollable and marginally stable
    a = MatBuilder.fill(Nat.N2(), Nat.N2(), 1, 0, 0, 0.5);
    T.check(!LinearSystemUtil.isStabilizable(a, b), "marginal uncontrollable");

    // uncontrollable and stable
    a = MatBuilder.fill(Nat.N2(), Nat.N2(), 0.2, 0, 0, 0.5);
    T.check(LinearSystemUtil.isStabilizable(a, b), "stable uncontrollable");

    // controllable and unstable
    a = MatBuilder.fill(Nat.N2(), Nat.N2(), 0.2, 0, 0, 1.2);
    T.check(LinearSystemUtil.isStabilizable(a, b), "unstable controllable");
  }

  public static void testIsDetectable() {
    Matrix<N2, N2> a;
    Matrix<N1, N2> c = MatBuilder.fill(Nat.N1(), Nat.N2(), 0, 1);

    a = MatBuilder.fill(Nat.N2(), Nat.N2(), 1.2, 0, 0, 0.5);
    T.check(!LinearSystemUtil.isDetectable(a, c), "unstable unobservable");

    a = MatBuilder.fill(Nat.N2(), Nat.N2(), 1, 0, 0, 0.5);
    T.check(!LinearSystemUtil.isDetectable(a, c), "marginal unobservable");

    a = MatBuilder.fill(Nat.N2(), Nat.N2(), 0.2, 0, 0, 0.5);
    T.check(LinearSystemUtil.isDetectable(a, c), "stable unobservable");

    a = MatBuilder.fill(Nat.N2(), Nat.N2(), 0.2, 0, 0, 1.2);
    T.check(LinearSystemUtil.isDetectable(a, c), "unstable observable");
  }

  public static void testMatrixExp() {
    Matrix<N2, N2> wrapped = Matrix.eye(Nat.N2());
    var result = wrapped.exp();
    T.check(result.isEqual(MatBuilder.fill(Nat.N2(), Nat.N2(), Math.E, 0, 0, Math.E), 1E-9), "eye");

    var matrix = MatBuilder.fill(Nat.N2(), Nat.N2(), 1, 2, 3, 4);
    result = matrix.times(0.01).exp();
    T.check(
        result.isEqual(
            MatBuilder.fill(Nat.N2(), Nat.N2(), 1.01035625, 0.02050912, 0.03076368, 1.04111993), 1E-8),
        "small");
  }

  public static void testCostAndCovarianceMatrices() {
    var cost = StateSpaceUtil.costMatrix(VecBuilder.fill(1.0, 2.0, 3.0));
    T.near(1.0 / 4.0, cost.get(1, 1), 1e-3, "cost");
    var cov = StateSpaceUtil.covarianceMatrix(Nat.N3(), VecBuilder.fill(1.0, 2.0, 3.0));
    T.near(9.0, cov.get(2, 2), 1e-3, "cov");
  }

  // ---- direct calls

  private static boolean stabilizable(double[] a, int n, double[] b, int m) {
    return Stabilizability.isStabilizable(n, m, a, b);
  }

  public static void testComplexEigenvaluesOutsideTheUnitCircle() {
    // scaled rotation, eigenvalues r e^(+-i theta)
    double r = 1.3;
    double th = 0.6;
    double[] a = {r * Math.cos(th), -r * Math.sin(th), r * Math.sin(th), r * Math.cos(th)};
    T.check(!stabilizable(a, 2, new double[] {0, 0}, 1), "no input");
    T.check(stabilizable(a, 2, new double[] {1, 0}, 1), "one input drives both modes");
    T.check(stabilizable(a, 2, new double[] {0, 1}, 1), "other input");
  }

  public static void testComplexEigenvaluesInsideTheUnitCircle() {
    double r = 0.9;
    double th = 0.6;
    double[] a = {r * Math.cos(th), -r * Math.sin(th), r * Math.sin(th), r * Math.cos(th)};
    T.check(stabilizable(a, 2, new double[] {0, 0}, 1), "stable without input");
  }

  public static void testEigenvaluesOnTheUnitCircle() {
    double th = 0.3;
    double[] a = {Math.cos(th), -Math.sin(th), Math.sin(th), Math.cos(th)};
    T.check(!stabilizable(a, 2, new double[] {0, 0}, 1), "marginal and uncontrolled");
    T.check(stabilizable(a, 2, new double[] {1, 0}, 1), "marginal and controlled");
  }

  public static void testDoubleIntegrator() {
    double[] a = {1, 0.02, 0, 1};
    T.check(stabilizable(a, 2, new double[] {0, 1}, 1), "driven through velocity");
    T.check(!stabilizable(a, 2, new double[] {1, 0}, 1), "position input leaves the velocity eigenvalue");
    T.check(!stabilizable(a, 2, new double[] {0, 0}, 1), "no input");
  }

  public static void testThreeStateSystems() {
    // chain of integrators, controllable from the last state
    double[] a = {1, 1, 0, 0, 1, 1, 0, 0, 1};
    T.check(stabilizable(a, 3, new double[] {0, 0, 1}, 1), "chain");
    T.check(!stabilizable(a, 3, new double[] {1, 0, 0}, 1), "wrong end");

    // block diagonal: unstable oscillator plus stable state
    double r = 1.1;
    double th = 1.0;
    double[] blocks = {
      r * Math.cos(th), -r * Math.sin(th), 0,
      r * Math.sin(th), r * Math.cos(th), 0,
      0, 0, 0.5
    };
    T.check(!stabilizable(blocks, 3, new double[] {0, 0, 1}, 1), "third state only");
    T.check(stabilizable(blocks, 3, new double[] {1, 0, 1}, 1), "drives oscillator");
  }

  public static void testRandomControllablePairsAreStabilizable() {
    Random rng = new Random(11);
    for (int n = 2; n <= 7; n++) {
      double[] a = new double[n * n];
      for (int i = 0; i < a.length; i++) {
        a[i] = rng.nextGaussian();
      }
      double[] b = new double[n * 2];
      for (int i = 0; i < b.length; i++) {
        b[i] = rng.nextGaussian();
      }
      T.check(stabilizable(a, n, b, 2), "generic pair n=" + n);
    }
  }

  public static void testRandomUnstableWithoutInputIsNotStabilizable() {
    Random rng = new Random(12);
    for (int n = 2; n <= 7; n++) {
      double[] a = new double[n * n];
      for (int i = 0; i < a.length; i++) {
        a[i] = rng.nextGaussian();
      }
      // a random Gaussian matrix of this size has an eigenvalue outside the unit circle
      for (int i = 0; i < n; i++) {
        a[i * n + i] += 2.0;
      }
      T.check(!stabilizable(a, n, new double[n], 1), "no input n=" + n);
    }
  }

  public static void testZeroMatrix() {
    T.check(stabilizable(new double[9], 3, new double[3], 1), "zero system is stable");
  }
}
