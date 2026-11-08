package dev.project516.frcdevwasm.shim.apriltag.jni;

/**
 * Tag pose estimation ported from apriltag_pose.c and AprilTagPoseEstimator.cpp. A pose is a
 * row-major 3x3 rotation R and a translation t with ray(corner) ~ R * p + t, where p is the tag
 * corner in the tag frame.
 */
final class AprilTagPose {
  private AprilTagPose() {}

  /** Rotation (row-major) and translation of a pose; null fields mean no pose. */
  static final class Pose {
    double[] r;
    double[] t;
  }

  /** Both orthogonal-iteration solutions; pose2 has null fields and err2 is infinity if none. */
  static final class Solutions {
    final Pose pose1 = new Pose();
    final Pose pose2 = new Pose();
    double err1;
    double err2;
  }

  private static final double[] I3 = {1, 0, 0, 0, 1, 0, 0, 0, 1};

  // 3x3 and 3-vector helpers

  private static double[] mul(double[] a, double[] b) {
    double[] c = new double[9];
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        c[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
      }
    }
    return c;
  }

  private static double[] mulv(double[] a, double[] v) {
    return new double[] {
      a[0] * v[0] + a[1] * v[1] + a[2] * v[2],
      a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
      a[6] * v[0] + a[7] * v[1] + a[8] * v[2]
    };
  }

  private static double[] transpose(double[] a) {
    return new double[] {a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
  }

  private static double[] sub(double[] a, double[] b) {
    double[] c = new double[a.length];
    for (int i = 0; i < a.length; i++) {
      c[i] = a[i] - b[i];
    }
    return c;
  }

  private static double[] add(double[] a, double[] b) {
    double[] c = new double[a.length];
    for (int i = 0; i < a.length; i++) {
      c[i] = a[i] + b[i];
    }
    return c;
  }

  private static double[] scale(double[] a, double s) {
    double[] c = new double[a.length];
    for (int i = 0; i < a.length; i++) {
      c[i] = a[i] * s;
    }
    return c;
  }

  private static double dot(double[] a, double[] b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  }

  private static double[] cross(double[] a, double[] b) {
    return new double[] {
      a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
    };
  }

  private static double[] normalize(double[] v) {
    return scale(v, 1.0 / Math.sqrt(dot(v, v)));
  }

  private static double det(double[] m) {
    return m[0] * (m[4] * m[8] - m[5] * m[7])
        - m[1] * (m[3] * m[8] - m[5] * m[6])
        + m[2] * (m[3] * m[7] - m[4] * m[6]);
  }

  private static double[] inverse(double[] m) {
    double d = det(m);
    return new double[] {
      (m[4] * m[8] - m[5] * m[7]) / d,
      (m[2] * m[7] - m[1] * m[8]) / d,
      (m[1] * m[5] - m[2] * m[4]) / d,
      (m[5] * m[6] - m[3] * m[8]) / d,
      (m[0] * m[8] - m[2] * m[6]) / d,
      (m[2] * m[3] - m[0] * m[5]) / d,
      (m[3] * m[7] - m[4] * m[6]) / d,
      (m[1] * m[6] - m[0] * m[7]) / d,
      (m[0] * m[4] - m[1] * m[3]) / d
    };
  }

  private static double[] outer(double[] a, double[] b) {
    return new double[] {
      a[0] * b[0], a[0] * b[1], a[0] * b[2],
      a[1] * b[0], a[1] * b[1], a[1] * b[2],
      a[2] * b[0], a[2] * b[1], a[2] * b[2]
    };
  }

  private static double frobenius(double[] m) {
    double s = 0;
    for (double x : m) {
      s += x * x;
    }
    return Math.sqrt(s);
  }

  /** Orthogonal factor U*V' of a nonsingular matrix, by scaled Newton iteration. */
  private static double[] polar(double[] a) {
    double[] q = a;
    for (int i = 0; i < 100; i++) {
      double[] inv = inverse(q);
      double gamma = Math.sqrt(frobenius(inv) / frobenius(q));
      double[] next = scale(add(scale(q, gamma), scale(transpose(inv), 1.0 / gamma)), 0.5);
      double change = frobenius(sub(next, q));
      q = next;
      if (change <= 1e-15 * frobenius(q)) {
        break;
      }
    }
    return q;
  }

  /** Rotation from a matrix whose third column is zero, matching the SVD of cscore's M3 with det fixed to +1. */
  private static double[] rotationFromPlanar(double[] m) {
    double[] c1 = {m[0], m[3], m[6]};
    double[] c2 = {m[1], m[4], m[7]};
    double[] c3 = cross(c1, c2);
    return polar(new double[] {c1[0], c2[0], c3[0], c1[1], c2[1], c3[1], c1[2], c2[2], c3[2]});
  }

  /** C sqrtf applied to a double. */
  private static double sqrtf(double x) {
    return (float) Math.sqrt((float) x);
  }

  // Homography method

  /** homography_to_pose followed by the y/z flip of estimate_pose_for_tag_homography. */
  static Pose fromHomography(double[] h, double tagSize, double fx, double fy, double cx, double cy) {
    double fxn = -fx;
    double r20 = h[6];
    double r21 = h[7];
    double tz = h[8];
    double r00 = (h[0] - cx * r20) / fxn;
    double r01 = (h[1] - cx * r21) / fxn;
    double tx = (h[2] - cx * tz) / fxn;
    double r10 = (h[3] - cy * r20) / fy;
    double r11 = (h[4] - cy * r21) / fy;
    double ty = (h[5] - cy * tz) / fy;

    double length1 = sqrtf(r00 * r00 + r10 * r10 + r20 * r20);
    double length2 = sqrtf(r01 * r01 + r11 * r11 + r21 * r21);
    double s = 1.0 / sqrtf(length1 * length2);
    if (tz > 0) {
      s *= -1;
    }
    r20 *= s;
    r21 *= s;
    tz *= s;
    r00 *= s;
    r01 *= s;
    tx *= s;
    r10 *= s;
    r11 *= s;
    ty *= s;

    double r02 = r10 * r21 - r20 * r11;
    double r12 = r20 * r01 - r00 * r21;
    double r22 = r00 * r11 - r10 * r01;
    double[] r = polar(new double[] {r00, r01, r02, r10, r11, r12, r20, r21, r22});

    double scale = tagSize / 2.0;
    Pose pose = new Pose();
    pose.r = new double[] {r[0], r[1], r[2], -r[3], -r[4], -r[5], -r[6], -r[7], -r[8]};
    pose.t = new double[] {tx * scale, -ty * scale, -tz * scale};
    return pose;
  }

  // Orthogonal iteration

  private static double[] projection(double[] v) {
    return scale(outer(v, v), 1.0 / dot(v, v));
  }

  /** Lu et al. 2000. Updates r and t in place and returns the object-space error. */
  private static double orthogonalIteration(
      double[][] v, double[][] p, double[] t, double[] r, int nSteps, double minImprovement) {
    int n = 4;
    double[] pMean = new double[3];
    for (double[] pi : p) {
      pMean = add(pMean, pi);
    }
    pMean = scale(pMean, 1.0 / n);
    double[][] pRes = new double[n][];
    double[][] f = new double[n][];
    double[] avgF = new double[9];
    for (int i = 0; i < n; i++) {
      pRes[i] = sub(p[i], pMean);
      f[i] = projection(v[i]);
      avgF = add(avgF, f[i]);
    }
    avgF = scale(avgF, 1.0 / n);
    double[] m1Inv = inverse(sub(I3, avgF));

    double prevError = Double.POSITIVE_INFINITY;
    for (int step = 0; step < nSteps; step++) {
      double[] m2 = new double[3];
      for (int j = 0; j < n; j++) {
        m2 = add(m2, mulv(sub(f[j], I3), mulv(r, p[j])));
      }
      m2 = scale(m2, 1.0 / n);
      double[] tNew = mulv(m1Inv, m2);
      System.arraycopy(tNew, 0, t, 0, 3);

      double[][] q = new double[n][];
      double[] qMean = new double[3];
      for (int j = 0; j < n; j++) {
        q[j] = mulv(f[j], add(mulv(r, p[j]), t));
        qMean = add(qMean, q[j]);
      }
      qMean = scale(qMean, 1.0 / n);
      double[] m3 = new double[9];
      for (int j = 0; j < n; j++) {
        m3 = add(m3, outer(sub(q[j], qMean), pRes[j]));
      }
      System.arraycopy(rotationFromPlanar(m3), 0, r, 0, 9);

      double error = 0;
      for (int j = 0; j < n; j++) {
        double[] e = mulv(sub(I3, f[j]), add(mulv(r, p[j]), t));
        error += dot(e, e);
      }
      boolean converged = Math.abs(error - prevError) < minImprovement;
      prevError = error;
      if (converged) {
        break;
      }
    }
    return prevError;
  }

  // Second local minimum (Schweighofer and Pinz 2006)

  private static double polyval(double[] p, int degree, double x) {
    double ret = 0;
    for (int i = 0; i <= degree; i++) {
      ret += p[i] * Math.pow(x, i);
    }
    return ret;
  }

  /** Roots of a small polynomial, ignoring roots beyond 1000. Returns the root count. */
  private static int solvePolyApprox(double[] p, int degree, double[] roots) {
    final int maxRoot = 1000;
    if (degree == 1) {
      if (Math.abs(p[0]) > maxRoot * Math.abs(p[1])) {
        return 0;
      }
      roots[0] = -p[0] / p[1];
      return 1;
    }
    double[] pDer = new double[degree];
    for (int i = 0; i < degree; i++) {
      pDer[i] = (i + 1) * p[i + 1];
    }
    double[] derRoots = new double[degree - 1];
    int nDer = solvePolyApprox(pDer, degree - 1, derRoots);

    int nRoots = 0;
    for (int i = 0; i <= nDer; i++) {
      double min = i == 0 ? -maxRoot : derRoots[i - 1];
      double max = i == nDer ? maxRoot : derRoots[i];
      if (polyval(p, degree, min) * polyval(p, degree, max) < 0) {
        double lower;
        double upper;
        if (polyval(p, degree, min) < polyval(p, degree, max)) {
          lower = min;
          upper = max;
        } else {
          lower = max;
          upper = min;
        }
        double root = 0.5 * (lower + upper);
        double dxOld = upper - lower;
        double dx = dxOld;
        double f = polyval(p, degree, root);
        double df = polyval(pDer, degree - 1, root);
        for (int j = 0; j < 100; j++) {
          if (((f + df * (upper - root)) * (f + df * (lower - root)) > 0)
              || (Math.abs(2 * f) > Math.abs(dxOld * df))) {
            dxOld = dx;
            dx = 0.5 * (upper - lower);
            root = lower + dx;
          } else {
            dxOld = dx;
            dx = -f / df;
            root += dx;
          }
          if (root == upper || root == lower) {
            break;
          }
          f = polyval(p, degree, root);
          df = polyval(pDer, degree - 1, root);
          if (f > 0) {
            upper = root;
          } else {
            lower = root;
          }
        }
        roots[nRoots++] = root;
      } else if (polyval(p, degree, max) == 0) {
        roots[nRoots++] = max;
      }
    }
    return nRoots;
  }

  /** Given one local minimum, returns the rotation of the other one or null. */
  private static double[] fixPoseAmbiguities(double[][] v, double[][] p, double[] t, double[] r) {
    int n = 4;
    double[] rt3 = normalize(t);
    double[] ex = {1, 0, 0};
    double[] rt1 = normalize(sub(ex, scale(rt3, dot(ex, rt3))));
    double[] rt2 = cross(rt3, rt1);
    double[] rT = {rt1[0], rt1[1], rt1[2], rt2[0], rt2[1], rt2[2], rt3[0], rt3[1], rt3[2]};

    double[] r1Prime = mul(rT, r);
    double r31 = r1Prime[6];
    double r32 = r1Prime[7];
    double hypotenuse = Math.sqrt(r31 * r31 + r32 * r32);
    if (hypotenuse < 1e-100) {
      r31 = 1;
      r32 = 0;
      hypotenuse = 1;
    }
    double[] rZ = {
      r31 / hypotenuse, -r32 / hypotenuse, 0,
      r32 / hypotenuse, r31 / hypotenuse, 0,
      0, 0, 1
    };

    double[] rTrans = mul(r1Prime, rZ);
    double sinGamma = -rTrans[1];
    double cosGamma = rTrans[4];
    double[] rGamma = {cosGamma, -sinGamma, 0, sinGamma, cosGamma, 0, 0, 0, 1};
    double sinBeta = -rTrans[6];
    double cosBeta = rTrans[8];
    double tInitial = Math.atan2(sinBeta, cosBeta);

    double[] rZT = transpose(rZ);
    double[][] pTrans = new double[n][];
    double[][] fTrans = new double[n][];
    double[] avgFTrans = new double[9];
    for (int i = 0; i < n; i++) {
      pTrans[i] = mulv(rZT, p[i]);
      fTrans[i] = projection(mulv(rT, v[i]));
      avgFTrans = add(avgFTrans, fTrans[i]);
    }
    avgFTrans = scale(avgFTrans, 1.0 / n);
    double[] g = scale(inverse(sub(I3, avgFTrans)), 1.0 / n);

    double[] m1 = {0, 0, 2, 0, 0, 0, -2, 0, 0};
    double[] m2 = {-1, 0, 0, 0, 1, 0, 0, 0, -1};
    double[] b0 = new double[3];
    double[] b1 = new double[3];
    double[] b2 = new double[3];
    for (int i = 0; i < n; i++) {
      double[] fi = sub(fTrans[i], I3);
      b0 = add(b0, mulv(fi, mulv(rGamma, pTrans[i])));
      b1 = add(b1, mulv(fi, mulv(rGamma, mulv(m1, pTrans[i]))));
      b2 = add(b2, mulv(fi, mulv(rGamma, mulv(m2, pTrans[i]))));
    }
    double[] b0p = mulv(g, b0);
    double[] b1p = mulv(g, b1);
    double[] b2p = mulv(g, b2);

    double a0 = 0;
    double a1 = 0;
    double a2 = 0;
    double a3 = 0;
    double a4 = 0;
    for (int i = 0; i < n; i++) {
      double[] imf = sub(I3, fTrans[i]);
      double[] c0 = mulv(imf, add(mulv(rGamma, pTrans[i]), b0p));
      double[] c1 = mulv(imf, add(mulv(rGamma, mulv(m1, pTrans[i])), b1p));
      double[] c2 = mulv(imf, add(mulv(rGamma, mulv(m2, pTrans[i])), b2p));
      a0 += dot(c0, c0);
      a1 += 2 * dot(c0, c1);
      a2 += dot(c1, c1) + 2 * dot(c0, c2);
      a3 += 2 * dot(c1, c2);
      a4 += dot(c2, c2);
    }

    double[] poly = {a1, 2 * a2 - 4 * a0, 3 * a3 - 3 * a1, 4 * a4 - 2 * a2, -a3};
    double[] roots = new double[4];
    int nRoots = solvePolyApprox(poly, 4, roots);

    double[] minima = new double[4];
    int nMinima = 0;
    for (int i = 0; i < nRoots; i++) {
      double t1 = roots[i];
      double t2 = t1 * t1;
      double t3 = t1 * t2;
      double t4 = t1 * t3;
      double t5 = t1 * t4;
      if (a2 - 2 * a0 + (3 * a3 - 6 * a1) * t1 + (6 * a4 - 8 * a2 + 10 * a0) * t2
              + (-8 * a3 + 6 * a1) * t3 + (-6 * a4 + 3 * a2) * t4 + a3 * t5
          >= 0) {
        double tCur = 2 * Math.atan(roots[i]);
        if (Math.abs(tCur - tInitial) > 0.1) {
          minima[nMinima++] = roots[i];
        }
      }
    }
    if (nMinima != 1) {
      return null;
    }
    double tCur = minima[0];
    double[] rBeta = add(scale(add(scale(m2, tCur), m1), tCur), I3);
    rBeta = scale(rBeta, 1 / (1 + tCur * tCur));
    return mul(mul(mul(transpose(rT), rGamma), rBeta), rZT);
  }

  /** estimate_tag_pose_orthogonal_iteration. Corners are 4 (x, y) pairs. */
  static Solutions orthogonalIteration(
      double[] h,
      double[] corners,
      double tagSize,
      double fx,
      double fy,
      double cx,
      double cy,
      int nIters,
      double minImprovement) {
    double s = tagSize / 2.0;
    double[][] p = {{-s, s, 0}, {s, s, 0}, {s, -s, 0}, {-s, -s, 0}};
    double[][] v = new double[4][];
    for (int i = 0; i < 4; i++) {
      v[i] = new double[] {(corners[i * 2] - cx) / fx, (corners[i * 2 + 1] - cy) / fy, 1};
    }

    Solutions out = new Solutions();
    out.pose1.r = new double[9];
    out.pose1.t = new double[3];
    Pose init = fromHomography(h, tagSize, fx, fy, cx, cy);
    System.arraycopy(init.r, 0, out.pose1.r, 0, 9);
    System.arraycopy(init.t, 0, out.pose1.t, 0, 3);
    out.err1 = orthogonalIteration(v, p, out.pose1.t, out.pose1.r, nIters, minImprovement);

    double[] r2 = fixPoseAmbiguities(v, p, out.pose1.t, out.pose1.r);
    if (r2 != null) {
      out.pose2.r = r2;
      out.pose2.t = new double[3];
      out.err2 = orthogonalIteration(v, p, out.pose2.t, out.pose2.r, nIters, minImprovement);
    } else {
      out.err2 = Double.POSITIVE_INFINITY;
    }
    return out;
  }

  /** The better of the two orthogonal-iteration solutions with the default 50 iterations. */
  static Pose best(
      double[] h, double[] corners, double tagSize, double fx, double fy, double cx, double cy) {
    Solutions s = orthogonalIteration(h, corners, tagSize, fx, fy, cx, cy, 50, 1e-7);
    return s.err1 <= s.err2 ? s.pose1 : s.pose2;
  }

  /** Nearest rotation matrix with Gram-Schmidt on the columns (R diagonal positive). */
  static double[] orthogonalize(double[] m) {
    double[] c1 = {m[0], m[3], m[6]};
    double[] c2 = {m[1], m[4], m[7]};
    double[] c3 = {m[2], m[5], m[8]};
    double[] q1 = normalize(c1);
    double[] q2 = normalize(sub(c2, scale(q1, dot(q1, c2))));
    double[] q3 = normalize(sub(sub(c3, scale(q1, dot(q1, c3))), scale(q2, dot(q2, c3))));
    return new double[] {q1[0], q2[0], q3[0], q1[1], q2[1], q3[1], q1[2], q2[2], q3[2]};
  }

  /** Quaternion (w, x, y, z) of a rotation matrix, as Rotation3d(Matrix) computes it. */
  static double[] quaternion(double[] r) {
    double trace = r[0] + r[4] + r[8];
    double w;
    double x;
    double y;
    double z;
    if (trace > 0.0) {
      double s = 0.5 / Math.sqrt(trace + 1.0);
      w = 0.25 / s;
      x = (r[7] - r[5]) * s;
      y = (r[2] - r[6]) * s;
      z = (r[3] - r[1]) * s;
    } else if (r[0] > r[4] && r[0] > r[8]) {
      double s = 2.0 * Math.sqrt(1.0 + r[0] - r[4] - r[8]);
      w = (r[7] - r[5]) / s;
      x = 0.25 * s;
      y = (r[1] + r[3]) / s;
      z = (r[2] + r[6]) / s;
    } else if (r[4] > r[8]) {
      double s = 2.0 * Math.sqrt(1.0 + r[4] - r[0] - r[8]);
      w = (r[2] - r[6]) / s;
      x = (r[1] + r[3]) / s;
      y = 0.25 * s;
      z = (r[5] + r[7]) / s;
    } else {
      double s = 2.0 * Math.sqrt(1.0 + r[8] - r[0] - r[4]);
      w = (r[3] - r[1]) / s;
      x = (r[2] + r[6]) / s;
      y = (r[5] + r[7]) / s;
      z = 0.25 * s;
    }
    return new double[] {w, x, y, z};
  }
}
