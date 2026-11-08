package dev.project516.frcdevwasm.shim.vendor.opencv.calib3d;

import dev.project516.frcdevwasm.shim.vendor.opencv.core.Linear;
import dev.project516.frcdevwasm.shim.vendor.opencv.core.MatImpl;
import dev.project516.frcdevwasm.shim.vendor.opencv.core.MatImpl.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * The org.opencv.calib3d.Calib3d functions that pose estimation calls: projecting and undistorting
 * points with the pinhole model and its distortion coefficients (k1 k2 p1 p2 k3 k4 k5 k6), and solvePnP.
 * Poses are found from a homography or a DLT and then refined on the reprojection error, so every
 * flag gets the pose that minimizes it. IPPE flags also return the second pose of a planar target.
 */
public final class Calib3dImpl {
  private Calib3dImpl() {}

  private static final int SOLVEPNP_IPPE = 6;
  private static final int SOLVEPNP_IPPE_SQUARE = 7;

  /** The pinhole camera of a camera matrix and distortion coefficients. */
  private static final class Camera {
    final double fx;
    final double fy;
    final double cx;
    final double cy;
    final double[] k = new double[8];

    Camera(long cameraMatrix, long distCoeffs) {
      Data m = MatImpl.data(cameraMatrix);
      boolean given = m.rows >= 3 && m.cols >= 3;
      fx = given ? m.get(0, 0, 0) : 1;
      fy = given ? m.get(1, 1, 0) : 1;
      cx = given ? m.get(0, 2, 0) : 0;
      cy = given ? m.get(1, 2, 0) : 0;
      Data d = MatImpl.data(distCoeffs);
      for (int i = 0; i < Math.min(8, d.size()); i++) {
        k[i] = d.get(i);
      }
    }

    /** Pixel of a point in camera coordinates. */
    double[] project(double x, double y, double z) {
      double w = z == 0 ? 1 : z;
      double[] d = distort(x / w, y / w);
      return new double[] {fx * d[0] + cx, fy * d[1] + cy};
    }

    double[] distort(double x, double y) {
      double r2 = x * x + y * y;
      double r4 = r2 * r2;
      double r6 = r4 * r2;
      double radial = (1 + k[0] * r2 + k[1] * r4 + k[4] * r6) / (1 + k[5] * r2 + k[6] * r4 + k[7] * r6);
      return new double[] {
        x * radial + 2 * k[2] * x * y + k[3] * (r2 + 2 * x * x), y * radial + k[2] * (r2 + 2 * y * y) + 2 * k[3] * x * y
      };
    }

    /** Normalized coordinates of a pixel with the distortion removed. */
    double[] undistort(double u, double v) {
      double x0 = (u - cx) / fx;
      double y0 = (v - cy) / fy;
      double x = x0;
      double y = y0;
      for (int i = 0; i < 50; i++) {
        double r2 = x * x + y * y;
        double r4 = r2 * r2;
        double r6 = r4 * r2;
        double inverse = (1 + k[5] * r2 + k[6] * r4 + k[7] * r6) / (1 + k[0] * r2 + k[1] * r4 + k[4] * r6);
        double nextX = (x0 - (2 * k[2] * x * y + k[3] * (r2 + 2 * x * x))) * inverse;
        double nextY = (y0 - (k[2] * (r2 + 2 * y * y) + 2 * k[3] * x * y)) * inverse;
        boolean done = Math.abs(nextX - x) + Math.abs(nextY - y) < 1e-14;
        x = nextX;
        y = nextY;
        if (done) {
          break;
        }
      }
      return new double[] {x, y};
    }
  }

  // Rotations

