package dev.project516.frcdevwasm.shim.math.jni;

public final class Ellipse2dJNIImpl {
  private Ellipse2dJNIImpl() {}

  /** Nearest point of the ellipse to the query point, or the point itself if inside. */
  public static void nearest(
      double centerX,
      double centerY,
      double centerHeading,
      double xSemiAxis,
      double ySemiAxis,
      double pointX,
      double pointY,
      double[] nearestPoint) {
    double cos = Math.cos(centerHeading);
    double sin = Math.sin(centerHeading);
    // point relative to the center, in the ellipse frame
    double dx = pointX - centerX;
    double dy = pointY - centerY;
    double u = dx * cos + dy * sin;
    double v = -dx * sin + dy * cos;

    if ((u * u) / (xSemiAxis * xSemiAxis) + (v * v) / (ySemiAxis * ySemiAxis) <= 1.0) {
      nearestPoint[0] = pointX;
      nearestPoint[1] = pointY;
      return;
    }

    double[] n = new double[2];
    if (xSemiAxis >= ySemiAxis) {
      closest(xSemiAxis, ySemiAxis, Math.abs(u), Math.abs(v), n);
    } else {
      closest(ySemiAxis, xSemiAxis, Math.abs(v), Math.abs(u), n);
      double t = n[0];
      n[0] = n[1];
      n[1] = t;
    }
    double x = Math.copySign(n[0], u);
    double y = Math.copySign(n[1], v);
    nearestPoint[0] = centerX + x * cos - y * sin;
    nearestPoint[1] = centerY + x * sin + y * cos;
  }

  /** Closest point on x^2/e0^2 + y^2/e1^2 = 1 to (y0, y1) in the first quadrant, e0 >= e1 > 0. */
  private static void closest(double e0, double e1, double y0, double y1, double[] out) {
    if (y1 > 0) {
      if (y0 > 0) {
        double z0 = y0 / e0;
        double z1 = y1 / e1;
        double g = z0 * z0 + z1 * z1 - 1;
        if (g != 0) {
          double r0 = (e0 / e1) * (e0 / e1);
          double sbar = root(r0, z0, z1, g);
          out[0] = r0 * y0 / (sbar + r0);
          out[1] = y1 / (sbar + 1);
        } else {
          out[0] = y0;
          out[1] = y1;
        }
      } else {
        out[0] = 0;
        out[1] = e1;
      }
    } else {
      double numer0 = e0 * y0;
      double denom0 = e0 * e0 - e1 * e1;
      if (numer0 < denom0) {
        double xde0 = numer0 / denom0;
        out[0] = e0 * xde0;
        out[1] = e1 * Math.sqrt(1 - xde0 * xde0);
      } else {
        out[0] = e0;
        out[1] = 0;
      }
    }
  }

  /** Bisection for the root of the Lagrange multiplier equation. */
  private static double root(double r0, double z0, double z1, double g) {
    double n0 = r0 * z0;
    double s0 = z1 - 1;
    double s1 = g < 0 ? 0 : Math.hypot(n0, z1) - 1;
    double s = 0;
    for (int i = 0; i < 2048; i++) {
      s = (s0 + s1) / 2;
      if (s == s0 || s == s1) {
        break;
      }
      double ratio0 = n0 / (s + r0);
      double ratio1 = z1 / (s + 1);
      double gs = ratio0 * ratio0 + ratio1 * ratio1 - 1;
      if (gs > 0) {
        s0 = s;
      } else if (gs < 0) {
        s1 = s;
      } else {
        break;
      }
    }
    return s;
  }
}
