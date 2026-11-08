package dev.project516.frcdevwasm.shim.test;

import org.wpilib.math.linalg.DARE;
import org.wpilib.math.linalg.Matrix;
import org.wpilib.math.util.Nat;
import org.wpilib.math.util.Num;
import org.wpilib.math.jni.WPIMathJNI;
import org.ejml.simple.SimpleMatrix;

/** Ported from allwpilib DARETest, plus exact exception messages. */
public final class MathDareTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  private static <R extends Num, C extends Num> void assertMatrixEqual(
      Matrix<R, C> a, Matrix<R, C> b) {
    for (int i = 0; i < a.getNumRows(); i++) {
      for (int j = 0; j < a.getNumCols(); j++) {
        T.near(a.get(i, j), b.get(i, j), 1e-4, "(" + i + "," + j + ")");
      }
    }
  }

  private static <S extends Num, I extends Num> void assertDareSolution(
      Matrix<S, S> a, Matrix<S, I> b, Matrix<S, S> q, Matrix<I, I> r, Matrix<S, S> x) {
    var y =
        (a.transpose().times(x).times(a))
            .minus(x)
            .minus(
                (a.transpose().times(x).times(b))
                    .times((b.transpose().times(x).times(b).plus(r)).inv())
                    .times(b.transpose().times(x).times(a)))
            .plus(q);
    assertMatrixEqual(new Matrix<>(new SimpleMatrix(y.getNumRows(), y.getNumCols())), y);
  }

  private static <S extends Num, I extends Num> void assertDareSolution(
      Matrix<S, S> a,
      Matrix<S, I> b,
      Matrix<S, S> q,
      Matrix<I, I> r,
      Matrix<S, I> n,
      Matrix<S, S> x) {
    var y =
        (a.transpose().times(x).times(a))
            .minus(x)
            .minus(
                (a.transpose().times(x).times(b).plus(n))
                    .times((b.transpose().times(x).times(b).plus(r)).inv())
                    .times(b.transpose().times(x).times(a).plus(n.transpose())))
            .plus(q);
    assertMatrixEqual(new Matrix<>(new SimpleMatrix(y.getNumRows(), y.getNumCols())), y);
  }

  private static String message(Runnable r) {
    return T.throwsA(IllegalArgumentException.class, r).getMessage();
  }

  public static void testNonInvertibleAABQR() {
    var a = new Matrix<>(Nat.N4(), Nat.N4(), new double[] {0.5, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0});
    var b = new Matrix<>(Nat.N4(), Nat.N1(), new double[] {0, 0, 0, 1});
    var q = new Matrix<>(Nat.N4(), Nat.N4(), new double[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    var r = new Matrix<>(Nat.N1(), Nat.N1(), new double[] {0.25});
    var x = DARE.dare(a, b, q, r);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, x);
  }

  public static void testNonInvertibleAABQRN() {
    var a = new Matrix<>(Nat.N4(), Nat.N4(), new double[] {0.5, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0});
    var b = new Matrix<>(Nat.N4(), Nat.N1(), new double[] {0, 0, 0, 1});
    var q = new Matrix<>(Nat.N4(), Nat.N4(), new double[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    var r = new Matrix<>(Nat.N1(), Nat.N1(), new double[] {0.25});
    var aref = new Matrix<>(Nat.N4(), Nat.N4(), new double[] {0.25, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0});
    q = a.minus(aref).transpose().times(q).times(a.minus(aref));
    r = b.transpose().times(q).times(b).plus(r);
    var n = a.minus(aref).transpose().times(q).times(b);
    var x = DARE.dare(a, b, q, r, n);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, n, x);
  }

  public static void testInvertibleAABQR() {
    var a = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1, 1, 0, 1});
    var b = new Matrix<>(Nat.N2(), Nat.N1(), new double[] {0, 1});
    var q = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1, 0, 0, 0});
    var r = new Matrix<>(Nat.N1(), Nat.N1(), new double[] {0.3});
    var x = DARE.dare(a, b, q, r);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, x);
  }

  public static void testInvertibleAABQRN() {
    var a = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1, 1, 0, 1});
    var b = new Matrix<>(Nat.N2(), Nat.N1(), new double[] {0, 1});
    var q = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1, 0, 0, 0});
    var r = new Matrix<>(Nat.N1(), Nat.N1(), new double[] {0.3});
    var aref = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {0.5, 1, 0, 1});
    q = a.minus(aref).transpose().times(q).times(a.minus(aref));
    r = b.transpose().times(q).times(b).plus(r);
    var n = a.minus(aref).transpose().times(q).times(b);
    var x = DARE.dare(a, b, q, r, n);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, n, x);
  }

  public static void testFirstGeneralizedEigenvalueOfSTIsStableABQR() {
    var a = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {0, 1, 0, 0});
    var b = new Matrix<>(Nat.N2(), Nat.N1(), new double[] {0, 1});
    var q = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1, 0, 0, 1});
    var r = new Matrix<>(Nat.N1(), Nat.N1(), new double[] {1});
    var x = DARE.dare(a, b, q, r);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, x);
  }

  public static void testFirstGeneralizedEigenvalueOfSTIsStableABQRN() {
    var a = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {0, 1, 0, 0});
    var b = new Matrix<>(Nat.N2(), Nat.N1(), new double[] {0, 1});
    var q = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1, 0, 0, 1});
    var r = new Matrix<>(Nat.N1(), Nat.N1(), new double[] {1});
    var aref = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {0, 0.5, 0, 0});
    q = a.minus(aref).transpose().times(q).times(a.minus(aref));
    r = b.transpose().times(q).times(b).plus(r);
    var n = a.minus(aref).transpose().times(q).times(b);
    var x = DARE.dare(a, b, q, r, n);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, n, x);
  }

  public static void testIdentitySystemABQR() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = Matrix.eye(Nat.N2());
    var r = Matrix.eye(Nat.N2());
    var x = DARE.dare(a, b, q, r);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, x);
    // closed form: X = (1 + sqrt(5)) / 2 on the diagonal
    T.near((1 + Math.sqrt(5)) / 2, x.get(0, 0), 1e-9, "golden ratio");
    T.near(0.0, x.get(0, 1), 1e-9, "off diagonal");
  }

  public static void testIdentitySystemABQRN() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = Matrix.eye(Nat.N2());
    var r = Matrix.eye(Nat.N2());
    var n = Matrix.eye(Nat.N2());
    var x = DARE.dare(a, b, q, r, n);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, n, x);
  }

  public static void testMoreInputsThanStatesABQR() {
    var a = Matrix.eye(Nat.N2());
    var b = new Matrix<>(Nat.N2(), Nat.N3(), new double[] {1, 0, 0, 0, 0.5, 0.3});
    var q = Matrix.eye(Nat.N2());
    var r = Matrix.eye(Nat.N3());
    var x = DARE.dare(a, b, q, r);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, x);
  }

  public static void testMoreInputsThanStatesABQRN() {
    var a = Matrix.eye(Nat.N2());
    var b = new Matrix<>(Nat.N2(), Nat.N3(), new double[] {1, 0, 0, 0, 0.5, 0.3});
    var q = Matrix.eye(Nat.N2());
    var r = Matrix.eye(Nat.N3());
    var n = new Matrix<>(Nat.N2(), Nat.N3(), new double[] {1, 0, 0, 0, 1, 0});
    var x = DARE.dare(a, b, q, r, n);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, n, x);
  }

  public static void testNoPrecondVariantsMatchChecked() {
    var a = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1, 1, 0, 1});
    var b = new Matrix<>(Nat.N2(), Nat.N1(), new double[] {0, 1});
    var q = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1, 0, 0, 0});
    var r = new Matrix<>(Nat.N1(), Nat.N1(), new double[] {0.3});
    var n = new Matrix<>(Nat.N2(), Nat.N1(), new double[] {0.01, 0.0});
    T.near(DARE.dare(a, b, q, r).getData(), DARE.dareNoPrecond(a, b, q, r).getData(), 0.0, "ABQR");
    T.near(DARE.dare(a, b, q, r, n).getData(), DARE.dareNoPrecond(a, b, q, r, n).getData(), 0.0, "ABQRN");
  }

  public static void testLargerSystemSolvesRiccati() {
    int n = 6;
    int m = 2;
    double[] ad = new double[n * n];
    double[] bd = new double[n * m];
    double[] qd = new double[n * n];
    double[] rd = new double[m * m];
    java.util.Random rng = new java.util.Random(4);
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        ad[i * n + j] = rng.nextGaussian() * 0.4;
      }
      qd[i * n + i] = 1.0 + i;
    }
    for (int i = 0; i < n * m; i++) {
      bd[i] = rng.nextGaussian();
    }
    rd[0] = 1.0;
    rd[3] = 2.0;
    var a = new Matrix<>(Nat.N6(), Nat.N6(), ad);
    var b = new Matrix<>(Nat.N6(), Nat.N2(), bd);
    var q = new Matrix<>(Nat.N6(), Nat.N6(), qd);
    var r = new Matrix<>(Nat.N2(), Nat.N2(), rd);
    var x = DARE.dare(a, b, q, r);
    assertMatrixEqual(x, x.transpose());
    assertDareSolution(a, b, q, r, x);
  }

  public static void testQNotSymmetricABQR() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1.0, 1.0, 0.0, 1.0});
    var r = Matrix.eye(Nat.N2());
    T.eq("Q was not symmetric.\n\nQ =\n  1  1\n  0  1\n", message(() -> DARE.dare(a, b, q, r)));
  }

  public static void testQNotPositiveSemidefiniteABQR() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {-1.0, 0.0, 0.0, -1.0});
    var r = Matrix.eye(Nat.N2());
    T.eq(
        "Q was not positive semidefinite.\n\nQ =\n  -1  0\n  0  -1\n",
        message(() -> DARE.dare(a, b, q, r)));
  }

  public static void testQNotSymmetricABQRN() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1.0, 1.0, 0.0, 1.0});
    var r = Matrix.eye(Nat.N2());
    var n = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {2.0, 0.0, 0.0, 2.0});
    // the message prints the original Q, not Q - N R^-1 N^T
    T.eq("Q was not symmetric.\n\nQ =\n  1  1\n  0  1\n", message(() -> DARE.dare(a, b, q, r, n)));
  }

  public static void testQNotPositiveSemidefiniteABQRN() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = Matrix.eye(Nat.N2());
    var r = Matrix.eye(Nat.N2());
    var n = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {2.0, 0.0, 0.0, 2.0});
    T.eq(
        "Q was not positive semidefinite.\n\nQ =\n  1  0\n  0  1\n",
        message(() -> DARE.dare(a, b, q, r, n)));
  }

  public static void testRNotSymmetricABQR() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = Matrix.eye(Nat.N2());
    var r = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1.0, 1.0, 0.0, 1.0});
    T.eq("R was not symmetric.\n\nR =\n  1  1\n  0  1\n", message(() -> DARE.dare(a, b, q, r)));
  }

  public static void testRNotPositiveDefiniteABQR() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = Matrix.eye(Nat.N2());
    var r1 = new Matrix<>(Nat.N2(), Nat.N2());
    T.eq(
        "R was not positive definite.\n\nR =\n  0  0\n  0  0\n",
        message(() -> DARE.dare(a, b, q, r1)));
    var r2 = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {-1.0, 0.0, 0.0, -1.0});
    T.eq(
        "R was not positive definite.\n\nR =\n  -1  0\n  0  -1\n",
        message(() -> DARE.dare(a, b, q, r2)));
  }

  public static void testRNotSymmetricABQRN() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = Matrix.eye(Nat.N2());
    var n = Matrix.eye(Nat.N2());
    var r = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1.0, 1.0, 0.0, 1.0});
    T.eq("R was not symmetric.\n\nR =\n  1  1\n  0  1\n", message(() -> DARE.dare(a, b, q, r, n)));
  }

  public static void testRNotPositiveDefiniteABQRN() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = Matrix.eye(Nat.N2());
    var n = Matrix.eye(Nat.N2());
    var r1 = new Matrix<>(Nat.N2(), Nat.N2());
    T.check(message(() -> DARE.dare(a, b, q, r1, n)).startsWith("R was not positive definite."), "r1");
    var r2 = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {-1.0, 0.0, 0.0, -1.0});
    T.check(message(() -> DARE.dare(a, b, q, r2, n)).startsWith("R was not positive definite."), "r2");
  }

  public static void testABNotStabilizableABQR() {
    var a = Matrix.eye(Nat.N2());
    var b = new Matrix<>(Nat.N2(), Nat.N2());
    var q = Matrix.eye(Nat.N2());
    var r = Matrix.eye(Nat.N2());
    T.eq(
        "(A, B) pair was not stabilizable.\n\nA =\n  1  0\n  0  1\nB =\n  0  0\n  0  0\n",
        message(() -> DARE.dare(a, b, q, r)));
  }

  public static void testABNotStabilizableABQRN() {
    var a = Matrix.eye(Nat.N2());
    var b = new Matrix<>(Nat.N2(), Nat.N2());
    var q = Matrix.eye(Nat.N2());
    var r = Matrix.eye(Nat.N2());
    var n = Matrix.eye(Nat.N2());
    // the message prints A - B R^-1 N^T, which is A here because B is zero
    T.eq(
        "(A, B) pair was not stabilizable.\n\nA =\n  1  0\n  0  1\nB =\n  0  0\n  0  0\n",
        message(() -> DARE.dare(a, b, q, r, n)));
  }

  public static void testACNotDetectableABQR() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = new Matrix<>(Nat.N2(), Nat.N2());
    var r = Matrix.eye(Nat.N2());
    T.eq(
        "(A, C) pair where Q = CᵀC was not detectable.\n\nA =\n  1  0\n  0  1\nQ =\n  0  0\n  0  0\n",
        message(() -> DARE.dare(a, b, q, r)));
  }

  public static void testACNotDetectableABQRN() {
    var a = Matrix.eye(Nat.N2());
    var b = Matrix.eye(Nat.N2());
    var q = new Matrix<>(Nat.N2(), Nat.N2());
    var r = Matrix.eye(Nat.N2());
    var n = new Matrix<>(Nat.N2(), Nat.N2());
    T.check(message(() -> DARE.dare(a, b, q, r, n)).startsWith("(A, C) pair where Q"), "message");
  }

  public static void testQDecomposition() {
    var a = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1.0, 0.0, 0.0, 0.0});
    var b = Matrix.eye(Nat.N2());
    var r = Matrix.eye(Nat.N2());

    // (A, C1) is detectable
    var c1 = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {0.0, 0.0, 1.0, 0.0});
    var q1 = c1.transpose().times(c1);
    DARE.dare(a, b, q1, r);

    // (A, C2) is not detectable
    var c2 = c1.transpose();
    var q2 = c2.transpose().times(c2);
    message(() -> DARE.dare(a, b, q2, r));
  }

  public static void testNoPrecondSolverTerminatesOnAnUnstabilizablePair() {
    // the C++ solver can hang here; the shim must come back
    var a = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {2, 0, 0, 2});
    var b = new Matrix<>(Nat.N2(), Nat.N2());
    var q = Matrix.eye(Nat.N2());
    var r = Matrix.eye(Nat.N2());
    try {
      DARE.dareNoPrecond(a, b, q, r);
    } catch (IllegalArgumentException e) {
      T.eq("DARE solver did not converge", e.getMessage());
    }
  }

  public static void testQNeedsPivotedLdlt() {
    // Q is positive semidefinite but its first diagonal entry is the smallest
    var a = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {0.9, 0.0, 0.0, 0.8});
    var b = Matrix.eye(Nat.N2());
    var r = Matrix.eye(Nat.N2());
    var q = new Matrix<>(Nat.N2(), Nat.N2(), new double[] {1.0, 2.0, 2.0, 5.0});
    var x = DARE.dare(a, b, q, r);
    assertDareSolution(a, b, q, r, x);
  }
}