  private static double[][] rotationOf(double[] rvec) {
    double angle = Math.sqrt(rvec[0] * rvec[0] + rvec[1] * rvec[1] + rvec[2] * rvec[2]);
    if (angle < 1e-14) {
      return Linear.identity3();
    }
    double x = rvec[0] / angle;
    double y = rvec[1] / angle;
    double z = rvec[2] / angle;
    double c = Math.cos(angle);
    double s = Math.sin(angle);
    double c1 = 1 - c;
    return new double[][] {
      {c + x * x * c1, x * y * c1 - z * s, x * z * c1 + y * s},
      {y * x * c1 + z * s, c + y * y * c1, y * z * c1 - x * s},
      {z * x * c1 - y * s, z * y * c1 + x * s, c + z * z * c1}
    };
  }

  private static double[] rvecOf(double[][] r) {
    double trace = r[0][0] + r[1][1] + r[2][2];
    double w;
    double x;
    double y;
    double z;
    if (trace > 0) {
      double s = Math.sqrt(trace + 1) * 2;
      w = s / 4;
      x = (r[2][1] - r[1][2]) / s;
      y = (r[0][2] - r[2][0]) / s;
      z = (r[1][0] - r[0][1]) / s;
    } else if (r[0][0] > r[1][1] && r[0][0] > r[2][2]) {
      double s = Math.sqrt(1 + r[0][0] - r[1][1] - r[2][2]) * 2;
      w = (r[2][1] - r[1][2]) / s;
      x = s / 4;
      y = (r[0][1] + r[1][0]) / s;
      z = (r[0][2] + r[2][0]) / s;
    } else if (r[1][1] > r[2][2]) {
      double s = Math.sqrt(1 + r[1][1] - r[0][0] - r[2][2]) * 2;
      w = (r[0][2] - r[2][0]) / s;
      x = (r[0][1] + r[1][0]) / s;
      y = s / 4;
      z = (r[1][2] + r[2][1]) / s;
    } else {
      double s = Math.sqrt(1 + r[2][2] - r[0][0] - r[1][1]) * 2;
      w = (r[1][0] - r[0][1]) / s;
      x = (r[0][2] + r[2][0]) / s;
      y = (r[1][2] + r[2][1]) / s;
      z = s / 4;
    }
    if (w < 0) {
      w = -w;
      x = -x;
      y = -y;
      z = -z;
    }
    double sine = Math.sqrt(x * x + y * y + z * z);
    if (sine < 1e-14) {
      return new double[3];
    }
    double angle = 2 * Math.atan2(sine, w);
    return new double[] {x / sine * angle, y / sine * angle, z / sine * angle};
  }

  /** A rotation vector or 3x3 rotation matrix in a Mat. */
  private static double[][] rotationOf(Data d) {
    if (d.size() == 9) {
      double[][] r = new double[3][3];
      for (int i = 0; i < 9; i++) {
        r[i / 3][i % 3] = d.get(i);
      }
      return r;
    }
    return rotationOf(new double[] {d.get(0), d.get(1), d.get(2)});
  }

  private static Data column(double... values) {
    Data d = new Data();
    d.alloc(values.length, 1, MatImpl.type(MatImpl.CV_64F, 1));
    for (int i = 0; i < values.length; i++) {
      d.set(i, values[i]);
    }
    return d;
  }

  public static void Rodrigues_0(long src, long dst, long jacobian) {
    Data s = MatImpl.data(src);
    Data out = new Data();
    if (s.size() == 9) {
      double[] r = rvecOf(rotationOf(s));
      out.alloc(3, 1, MatImpl.type(s.depth(), 1));
      for (int i = 0; i < 3; i++) {
        out.set(i, r[i]);
      }
    } else {
      double[][] r = rotationOf(s);
      out.alloc(3, 3, MatImpl.type(s.depth(), 1));
      for (int i = 0; i < 9; i++) {
        out.set(i, r[i / 3][i % 3]);
      }
    }
    MatImpl.data(dst).share(out);
  }

  public static void Rodrigues_1(long src, long dst) {
    Rodrigues_0(src, dst, 0);
  }

  // Projection and undistortion

