package dev.project516.frcdevwasm.shim.test;

import edu.wpi.first.math.geometry.Ellipse2d;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.jni.Ellipse2dJNI;
import edu.wpi.first.math.jni.WPIMathJNI;
import java.util.Random;

/** Ellipse2d nearest point, from allwpilib Ellipse2dTest. */
public final class MathEllipse2dTest {
  static {
    WPIMathJNI.Helper.setExtractOnStaticLoad(false);
  }

  private static final double EPS = 1E-9;

  public static void testDistance() {
    var center = new Pose2d(1.0, 2.0, Rotation2d.fromDegrees(270.0));
    var ellipse = new Ellipse2d(center, 1.0, 2.0);

    T.near(0.0, ellipse.getDistance(new Translation2d(2.5, 2.0)), EPS, "point1");
    T.near(0.0, ellipse.getDistance(new Translation2d(1.0, 2.0)), EPS, "point2");
    T.near(0.0, ellipse.getDistance(new Translation2d(1.0, 1.0)), EPS, "point3");
    T.near(0.19210128384806818, ellipse.getDistance(new Translation2d(-1.0, 2.5)), EPS, "point4");
  }

  public static void testNearest() {
    var center = new Pose2d(1.0, 2.0, Rotation2d.fromDegrees(270.0));
    var ellipse = new Ellipse2d(center, 1.0, 2.0);

    var n1 = ellipse.nearest(new Translation2d(2.5, 2.0));
    T.near(2.5, n1.getX(), EPS, "n1 x");
    T.near(2.0, n1.getY(), EPS, "n1 y");

    var n2 = ellipse.nearest(new Translation2d(1.0, 2.0));
    T.near(1.0, n2.getX(), EPS, "n2 x");
    T.near(2.0, n2.getY(), EPS, "n2 y");

    var n3 = ellipse.nearest(new Translation2d(1.0, 1.0));
    T.near(1.0, n3.getX(), EPS, "n3 x");
    T.near(1.0, n3.getY(), EPS, "n3 y");

    var n4 = ellipse.nearest(new Translation2d(-1.0, 2.5));
    T.near(-0.8512799937611617, n4.getX(), EPS, "n4 x");
    T.near(2.378405333174535, n4.getY(), EPS, "n4 y");
  }

  private static double[] nearest(double cx, double cy, double heading, double a, double b, double px, double py) {
    double[] out = new double[2];
    Ellipse2dJNI.nearest(cx, cy, heading, a, b, px, py, out);
    return out;
  }

  public static void testAxisAlignedCircle() {
    // unit circle at the origin: nearest point is the projection onto the circle
    double[] n = nearest(0, 0, 0, 1, 1, 3, 4);
    T.near(0.6, n[0], 1e-12, "x");
    T.near(0.8, n[1], 1e-12, "y");
  }

  public static void testPointsOnTheAxes() {
    double[] n = nearest(0, 0, 0, 3, 1, 10, 0);
    T.near(3.0, n[0], 1e-12, "+x axis");
    T.near(0.0, n[1], 1e-12, "+x axis y");
    n = nearest(0, 0, 0, 3, 1, -10, 0);
    T.near(-3.0, n[0], 1e-12, "-x axis");
    n = nearest(0, 0, 0, 3, 1, 0, 5);
    T.near(0.0, n[0], 1e-12, "+y axis x");
    T.near(1.0, n[1], 1e-12, "+y axis");
    n = nearest(0, 0, 0, 1, 3, 0, -5);
    T.near(-3.0, n[1], 1e-12, "tall ellipse, -y axis");
  }

  public static void testNearestPointsLieOnTheEllipseWithAnOrthogonalNormal() {
    // the vector from the nearest point to the query is parallel to the ellipse normal
    Random rng = new Random(8);
    for (int i = 0; i < 500; i++) {
      double a = 0.2 + 3 * rng.nextDouble();
      double b = 0.2 + 3 * rng.nextDouble();
      double heading = (rng.nextDouble() - 0.5) * 2 * Math.PI;
      double cx = rng.nextGaussian();
      double cy = rng.nextGaussian();
      double px = cx + rng.nextGaussian() * 5;
      double py = cy + rng.nextGaussian() * 5;
      double[] n = nearest(cx, cy, heading, a, b, px, py);

      double c = Math.cos(heading);
      double s = Math.sin(heading);
      double u = (n[0] - cx) * c + (n[1] - cy) * s;
      double v = -(n[0] - cx) * s + (n[1] - cy) * c;
      double pu = (px - cx) * c + (py - cy) * s;
      double pv = -(px - cx) * s + (py - cy) * c;
      if ((pu * pu) / (a * a) + (pv * pv) / (b * b) <= 1.0) {
        T.near(px, n[0], 1e-12, "inside x " + i);
        T.near(py, n[1], 1e-12, "inside y " + i);
        continue;
      }
      T.near(1.0, (u * u) / (a * a) + (v * v) / (b * b), 1e-9, "on ellipse " + i);
      // normal at (u, v) is (u / a^2, v / b^2)
      double cross = (pu - u) * (v / (b * b)) - (pv - v) * (u / (a * a));
      T.near(0.0, cross, 1e-7 * (1 + Math.hypot(pu - u, pv - v)), "normal " + i);

      // no sampled boundary point is closer
      double best = Math.hypot(pu - u, pv - v);
      for (int k = 0; k < 200; k++) {
        double t = 2 * Math.PI * k / 200;
        double d = Math.hypot(pu - a * Math.cos(t), pv - b * Math.sin(t));
        T.check(d >= best - 1e-9, "closer boundary point " + i);
      }
    }
  }

  public static void testInsidePointIsReturnedUnchanged() {
    double[] n = nearest(1, 2, 0.4, 2, 1, 1.1, 2.1);
    T.eq(1.1, n[0], "x");
    T.eq(2.1, n[1], "y");
  }
}
