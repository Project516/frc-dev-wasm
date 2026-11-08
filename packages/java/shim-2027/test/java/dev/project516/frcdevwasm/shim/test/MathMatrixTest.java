package dev.project516.frcdevwasm.shim.test;

import org.wpilib.math.linalg.MatBuilder;
import org.wpilib.math.linalg.Matrix;
import org.wpilib.math.util.Nat;
import org.wpilib.math.linalg.VecBuilder;
import org.wpilib.math.jni.EigenJNI;
import org.wpilib.math.jni.WPIMathJNI;
import java.util.Random;

/** Matrix.exp, Matrix.pow, rank updates and full pivot QR solves through EigenJNI. */
public final class MathMatrixTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  // ---- plain row-major helpers

  private static double[] mul(double[] a, double[] b, int n) {
    double[] c = new double[n * n];
    for (int i = 0; i < n; i++) {
      for (int k = 0; k < n; k++) {
        for (int j = 0; j < n; j++) {
          c[i * n + j] += a[i * n + k] * b[k * n + j];
        }
      }
    }
    return c;
  }

  private static double[] identity(int n) {
    double[] a = new double[n * n];
    for (int i = 0; i < n; i++) {
      a[i * n + i] = 1;
    }
    return a;
  }

  private static double maxAbs(double[] a) {
    double m = 0;
    for (double v : a) {
      m = Math.max(m, Math.abs(v));
    }
    return m;
  }

  private static double[] sub(double[] a, double[] b) {
    double[] c = new double[a.length];
    for (int i = 0; i < c.length; i++) {
      c[i] = a[i] - b[i];
    }
    return c;
  }

  private static double[] random(Random rng, int n, double scale) {
    double[] a = new double[n * n];
    for (int i = 0; i < a.length; i++) {
      a[i] = rng.nextGaussian() * scale;
    }
    return a;
  }

  private static double[] inverse(double[] a, int n) {
    double[] m = a.clone();
    double[] inv = identity(n);
    for (int c = 0; c < n; c++) {
      int p = c;
      for (int r = c + 1; r < n; r++) {
        if (Math.abs(m[r * n + c]) > Math.abs(m[p * n + c])) {
          p = r;
        }
      }
      for (int j = 0; j < n; j++) {
        double t = m[c * n + j];
        m[c * n + j] = m[p * n + j];
        m[p * n + j] = t;
        t = inv[c * n + j];
        inv[c * n + j] = inv[p * n + j];
        inv[p * n + j] = t;
      }
      double d = m[c * n + c];
      for (int j = 0; j < n; j++) {
        m[c * n + j] /= d;
        inv[c * n + j] /= d;
      }
      for (int r = 0; r < n; r++) {
        if (r != c) {
          double f = m[r * n + c];
          for (int j = 0; j < n; j++) {
            m[r * n + j] -= f * m[c * n + j];
            inv[r * n + j] -= f * inv[c * n + j];
          }
        }
      }
    }
    return inv;
  }

  /** Reference exp: scaled Taylor series with many terms. */
  private static double[] referenceExp(double[] a, int n) {
    double norm = maxAbs(a) * n;
    int s = 0;
    while (norm / Math.pow(2, s) > 0.25) {
      s++;
    }
    double[] x = new double[a.length];
    for (int i = 0; i < x.length; i++) {
      x[i] = a[i] / Math.pow(2, s);
    }
    double[] sum = identity(n);
    double[] term = identity(n);
    for (int k = 1; k <= 30; k++) {
      term = mul(term, x, n);
      for (int i = 0; i < term.length; i++) {
        term[i] /= k;
        sum[i] += term[i];
      }
    }
    for (int i = 0; i < s; i++) {
      sum = mul(sum, sum, n);
    }
    return sum;
  }

  private static void nearRel(double[] expected, double[] actual, double rel, String msg) {
    double tol = rel * Math.max(1.0, maxAbs(expected));
    T.near(expected, actual, tol, msg);
  }

  private static double[] exp(double[] a, int n) {
    double[] out = new double[n * n];
    EigenJNI.exp(a, n, out);
    return out;
  }

  private static double[] pow(double[] a, int n, double p) {
    double[] out = new double[n * n];
    EigenJNI.pow(a, n, p, out);
    return out;
  }

  // ---- exp

  public static void testMatrixExponential() {
    var matrix = Matrix.eye(Nat.N2());
    var result = matrix.exp();
    T.check(result.isEqual(MatBuilder.fill(Nat.N2(), Nat.N2(), Math.E, 0, 0, Math.E), 1E-9), "eye");

    var m = MatBuilder.fill(Nat.N2(), Nat.N2(), 1, 2, 3, 4);
    result = m.times(0.01).exp();
    T.check(
        result.isEqual(
            MatBuilder.fill(Nat.N2(), Nat.N2(), 1.01035625, 0.02050912, 0.03076368, 1.04111993), 1E-8),
        "small");
  }

  public static void testExpOfZeroAndOneByOne() {
    T.near(new double[] {1, 0, 0, 1}, exp(new double[4], 2), 0.0, "zero");
    T.near(Math.exp(1.7), exp(new double[] {1.7}, 1)[0], 1e-14, "1x1");
    T.near(Math.exp(-40.0), exp(new double[] {-40.0}, 1)[0], 1e-25, "1x1 negative");
  }

  public static void testExpOfRotationGenerator() {
    // exp([[0, -t], [t, 0]]) is a rotation by t; the angles cover every Pade degree and scaling
    double[] angles = {1e-4, 0.001, 0.01, 0.05, 0.1, 0.3, 0.5, 0.9, 1.5, 2.0, 3.0, 5.0, 10.0, -7.0, 40.0, 150.0};
    for (double t : angles) {
      double[] r = exp(new double[] {0, -t, t, 0}, 2);
      T.near(new double[] {Math.cos(t), -Math.sin(t), Math.sin(t), Math.cos(t)}, r, 1e-11 * Math.max(1, Math.abs(t)), "t=" + t);
    }
  }

  public static void testExpOfDiagonalAndJordan() {
    double[] d = exp(new double[] {2, 0, 0, 0, -1, 0, 0, 0, 0.5}, 3);
    T.near(new double[] {Math.exp(2), 0, 0, 0, Math.exp(-1), 0, 0, 0, Math.exp(0.5)}, d, 1e-12, "diag");
    // exp([[a, 1], [0, a]]) = e^a [[1, 1], [0, 1]]
    double[] j = exp(new double[] {0.8, 1, 0, 0.8}, 2);
    double e = Math.exp(0.8);
    T.near(new double[] {e, e, 0, e}, j, 1e-13, "jordan");
  }

  public static void testExpMatchesReferenceSeries() {
    Random rng = new Random(1234);
    double[] scales = {1e-3, 0.01, 0.05, 0.2, 0.5, 1.0, 2.0, 5.0};
    for (int n = 1; n <= 8; n++) {
      for (double s : scales) {
        double[] a = random(rng, n, s);
        nearRel(referenceExp(a, n), exp(a, n), 1e-11, "n=" + n + " scale=" + s);
      }
    }
  }

  public static void testExpInverseAndSum() {
    Random rng = new Random(77);
    for (int n = 2; n <= 6; n++) {
      double[] a = random(rng, n, 0.6);
      double[] neg = new double[a.length];
      for (int i = 0; i < a.length; i++) {
        neg[i] = -a[i];
      }
      nearRel(identity(n), mul(exp(a, n), exp(neg, n), n), 1e-11, "exp(A) exp(-A), n=" + n);
      // exp(A) = exp(A / 2)^2
      double[] half = new double[a.length];
      for (int i = 0; i < a.length; i++) {
        half[i] = a[i] / 2;
      }
      double[] eh = exp(half, n);
      nearRel(exp(a, n), mul(eh, eh, n), 1e-11, "exp(A/2)^2, n=" + n);
    }
  }

  // ---- pow

  public static void testPowIntegerExponents() {
    Random rng = new Random(5);
    for (int n = 1; n <= 5; n++) {
      double[] a = random(rng, n, 0.7);
      for (int i = 0; i < n; i++) {
        a[i * n + i] += 1.5;
      }
      double[] expected = identity(n);
      T.near(expected, pow(a, n, 0), 1e-15, "A^0");
      T.near(a, pow(a, n, 1), 1e-13, "A^1");
      for (int k = 1; k <= 7; k++) {
        expected = mul(expected, a, n);
        nearRel(expected, pow(a, n, k), 1e-10, "A^" + k + " n=" + n);
      }
      double[] inv = inverse(a, n);
      nearRel(inv, pow(a, n, -1), 1e-10, "A^-1 n=" + n);
      double[] inv3 = mul(mul(inv, inv, n), inv, n);
      nearRel(inv3, pow(a, n, -3), 1e-9, "A^-3 n=" + n);
    }
  }

  public static void testPowOneByOne() {
    T.near(Math.pow(3.0, 2.5), pow(new double[] {3.0}, 1, 2.5)[0], 1e-14, "positive");
    T.near(-8.0, pow(new double[] {-2.0}, 1, 3.0)[0], 1e-14, "negative base, integer power");
    T.check(Double.isNaN(pow(new double[] {-2.0}, 1, 0.5)[0]), "negative base, fractional power");
  }

  /** V diag(d^p) V^-1 for a matrix built from a known spectrum. */
  private static void checkDiagonalizable(Random rng, int n, double[] eig, double p, double tol) {
    double[] v = random(rng, n, 1.0);
    for (int i = 0; i < n; i++) {
      v[i * n + i] += 2.0;
    }
    double[] vinv = inverse(v, n);
    double[] d = new double[n * n];
    double[] dp = new double[n * n];
    for (int i = 0; i < n; i++) {
      d[i * n + i] = eig[i];
      dp[i * n + i] = Math.pow(eig[i], p);
    }
    double[] a = mul(mul(v, d, n), vinv, n);
    double[] expected = mul(mul(v, dp, n), vinv, n);
    nearRel(expected, pow(a, n, p), tol, "n=" + n + " p=" + p);
  }

  public static void testPowFractionalWithRealEigenvalues() {
    Random rng = new Random(2024);
    double[] ps = {0.5, 0.25, 1.0 / 3.0, 0.75, 1.5, 2.5, 0.1, 0.9, -0.5, -1.5, -2.25, 3.7};
    for (int n = 2; n <= 6; n++) {
      double[] eig = new double[n];
      for (int i = 0; i < n; i++) {
        eig[i] = 0.4 + 2.5 * rng.nextDouble();
      }
      for (double p : ps) {
        checkDiagonalizable(rng, n, eig, p, 5e-8);
      }
    }
  }

  public static void testPowClusteredAndWideSpectra() {
    Random rng = new Random(31);
    checkDiagonalizable(rng, 3, new double[] {1.0, 1.0001, 1.0002}, 0.5, 1e-6);
    checkDiagonalizable(rng, 3, new double[] {1e-3, 1.0, 1e3}, 0.5, 1e-6);
    checkDiagonalizable(rng, 4, new double[] {0.9, 1.1, 20.0, 0.05}, 1.5, 1e-6);
  }

  public static void testPowOfRotationHasComplexEigenvalues() {
    // R(theta)^p = R(p theta) for theta in (-pi, pi)
    double[] thetas = {0.7, -1.2, 2.5};
    double[] ps = {0.5, 1.0 / 3.0, 2.5, -0.75, 1.3, 4.0, -2.0};
    for (double th : thetas) {
      double[] r = {Math.cos(th), -Math.sin(th), Math.sin(th), Math.cos(th)};
      for (double p : ps) {
        double q = p * th;
        T.near(new double[] {Math.cos(q), -Math.sin(q), Math.sin(q), Math.cos(q)}, pow(r, 2, p), 1e-10, "th=" + th + " p=" + p);
      }
    }
  }

  public static void testPowOfScaledRotation() {
    double r = 1.7;
    double th = 0.9;
    double[] a = {r * Math.cos(th), -r * Math.sin(th), r * Math.sin(th), r * Math.cos(th)};
    for (double p : new double[] {0.5, 1.5, -0.5, 2.2}) {
      double s = Math.pow(r, p);
      double q = p * th;
      T.near(new double[] {s * Math.cos(q), -s * Math.sin(q), s * Math.sin(q), s * Math.cos(q)}, pow(a, 2, p), 1e-10, "p=" + p);
    }
  }

  public static void testPowOfThreeDimensionalRotation() {
    // rotation about the unit axis k by angle th
    double[] k = {1 / Math.sqrt(3), 1 / Math.sqrt(3), 1 / Math.sqrt(3)};
    double th = 1.1;
    for (double p : new double[] {0.5, 1.0 / 3.0, 2.5, -0.5, 1.7}) {
      T.near(axisAngle(k, p * th), pow(axisAngle(k, th), 3, p), 1e-10, "p=" + p);
    }
  }

  private static double[] axisAngle(double[] k, double th) {
    double c = Math.cos(th);
    double s = Math.sin(th);
    double v = 1 - c;
    return new double[] {
      c + k[0] * k[0] * v, k[0] * k[1] * v - k[2] * s, k[0] * k[2] * v + k[1] * s,
      k[1] * k[0] * v + k[2] * s, c + k[1] * k[1] * v, k[1] * k[2] * v - k[0] * s,
      k[2] * k[0] * v - k[1] * s, k[2] * k[1] * v + k[0] * s, c + k[2] * k[2] * v
    };
  }

  public static void testPowOfJordanBlocks() {
    // [[a, 1], [0, a]]^p = [[a^p, p a^(p-1)], [0, a^p]]
    double a = 2.0;
    for (double p : new double[] {0.5, 1.5, -0.5, 3.3}) {
      T.near(
          new double[] {Math.pow(a, p), p * Math.pow(a, p - 1), 0, Math.pow(a, p)},
          pow(new double[] {a, 1, 0, a}, 2, p),
          1e-12,
          "2x2 p=" + p);
    }
    // 3x3: a^p I + p a^(p-1) N + p(p-1)/2 a^(p-2) N^2
    for (double p : new double[] {0.5, 1.5, -0.5}) {
      double c0 = Math.pow(a, p);
      double c1 = p * Math.pow(a, p - 1);
      double c2 = p * (p - 1) / 2 * Math.pow(a, p - 2);
      T.near(
          new double[] {c0, c1, c2, 0, c0, c1, 0, 0, c0},
          pow(new double[] {a, 1, 0, 0, a, 1, 0, 0, a}, 3, p),
          1e-10,
          "3x3 p=" + p);
    }
  }

  public static void testPowTriangularMatrixWithDistinctDiagonal() {
    double[] a = {4, 1, 2, 0, 9, 3, 0, 0, 16};
    double[] half = pow(a, 3, 0.5);
    nearRel(a, mul(half, half, 3), 1e-12, "sqrt squared");
    double[] quarter = pow(a, 3, 0.25);
    nearRel(a, mul(mul(quarter, quarter, 3), mul(quarter, quarter, 3), 3), 1e-11, "fourth root");
    T.near(2.0, half[0], 1e-12, "diag 0");
    T.near(3.0, half[4], 1e-12, "diag 1");
    T.near(4.0, half[8], 1e-12, "diag 2");
  }

  public static void testPowWithSimpleZeroEigenvalue() {
    // exactly singular and triangular, so the zero eigenvalue is exact
    Random rng = new Random(9);
    int n = 4;
    double[] a = new double[n * n];
    double[] diag = {2.0, 0.0, 3.0, 0.5};
    for (int i = 0; i < n; i++) {
      a[i * n + i] = diag[i];
      for (int j = i + 1; j < n; j++) {
        a[i * n + j] = rng.nextGaussian();
      }
    }
    double[] half = pow(a, n, 0.5);
    nearRel(a, mul(half, half, n), 1e-9, "sqrt squared");
    nearRel(mul(a, half, n), pow(a, n, 1.5), 1e-9, "A^1.5 = A A^0.5");
    double[] quarter = pow(a, n, 0.25);
    double[] q2 = mul(quarter, quarter, n);
    nearRel(half, q2, 1e-9, "A^0.25 squared");
  }

  public static void testPowComposition() {
    Random rng = new Random(88);
    int n = 4;
    double[] a = random(rng, n, 0.3);
    for (int i = 0; i < n; i++) {
      a[i * n + i] += 2.0;
    }
    double[] p1 = pow(a, n, 0.3);
    double[] p2 = pow(a, n, 0.7);
    nearRel(pow(a, n, 1.0), mul(p1, p2, n), 1e-9, "A^0.3 A^0.7");
    nearRel(mul(pow(a, n, 1.5), pow(a, n, -1.5), n), identity(n), 1e-9, "A^1.5 A^-1.5");
    // exp(p log A) relationship: pow(exp(X), p) = exp(p X) for small X
    double[] x = random(rng, n, 0.15);
    double[] px = new double[x.length];
    for (int i = 0; i < x.length; i++) {
      px[i] = 0.37 * x[i];
    }
    nearRel(exp(px, n), pow(exp(x, n), n, 0.37), 1e-9, "exp(X)^p = exp(pX)");
  }

  public static void testMatrixPowApi() {
    var m = MatBuilder.fill(Nat.N2(), Nat.N2(), 4.0, 0.0, 0.0, 9.0);
    var r = m.pow(0.5);
    T.near(new double[] {2, 0, 0, 3}, r.getData(), 1e-12, "sqrt of diag");
  }

  public static void testNonFiniteInputsTerminate() {
    double[] nan = {Double.NaN, 1, 0, 2};
    for (double p : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      for (double v : pow(new double[] {2, 1, 0, 3}, 2, p)) {
        T.check(Double.isNaN(v), "non-finite exponent " + p);
      }
    }
    pow(nan, 2, 0.5);
    pow(new double[] {Double.NaN, 1, 0, 0, 2, 1, 1, 0, 3}, 3, 0.5);
    pow(new double[] {Double.NaN, 1, 0, 0, 2, 1, 1, 0, 3}, 3, 3);
    exp(nan, 2);
    exp(new double[] {1e308, 1e308, 1e308, 1e308}, 2);
  }

  // ---- rank updates

  private static double[] cholesky(double[] s, int n) {
    double[] l = new double[n * n];
    for (int j = 0; j < n; j++) {
      double d = s[j * n + j];
      for (int k = 0; k < j; k++) {
        d -= l[j * n + k] * l[j * n + k];
      }
      l[j * n + j] = Math.sqrt(d);
      for (int i = j + 1; i < n; i++) {
        double v = s[i * n + j];
        for (int k = 0; k < j; k++) {
          v -= l[i * n + k] * l[j * n + k];
        }
        l[i * n + j] = v / l[j * n + j];
      }
    }
    return l;
  }

  private static double[] transpose(double[] a, int n) {
    double[] t = new double[a.length];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        t[j * n + i] = a[i * n + j];
      }
    }
    return t;
  }

  private static double[] spd(Random rng, int n) {
    double[] m = random(rng, n, 1.0);
    double[] s = mul(m, transpose(m, n), n);
    for (int i = 0; i < n; i++) {
      s[i * n + i] += n;
    }
    return s;
  }

  private static void checkRankUpdate(int n, double sigma, boolean lower, long seed) {
    Random rng = new Random(seed);
    double[] s = spd(rng, n);
    double[] v = new double[n];
    for (int i = 0; i < n; i++) {
      v[i] = rng.nextGaussian();
    }
    double[] updated = s.clone();
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        updated[i * n + j] += sigma * v[i] * v[j];
      }
    }
    double[] l = cholesky(s, n);
    double[] expected = cholesky(updated, n);
    double[] m = lower ? l : transpose(l, n);
    EigenJNI.rankUpdate(m, n, v, sigma, lower);
    if (!lower) {
      // the upper factor is the transpose of the lower one
      expected = transpose(expected, n);
    }
    // only the stored triangle is meaningful
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        boolean stored = lower ? j <= i : j >= i;
        if (stored) {
          T.near(expected[i * n + j], m[i * n + j], 1e-10, "n=" + n + " sigma=" + sigma + " lower=" + lower + " (" + i + "," + j + ")");
        }
      }
    }
  }

  public static void testRankUpdateLowerUpdate() {
    for (int n = 1; n <= 7; n++) {
      checkRankUpdate(n, 1.0, true, 100 + n);
      checkRankUpdate(n, 0.37, true, 200 + n);
    }
  }

  public static void testRankUpdateLowerDowndate() {
    for (int n = 1; n <= 7; n++) {
      checkRankUpdate(n, -0.25, true, 300 + n);
      checkRankUpdate(n, -1.0, true, 400 + n);
    }
  }

  public static void testRankUpdateUpper() {
    for (int n = 1; n <= 7; n++) {
      checkRankUpdate(n, 1.0, false, 500 + n);
      checkRankUpdate(n, -0.5, false, 600 + n);
    }
  }

  public static void testRankUpdateThroughMatrixApi() {
    var s = MatBuilder.fill(Nat.N2(), Nat.N2(), 4.0, 2.0, 2.0, 3.0);
    double[] l = cholesky(s.getData(), 2);
    var lm = new Matrix<>(Nat.N2(), Nat.N2(), l);
    var v = VecBuilder.fill(1.0, 0.5);
    lm.rankUpdate(v, 2.0, true);
    var expected = MatBuilder.fill(Nat.N2(), Nat.N2(), 6.0, 3.0, 3.0, 3.5);
    double[] ll = lm.getData();
    double[] llt = {ll[0] * ll[0], ll[0] * ll[2], ll[2] * ll[0], ll[2] * ll[2] + ll[3] * ll[3]};
    T.near(expected.getData(), llt, 1e-12, "L L^T");
  }
}