  private static void storePoints(long dst, Data like, double[][] points, int dims) {
    Data out = new Data();
    out.alloc(like.rows, like.cols, like.type);
    for (int i = 0; i < points.length; i++) {
      for (int k = 0; k < dims; k++) {
        out.set(i * dims + k, points[i][k]);
      }
    }
    MatImpl.data(dst).share(out);
  }

  public static void projectPoints_0(long objectPoints, long rvec, long tvec, long cameraMatrix, long distCoeffs, long imagePoints, long jacobian, double aspectRatio) {
    projectPoints_2(objectPoints, rvec, tvec, cameraMatrix, distCoeffs, imagePoints);
  }

  public static void projectPoints_1(long objectPoints, long rvec, long tvec, long cameraMatrix, long distCoeffs, long imagePoints, long jacobian) {
    projectPoints_2(objectPoints, rvec, tvec, cameraMatrix, distCoeffs, imagePoints);
  }

  public static void projectPoints_2(long objectPoints, long rvec, long tvec, long cameraMatrix, long distCoeffs, long imagePoints) {
    Data objects = MatImpl.data(objectPoints);
    double[][] points = MatImpl.points(objects, 3);
    double[][] r = rotationOf(MatImpl.data(rvec));
    Data translation = MatImpl.data(tvec);
    double[] t = {translation.get(0), translation.get(1), translation.get(2)};
    Camera camera = new Camera(cameraMatrix, distCoeffs);
    double[][] pixels = new double[points.length][];
    for (int i = 0; i < points.length; i++) {
      double[] c = camera(r, t, points[i]);
      pixels[i] = camera.project(c[0], c[1], c[2]);
    }
    int depth = objects.depth() == MatImpl.CV_64F ? MatImpl.CV_64F : MatImpl.CV_32F;
    MatImpl.data(imagePoints).share(MatImpl.pointColumn(pixels, depth, 2));
  }

  private static double[] camera(double[][] r, double[] t, double[] p) {
    return new double[] {
      r[0][0] * p[0] + r[0][1] * p[1] + r[0][2] * p[2] + t[0],
      r[1][0] * p[0] + r[1][1] * p[1] + r[1][2] * p[2] + t[1],
      r[2][0] * p[0] + r[2][1] * p[1] + r[2][2] * p[2] + t[2]
    };
  }

  /** Undistorts pixels to normalized coordinates, then maps them through p (3x3, or the camera matrix when asPixels). */
  private static void undistort(long src, long dst, long cameraMatrix, long distCoeffs, long rectification, long newCamera, boolean asPixels) {
    Data s = MatImpl.data(src);
    Camera camera = new Camera(cameraMatrix, distCoeffs);
    Data rData = MatImpl.data(rectification);
    double[][] r = rData.size() == 9 ? rotationOf(rData) : null;
    Data pData = MatImpl.data(newCamera);
    boolean mapped = asPixels || pData.rows >= 3 && pData.cols >= 3;
    double[][] points = MatImpl.points(s, 2);
    for (double[] p : points) {
      double[] n = camera.undistort(p[0], p[1]);
      double x = n[0];
      double y = n[1];
      if (r != null) {
        double[] rotated = {r[0][0] * x + r[0][1] * y + r[0][2], r[1][0] * x + r[1][1] * y + r[1][2], r[2][0] * x + r[2][1] * y + r[2][2]};
        x = rotated[0] / rotated[2];
        y = rotated[1] / rotated[2];
      }
      if (mapped) {
        double fx = asPixels ? camera.fx : pData.get(0, 0, 0);
        double fy = asPixels ? camera.fy : pData.get(1, 1, 0);
        double cx = asPixels ? camera.cx : pData.get(0, 2, 0);
        double cy = asPixels ? camera.cy : pData.get(1, 2, 0);
        x = x * fx + cx;
        y = y * fy + cy;
      }
      p[0] = x;
      p[1] = y;
    }
    storePoints(dst, s, points, 2);
  }

  public static void undistortPoints_0(long src, long dst, long cameraMatrix, long distCoeffs, long r, long p) {
    undistort(src, dst, cameraMatrix, distCoeffs, r, p, false);
  }

