package dev.project516.frcdevwasm.shim.test;

import org.wpilib.math.linalg.VecBuilder;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Quaternion;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.geometry.Translation3d;
import org.wpilib.math.geometry.Twist3d;
import org.wpilib.math.jni.Transform3dJNI;
import org.wpilib.math.jni.Twist3dJNI;
import org.wpilib.math.jni.WPIMathJNI;
import org.wpilib.math.util.Units;
import java.util.List;
import java.util.Random;

/** Twist3d.exp and Transform3d.log, from allwpilib Twist3dTest and Pose3dTest. */
public final class MathTwist3dTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  private static void assertPose(Pose3d expected, Pose3d actual) {
    T.check(expected.equals(actual), "expected " + expected + " but was " + actual);
  }

  private static void assertTwist(Twist3d expected, Twist3d actual) {
    T.check(expected.equals(actual), "expected " + expected + " but was " + actual);
  }

  public static void testStraightX() {
    assertPose(new Pose3d(5.0, 0.0, 0.0, Rotation3d.ZERO), Pose3d.ZERO.plus(new Twist3d(5.0, 0.0, 0.0, 0.0, 0.0, 0.0).exp()));
  }

  public static void testStraightY() {
    assertPose(new Pose3d(0.0, 5.0, 0.0, Rotation3d.ZERO), Pose3d.ZERO.plus(new Twist3d(0.0, 5.0, 0.0, 0.0, 0.0, 0.0).exp()));
  }

  public static void testStraightZ() {
    assertPose(new Pose3d(0.0, 0.0, 5.0, Rotation3d.ZERO), Pose3d.ZERO.plus(new Twist3d(0.0, 0.0, 5.0, 0.0, 0.0, 0.0).exp()));
  }

  public static void testQuarterCircle() {
    var zAxis = VecBuilder.fill(0.0, 0.0, 1.0);
    var quarterCircle = new Twist3d(5.0 / 2.0 * Math.PI, 0.0, 0.0, 0.0, 0.0, Math.PI / 2.0);
    var expected = new Pose3d(5.0, 5.0, 0.0, new Rotation3d(zAxis, Units.degreesToRadians(90.0)));
    assertPose(expected, Pose3d.ZERO.plus(quarterCircle.exp()));
  }

  public static void testDiagonalNoDtheta() {
    assertPose(new Pose3d(2.0, 2.0, 0.0, Rotation3d.ZERO), Pose3d.ZERO.plus(new Twist3d(2.0, 2.0, 0.0, 0.0, 0.0, 0.0).exp()));
  }

  public static void testPose3dLogX() {
    final var end = new Pose3d(0.0, 5.0, 5.0, new Rotation3d(Units.degreesToRadians(90.0), 0.0, 0.0));
    final var twist = end.minus(Pose3d.ZERO).log();
    assertTwist(new Twist3d(0.0, 5.0 / 2.0 * Math.PI, 0.0, Units.degreesToRadians(90.0), 0.0, 0.0), twist);
    assertPose(end, Pose3d.ZERO.plus(twist.exp()));
  }

  public static void testPose3dLogY() {
    final var end = new Pose3d(5.0, 0.0, 5.0, new Rotation3d(0.0, Units.degreesToRadians(90.0), 0.0));
    final var twist = end.minus(Pose3d.ZERO).log();
    assertTwist(new Twist3d(0.0, 0.0, 5.0 / 2.0 * Math.PI, 0.0, Math.PI / 2.0, 0.0), twist);
    assertPose(end, Pose3d.ZERO.plus(twist.exp()));
  }

  public static void testPose3dLogZ() {
    final var end = new Pose3d(5.0, 5.0, 0.0, new Rotation3d(0.0, 0.0, Units.degreesToRadians(90.0)));
    final var twist = end.minus(Pose3d.ZERO).log();
    assertTwist(new Twist3d(5.0 / 2.0 * Math.PI, 0.0, 0.0, 0.0, 0.0, Math.PI / 2.0), twist);
    assertPose(end, Pose3d.ZERO.plus(twist.exp()));
  }

  public static void testComplexTwists() {
    var initialPoses =
        List.of(
            new Pose3d(new Translation3d(0.698303, -0.959096, 0.271076), new Rotation3d(new Quaternion(0.86403, -0.076866, 0.147234, 0.475254))),
            new Pose3d(new Translation3d(0.634892, -0.765209, 0.117543), new Rotation3d(new Quaternion(0.84987, -0.070829, 0.162097, 0.496415))),
            new Pose3d(new Translation3d(0.584827, -0.590303, -0.02557), new Rotation3d(new Quaternion(0.832743, -0.041991, 0.202188, 0.513708))),
            new Pose3d(new Translation3d(0.505038, -0.451479, -0.112835), new Rotation3d(new Quaternion(0.816515, -0.002673, 0.226182, 0.531166))),
            new Pose3d(new Translation3d(0.428178, -0.329692, -0.189707), new Rotation3d(new Quaternion(0.807886, 0.029298, 0.257788, 0.529157))));
    var finalPoses =
        List.of(
            new Pose3d(new Translation3d(-0.230448, -0.511957, 0.198406), new Rotation3d(new Quaternion(0.753984, 0.347016, 0.409105, 0.379106))),
            new Pose3d(new Translation3d(-0.088932, -0.343253, 0.095018), new Rotation3d(new Quaternion(0.638738, 0.413016, 0.536281, 0.365833))),
            new Pose3d(new Translation3d(-0.107908, -0.317552, 0.133946), new Rotation3d(new Quaternion(0.653444, 0.417069, 0.465505, 0.427046))),
            new Pose3d(new Translation3d(-0.123383, -0.156411, -0.047435), new Rotation3d(new Quaternion(0.652983, 0.40644, 0.431566, 0.47135))),
            new Pose3d(new Translation3d(-0.084654, -0.019305, -0.030022), new Rotation3d(new Quaternion(0.620243, 0.429104, 0.479384, 0.44873))));
    final double eps = 1E-5;
    for (int i = 0; i < initialPoses.size(); i++) {
      var start = initialPoses.get(i);
      var end = finalPoses.get(i);
      var twist = log(start, end);
      var startExp = exp(start, twist);
      T.near(startExp.getX(), end.getX(), eps, "x " + i);
      T.near(startExp.getY(), end.getY(), eps, "y " + i);
      T.near(startExp.getZ(), end.getZ(), eps, "z " + i);
      var q = startExp.getRotation().getQuaternion();
      var e = end.getRotation().getQuaternion();
      T.near(q.getW(), e.getW(), eps, "w " + i);
      T.near(q.getX(), e.getX(), eps, "qx " + i);
      T.near(q.getY(), e.getY(), eps, "qy " + i);
      T.near(q.getZ(), e.getZ(), eps, "qz " + i);
    }
  }

  public static void testTwistNaN() {
    var initialPoses =
        List.of(
            new Pose3d(new Translation3d(6.32, 4.12, 0.00), new Rotation3d(new Quaternion(-0.9999999999999999, 0.0, 0.0, 1.9208309264993548E-8))),
            new Pose3d(new Translation3d(3.75, 2.95, 0.00), new Rotation3d(new Quaternion(0.9999999999999793, 0.0, 0.0, 2.0352360299846772E-7))));
    var finalPoses =
        List.of(
            new Pose3d(new Translation3d(6.33, 4.15, 0.00), new Rotation3d(new Quaternion(-0.9999999999999999, 0.0, 0.0, 2.416890209039172E-8))),
            new Pose3d(new Translation3d(3.66, 2.93, 0.00), new Rotation3d(new Quaternion(0.9999999999999782, 0.0, 0.0, 2.0859477994905617E-7))));
    for (int i = 0; i < initialPoses.size(); i++) {
      var twist = log(initialPoses.get(i), finalPoses.get(i));
      for (double v : new double[] {twist.dx, twist.dy, twist.dz, twist.rx, twist.ry, twist.rz}) {
        T.check(!Double.isNaN(v), "NaN in twist " + i);
      }
    }
  }

  public static void testRawJniLayout() {
    // pure translation: {x, y, z, qw, qx, qy, qz}
    double[] r = Twist3dJNI.exp(0.5, 0.25, -1, 0, 0, 0);
    T.near(new double[] {0.5, 0.25, -1, 1, 0, 0, 0}, r, 1e-15, "exp translation");
    // a quaternion that is not unit length is normalized
    r = Transform3dJNI.log(1, 2, 3, 2, 0, 0, 0);
    T.near(new double[] {1, 2, 3, 0, 0, 0}, r, 1e-15, "log translation");
  }

  public static void testExpLogRoundTripOnRandomPoses() {
    Random rng = new Random(2);
    for (int i = 0; i < 200; i++) {
      var start = randomPose(rng, 1.0);
      var end = randomPose(rng, 1.0);
      var twist = log(start, end);
      var back = exp(start, twist);
      T.near(end.getX(), back.getX(), 1e-9, "x " + i);
      T.near(end.getY(), back.getY(), 1e-9, "y " + i);
      T.near(end.getZ(), back.getZ(), 1e-9, "z " + i);
      var q = back.getRotation().getQuaternion();
      var e = end.getRotation().getQuaternion();
      double dot = q.getW() * e.getW() + q.getX() * e.getX() + q.getY() * e.getY() + q.getZ() * e.getZ();
      T.near(1.0, Math.abs(dot), 1e-9, "rotation " + i);
    }
  }

  public static void testSmallTwistsUseTheTaylorBranch() {
    // 1e-7 to 1e-6 rad loses most digits to cancellation in (1 - cos) and (1 - A / 2B), in the C++ too
    Random rng = new Random(3);
    for (double scale : new double[] {1e-12, 1e-9, 1e-8, 1e-3, 1e-2, 0.1, 1.0}) {
      double rotTol = scale < 1e-3 ? Math.max(1e-9, 1e-3 * scale) : 1e-7;
      for (int i = 0; i < 20; i++) {
        var start = randomPose(rng, 1.0);
        var twist = new Twist3d(rng.nextGaussian() * 0.01, rng.nextGaussian() * 0.01, rng.nextGaussian() * 0.01, rng.nextGaussian() * scale, rng.nextGaussian() * scale, rng.nextGaussian() * scale);
        var recovered = log(start, exp(start, twist));
        T.near(twist.dx, recovered.dx, 1e-7, "dx scale=" + scale);
        T.near(twist.dy, recovered.dy, 1e-7, "dy scale=" + scale);
        T.near(twist.dz, recovered.dz, 1e-7, "dz scale=" + scale);
        T.near(twist.rx, recovered.rx, rotTol, "rx scale=" + scale);
        T.near(twist.ry, recovered.ry, rotTol, "ry scale=" + scale);
        T.near(twist.rz, recovered.rz, rotTol, "rz scale=" + scale);
      }
    }
  }

  public static void testTwistOnSamePoseIsZero() {
    var p = new Pose3d(1, 2, 3, new Rotation3d(0.3, -0.2, 1.1));
    var twist = log(p, p);
    T.near(new double[] {0, 0, 0, 0, 0, 0}, new double[] {twist.dx, twist.dy, twist.dz, twist.rx, twist.ry, twist.rz}, 1e-12, "log of self");
  }

  public static void testExpComposesRotationsInThePoseFrame() {
    // a twist applied to a yawed pose moves along the pose's own x axis
    var start = new Pose3d(0, 0, 0, new Rotation3d(0, 0, Math.PI / 2));
    var moved = exp(start, new Twist3d(2, 0, 0, 0, 0, 0));
    T.near(0.0, moved.getX(), 1e-12, "x");
    T.near(2.0, moved.getY(), 1e-12, "y");
  }

  private static Pose3d exp(Pose3d start, Twist3d twist) {
    return start.plus(twist.exp());
  }

  private static Twist3d log(Pose3d start, Pose3d end) {
    return end.minus(start).log();
  }

  private static Pose3d randomPose(Random rng, double spread) {
    var q = new Quaternion(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian());
    return new Pose3d(
        new Translation3d(rng.nextGaussian() * spread, rng.nextGaussian() * spread, rng.nextGaussian() * spread),
        new Rotation3d(q));
  }
}