  public static void undistortPoints_1(long src, long dst, long cameraMatrix, long distCoeffs, long r) {
    undistort(src, dst, cameraMatrix, distCoeffs, r, 0, false);
  }

  public static void undistortPoints_2(long src, long dst, long cameraMatrix, long distCoeffs) {
    undistort(src, dst, cameraMatrix, distCoeffs, 0, 0, false);
  }

  public static void undistortPointsIter_0(long src, long dst, long cameraMatrix, long distCoeffs, long r, long p, int criteriaType, int criteriaMaxCount, double criteriaEpsilon) {
    undistort(src, dst, cameraMatrix, distCoeffs, r, p, false);
  }

  public static void undistortImagePoints_0(long src, long dst, long cameraMatrix, long distCoeffs, int criteriaType, int criteriaMaxCount, double criteriaEpsilon) {
    undistort(src, dst, cameraMatrix, distCoeffs, 0, 0, true);
  }

  public static void undistortImagePoints_1(long src, long dst, long cameraMatrix, long distCoeffs) {
    undistort(src, dst, cameraMatrix, distCoeffs, 0, 0, true);
  }

  // Pose

  private static final class Pose {
    double[][] r;
    double[] t;
    double error = Double.NaN;

    Pose(double[][] r, double[] t) {
      this.r = r;
      this.t = t;
    }
  }

  private static double[] residuals(Camera camera, double[][] objects, double[][] pixels, double[][] r, double[] t) {
    double[] out = new double[pixels.length * 2];
    for (int i = 0; i < pixels.length; i++) {
      double[] c = camera(r, t, objects[i]);
      double[] p = camera.project(c[0], c[1], c[2]);
      out[2 * i] = p[0] - pixels[i][0];
      out[2 * i + 1] = p[1] - pixels[i][1];
    }
    return out;
  }

  private static double cost(double[] residuals) {
    double sum = 0;
    for (double v : residuals) {
      sum += v * v;
    }
    return sum;
  }

  private static Pose perturbed(Pose pose, int k, double h) {
    if (k < 3) {
      double[] w = new double[3];
      w[k] = h;
      return new Pose(Linear.multiply(rotationOf(w), pose.r), pose.t);
    }
    double[] t = pose.t.clone();
    t[k - 3] += h;
    return new Pose(pose.r, t);
  }

  /** Levenberg-Marquardt on the reprojection error in pixels. */
  private static Pose refine(Camera camera, double[][] objects, double[][] pixels, Pose start) {
    Pose pose = new Pose(start.r, start.t);
    double[] res = residuals(camera, objects, pixels, pose.r, pose.t);
    double current = cost(res);
    double lambda = 1e-3;
    for (int iteration = 0; iteration < 100 && current > 1e-20 && lambda < 1e12; iteration++) {
      double h = 1e-7;
      double[][] jacobian = new double[res.length][6];
      for (int k = 0; k < 6; k++) {
        Pose p = perturbed(pose, k, h);
        double[] shifted = residuals(camera, objects, pixels, p.r, p.t);
        for (int i = 0; i < res.length; i++) {
          jacobian[i][k] = (shifted[i] - res[i]) / h;
        }
      }
      double[][] jtj = new double[6][6];
      double[] jtr = new double[6];
      for (int i = 0; i < res.length; i++) {
        for (int a = 0; a < 6; a++) {
          jtr[a] += jacobian[i][a] * res[i];
          for (int b = 0; b < 6; b++) {
            jtj[a][b] += jacobian[i][a] * jacobian[i][b];
          }
        }
      }
      boolean improved = false;
      while (lambda < 1e12) {
        double[][] damped = new double[6][6];
        for (int a = 0; a < 6; a++) {
          System.arraycopy(jtj[a], 0, damped[a], 0, 6);
          damped[a][a] += lambda * (jtj[a][a] + 1e-12);
        }
        double[] negative = new double[6];
        for (int a = 0; a < 6; a++) {
          negative[a] = -jtr[a];
        }
        double[] step = Linear.solve(damped, negative);
        if (step != null) {
          double[] w = {step[0], step[1], step[2]};
          Pose next = new Pose(Linear.multiply(rotationOf(w), pose.r), new double[] {pose.t[0] + step[3], pose.t[1] + step[4], pose.t[2] + step[5]});
          double[] nextRes = residuals(camera, objects, pixels, next.r, next.t);
          double nextCost = cost(nextRes);
          if (nextCost < current) {
            double size = 0;
            for (double v : step) {
              size += Math.abs(v);
            }
            pose = next;
            res = nextRes;
            current = nextCost;
            lambda = Math.max(lambda / 3, 1e-12);
            improved = size > 1e-12;
            break;
          }
        }
        lambda *= 4;
      }
      if (!improved) {
        break;
      }
    }
    // OpenCV's RMS error is per coordinate, not per point
    pose.error = Math.sqrt(current / (2.0 * pixels.length));
    return pose;
  }

  private static double[][] row(double[]... rows) {
    return rows;
  }

  /** Pose from the homography of a planar target, in the plane's own frame. Null when it cannot be found. */
  private static Pose planar(double[][] plane, double[][] normalized) {
    int n = plane.length;
    double scale = 0;
    for (double[] p : plane) {
      scale += Math.hypot(p[0], p[1]);
    }
    scale = scale == 0 ? 1 : Math.sqrt(2) * n / scale;
    double[][] ata = new double[9][9];
    for (int i = 0; i < n; i++) {
      double u = plane[i][0] * scale;
      double v = plane[i][1] * scale;
      double x = normalized[i][0];
      double y = normalized[i][1];
      double[][] rows = {{u, v, 1, 0, 0, 0, -x * u, -x * v, -x}, {0, 0, 0, u, v, 1, -y * u, -y * v, -y}};
      for (double[] r : rows) {
        for (int a = 0; a < 9; a++) {
          for (int b = 0; b < 9; b++) {
            ata[a][b] += r[a] * r[b];
          }
        }
      }
    }
    double[] h = Linear.eigenSymmetric(ata).vectors[0];
    double[] h1 = {h[0] * scale, h[3] * scale, h[6] * scale};
    double[] h2 = {h[1] * scale, h[4] * scale, h[7] * scale};
    double[] h3 = {h[2], h[5], h[8]};
    double lambda = 2 / (Math.sqrt(h1[0] * h1[0] + h1[1] * h1[1] + h1[2] * h1[2]) + Math.sqrt(h2[0] * h2[0] + h2[1] * h2[1] + h2[2] * h2[2]));
    if (Double.isNaN(lambda) || Double.isInfinite(lambda)) {
      return null;
    }
    if (h3[2] < 0) {
      lambda = -lambda;
    }
    double[] r1 = {h1[0] * lambda, h1[1] * lambda, h1[2] * lambda};
    double[] r2 = {h2[0] * lambda, h2[1] * lambda, h2[2] * lambda};
    double[] r3 = {r1[1] * r2[2] - r1[2] * r2[1], r1[2] * r2[0] - r1[0] * r2[2], r1[0] * r2[1] - r1[1] * r2[0]};
    double[][] rotation = Linear.nearestRotation(Linear.transpose(row(r1, r2, r3)));
    return new Pose(rotation, new double[] {h3[0] * lambda, h3[1] * lambda, h3[2] * lambda});
  }

  /** Pose of a target with points in 3d from the direct linear transform. Null when it cannot be found. */
  private static Pose spatial(double[][] objects, double[][] normalized) {
    int n = objects.length;
    if (n < 6) {
      return null;
    }
    double[] c = new double[3];
    for (double[] p : objects) {
      for (int k = 0; k < 3; k++) {
        c[k] += p[k] / n;
      }
    }
    double spread = 0;
    for (double[] p : objects) {
      spread += Math.sqrt(Math.pow(p[0] - c[0], 2) + Math.pow(p[1] - c[1], 2) + Math.pow(p[2] - c[2], 2));
    }
    double scale = spread == 0 ? 1 : Math.sqrt(3) * n / spread;
    double[][] ata = new double[12][12];
    for (int i = 0; i < n; i++) {
      double[] p = {(objects[i][0] - c[0]) * scale, (objects[i][1] - c[1]) * scale, (objects[i][2] - c[2]) * scale, 1};
      double x = normalized[i][0];
      double y = normalized[i][1];
      double[] first = new double[12];
      double[] second = new double[12];
      for (int k = 0; k < 4; k++) {
        first[k] = p[k];
        first[8 + k] = -x * p[k];
        second[4 + k] = p[k];
        second[8 + k] = -y * p[k];
      }
      for (double[] r : new double[][] {first, second}) {
        for (int a = 0; a < 12; a++) {
          for (int b = 0; b < 12; b++) {
            ata[a][b] += r[a] * r[b];
          }
        }
      }
    }
    double[] v = Linear.eigenSymmetric(ata).vectors[0];
    // P maps the scaled and centered points; fold the scaling and centering into the last column
    double[][] m = new double[3][3];
    double[] p4 = new double[3];
    for (int i = 0; i < 3; i++) {
      for (int k = 0; k < 3; k++) {
        m[i][k] = v[4 * i + k] * scale;
      }
      p4[i] = v[4 * i + 3];
      for (int k = 0; k < 3; k++) {
        p4[i] -= m[i][k] * c[k];
      }
    }
    double det = Linear.determinant3(m);
    if (det == 0 || Double.isNaN(det)) {
      return null;
    }
    double sign = det < 0 ? -1 : 1;
    double s = Math.cbrt(Math.abs(det));
    double[][] rotation = new double[3][3];
    for (int i = 0; i < 3; i++) {
      for (int k = 0; k < 3; k++) {
        rotation[i][k] = sign * m[i][k] / s;
      }
    }
    return new Pose(Linear.nearestRotation(rotation), new double[] {sign * p4[0] / s, sign * p4[1] / s, sign * p4[2] / s});
  }

  private static double[][] plane(double[][] objects, double[] centroid, double[][] frame) {
    int n = objects.length;
    for (double[] p : objects) {
      for (int k = 0; k < 3; k++) {
        centroid[k] += p[k] / n;
      }
    }
    double[][] covariance = new double[3][3];
    for (double[] p : objects) {
      for (int a = 0; a < 3; a++) {
        for (int b = 0; b < 3; b++) {
          covariance[a][b] += (p[a] - centroid[a]) * (p[b] - centroid[b]);
        }
      }
    }
    Linear.Eigen eigen = Linear.eigenSymmetric(covariance);
    if (eigen.values[2] <= 0 || eigen.values[0] > 1e-9 * eigen.values[2]) {
      return null;
    }
    double[] e1 = eigen.vectors[2];
    double[] e2 = eigen.vectors[1];
    double[] e3 = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
    frame[0] = e1;
    frame[1] = e2;
    frame[2] = e3;
    double[][] coordinates = new double[n][2];
    for (int i = 0; i < n; i++) {
      for (int k = 0; k < 3; k++) {
        coordinates[i][0] += (objects[i][k] - centroid[k]) * e1[k];
        coordinates[i][1] += (objects[i][k] - centroid[k]) * e2[k];
      }
    }
    return coordinates;
  }

  /** The poses of the target, best first: two for a planar target when asked for both, otherwise one. */
  private static List<Pose> solve(Camera camera, double[][] objects, double[][] pixels, int flags, Pose guess) {
    List<Pose> poses = new ArrayList<>();
    int n = objects.length;
    if (n < 4 && guess == null || n < 3 || pixels.length != n) {
      return poses;
    }
    double[][] normalized = new double[n][];
    for (int i = 0; i < n; i++) {
      normalized[i] = camera.undistort(pixels[i][0], pixels[i][1]);
    }
    boolean ippe = flags == SOLVEPNP_IPPE || flags == SOLVEPNP_IPPE_SQUARE;
    double[] centroid = new double[3];
    double[][] frame = new double[3][];
    double[][] coordinates = plane(objects, centroid, frame);
    Pose start = guess;
    if (start == null) {
      if (coordinates != null) {
        Pose inPlane = planar(coordinates, normalized);
        if (inPlane != null) {
          double[][] toPlane = row(frame[0], frame[1], frame[2]);
          double[][] rotation = Linear.multiply(inPlane.r, toPlane);
          double[] rc = Linear.multiply(rotation, centroid);
          start = new Pose(rotation, new double[] {inPlane.t[0] - rc[0], inPlane.t[1] - rc[1], inPlane.t[2] - rc[2]});
        }
      } else {
        start = spatial(objects, normalized);
      }
    }
    if (start == null) {
      return poses;
    }
    Pose best = refine(camera, objects, pixels, start);
    if (Double.isNaN(best.error)) {
      return poses;
    }
    poses.add(best);
    if (ippe && coordinates != null) {
      // The other pose of a planar target mirrors its normal about the ray to the target
      double[] ray = unit(best.t);
      double[] normal = {best.r[0][2], best.r[1][2], best.r[2][2]};
      double along = normal[0] * ray[0] + normal[1] * ray[1] + normal[2] * ray[2];
      double[] mirrored = {2 * along * ray[0] - normal[0], 2 * along * ray[1] - normal[1], 2 * along * ray[2] - normal[2]};
      double[] axis = {normal[1] * mirrored[2] - normal[2] * mirrored[1], normal[2] * mirrored[0] - normal[0] * mirrored[2], normal[0] * mirrored[1] - normal[1] * mirrored[0]};
      double sine = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
      double cosine = normal[0] * mirrored[0] + normal[1] * mirrored[1] + normal[2] * mirrored[2];
      double angle = Math.atan2(sine, cosine);
      double[] turn = sine < 1e-12 ? new double[3] : new double[] {axis[0] / sine * angle, axis[1] / sine * angle, axis[2] / sine * angle};
      Pose flipped = new Pose(Linear.multiply(rotationOf(turn), best.r), best.t);
      Pose other = refine(camera, objects, pixels, flipped);
      if (Double.isNaN(other.error) || samePose(best, other)) {
        other = flipped;
        other.error = Math.sqrt(cost(residuals(camera, objects, pixels, other.r, other.t)) / (2.0 * n));
      }
      poses.add(other);
      if (other.error < best.error) {
        poses.remove(other);
        poses.add(0, other);
      }
    }
    return poses;
  }

  private static double[] unit(double[] v) {
    double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    return length == 0 ? new double[] {0, 0, 1} : new double[] {v[0] / length, v[1] / length, v[2] / length};
  }

  private static boolean samePose(Pose a, Pose b) {
    double[] ra = rvecOf(a.r);
    double[] rb = rvecOf(b.r);
    double difference = 0;
    for (int k = 0; k < 3; k++) {
      difference += Math.abs(ra[k] - rb[k]) + Math.abs(a.t[k] - b.t[k]) / Math.max(1e-9, Math.abs(a.t[2]));
    }
    return difference < 1e-4;
  }

  private static Pose guessOf(boolean useGuess, long rvec, long tvec) {
    Data r = MatImpl.data(rvec);
    Data t = MatImpl.data(tvec);
    if (!useGuess || r.size() < 3 || t.size() < 3) {
      return null;
    }
    return new Pose(rotationOf(r), new double[] {t.get(0), t.get(1), t.get(2)});
  }

  private static int generic(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvecs, long tvecs, boolean useGuess, int flags, long rvec, long tvec, long reprojectionError) {
    double[][] objects = MatImpl.points(MatImpl.data(objectPoints), 3);
    double[][] pixels = MatImpl.points(MatImpl.data(imagePoints), 2);
    List<Pose> poses = solve(new Camera(cameraMatrix, distCoeffs), objects, pixels, flags, guessOf(useGuess, rvec, tvec));
    Data rotations = new Data();
    Data translations = new Data();
    int pairType = MatImpl.type(MatImpl.CV_32S, 2);
    rotations.alloc(poses.size(), 1, pairType);
    translations.alloc(poses.size(), 1, pairType);
    Data errorData = MatImpl.data(reprojectionError);
    Data errors = new Data();
    errors.alloc(poses.size(), 1, MatImpl.type(errorData.depth() == MatImpl.CV_64F ? MatImpl.CV_64F : MatImpl.CV_32F, 1));
    for (int i = 0; i < poses.size(); i++) {
      Pose pose = poses.get(i);
      // The Java side reads a vector of Mats as the addresses of its elements, high and low half
      long rotationHandle = MatImpl.register(column(rvecOf(pose.r)));
      long translationHandle = MatImpl.register(column(pose.t));
      rotations.set(2 * i, (int) (rotationHandle >>> 32));
      rotations.set(2 * i + 1, (int) rotationHandle);
      translations.set(2 * i, (int) (translationHandle >>> 32));
      translations.set(2 * i + 1, (int) translationHandle);
      errors.set(i, pose.error);
    }
    MatImpl.data(rvecs).share(rotations);
    MatImpl.data(tvecs).share(translations);
    errorData.share(errors);
    return poses.size();
  }

  public static int solvePnPGeneric_0(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvecs, long tvecs, boolean useGuess, int flags, long rvec, long tvec, long reprojectionError) {
    return generic(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvecs, tvecs, useGuess, flags, rvec, tvec, reprojectionError);
  }

  public static int solvePnPGeneric_1(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvecs, long tvecs, boolean useGuess, int flags, long rvec, long tvec) {
    return generic(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvecs, tvecs, useGuess, flags, rvec, tvec, 0);
  }

  public static int solvePnPGeneric_2(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvecs, long tvecs, boolean useGuess, int flags, long rvec) {
    return generic(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvecs, tvecs, useGuess, flags, rvec, 0, 0);
  }

  public static int solvePnPGeneric_3(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvecs, long tvecs, boolean useGuess, int flags) {
    return generic(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvecs, tvecs, useGuess, flags, 0, 0, 0);
  }

  public static int solvePnPGeneric_4(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvecs, long tvecs, boolean useGuess) {
    return generic(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvecs, tvecs, useGuess, 0, 0, 0, 0);
  }

  public static int solvePnPGeneric_5(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvecs, long tvecs) {
    return generic(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvecs, tvecs, false, 0, 0, 0, 0);
  }

  private static boolean single(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvec, long tvec, boolean useGuess, int flags) {
    double[][] objects = MatImpl.points(MatImpl.data(objectPoints), 3);
    double[][] pixels = MatImpl.points(MatImpl.data(imagePoints), 2);
    List<Pose> poses = solve(new Camera(cameraMatrix, distCoeffs), objects, pixels, flags, guessOf(useGuess, rvec, tvec));
    if (poses.isEmpty()) {
      return false;
    }
    MatImpl.data(rvec).share(column(rvecOf(poses.get(0).r)));
    MatImpl.data(tvec).share(column(poses.get(0).t));
    return true;
  }

  public static boolean solvePnP_0(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvec, long tvec, boolean useGuess, int flags) {
    return single(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvec, tvec, useGuess, flags);
  }

  public static boolean solvePnP_1(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvec, long tvec, boolean useGuess) {
    return single(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvec, tvec, useGuess, 0);
  }

  public static boolean solvePnP_2(long objectPoints, long imagePoints, long cameraMatrix, long distCoeffs, long rvec, long tvec) {
    return single(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvec, tvec, false, 0);
  }
}
