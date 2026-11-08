package dev.project516.frcdevwasm.shim.math.jni;

public final class Pose3dJNIImpl {
  private Pose3dJNIImpl() {}

  public static double[] exp(
      double poseX,
      double poseY,
      double poseZ,
      double poseQw,
      double poseQx,
      double poseQy,
      double poseQz,
      double twistDx,
      double twistDy,
      double twistDz,
      double twistRx,
      double twistRy,
      double twistRz) {
    double[] q = normalized(poseQw, poseQx, poseQy, poseQz);

    double theta = Math.sqrt(twistRx * twistRx + twistRy * twistRy + twistRz * twistRz);
    double thetaSq = theta * theta;
    double a;
    double b;
    double c;
    if (Math.abs(theta) < 1e-7) {
      a = 1 - thetaSq / 6 + thetaSq * thetaSq / 120;
      b = 1 / 2.0 - thetaSq / 24 + thetaSq * thetaSq / 720;
      c = 1 / 6.0 - thetaSq / 120 + thetaSq * thetaSq / 5040;
    } else {
      a = Math.sin(theta) / theta;
      b = (1 - Math.cos(theta)) / thetaSq;
      c = (1 - a) / thetaSq;
    }

    // omega is the hat matrix of the rotation vector
    double[] omega = {0, -twistRz, twistRy, twistRz, 0, -twistRx, -twistRy, twistRx, 0};
    double[] omegaSq = mul3(omega, omega);
    double[] r = new double[9];
    double[] v = new double[9];
    for (int i = 0; i < 9; i++) {
      r[i] = a * omega[i] + b * omegaSq[i];
      v[i] = b * omega[i] + c * omegaSq[i];
    }
    for (int i = 0; i < 9; i += 4) {
      r[i] += 1.0;
      v[i] += 1.0;
    }
    double tx = v[0] * twistDx + v[1] * twistDy + v[2] * twistDz;
    double ty = v[3] * twistDx + v[4] * twistDy + v[5] * twistDz;
    double tz = v[6] * twistDx + v[7] * twistDy + v[8] * twistDz;

    double[] tq = quaternionFromMatrix(r);
    double[] rotated = rotateVector(q, tx, ty, tz);
    double[] rq = normalized(multiply(q, tq));
    return new double[] {
      poseX + rotated[0], poseY + rotated[1], poseZ + rotated[2], rq[0], rq[1], rq[2], rq[3]
    };
  }

  public static double[] log(
      double startX,
      double startY,
      double startZ,
      double startQw,
      double startQx,
      double startQy,
      double startQz,
      double endX,
      double endY,
      double endZ,
      double endQw,
      double endQx,
      double endQy,
      double endQz) {
    double[] s = normalized(startQw, startQx, startQy, startQz);
    double[] e = normalized(endQw, endQx, endQy, endQz);

    // end relative to start
    double[] t = rotateVector(normalized(inverse(s)), endX - startX, endY - startY, endZ - startZ);
    double[] rel = normalized(multiply(inverse(s), e));
    double[] rvec = rotationVector(rel);

    double theta = Math.sqrt(rvec[0] * rvec[0] + rvec[1] * rvec[1] + rvec[2] * rvec[2]);
    double thetaSq = theta * theta;
    double c;
    if (Math.abs(theta) < 1e-7) {
      c = 1 / 12.0 + thetaSq / 720 + thetaSq * thetaSq / 30240;
    } else {
      double a = Math.sin(theta) / theta;
      double b = (1 - Math.cos(theta)) / thetaSq;
      c = (1 - a / (2 * b)) / thetaSq;
    }

    double[] omega = {0, -rvec[2], rvec[1], rvec[2], 0, -rvec[0], -rvec[1], rvec[0], 0};
    double[] omegaSq = mul3(omega, omega);
    double[] vInv = new double[9];
    for (int i = 0; i < 9; i++) {
      vInv[i] = -0.5 * omega[i] + c * omegaSq[i];
    }
    for (int i = 0; i < 9; i += 4) {
      vInv[i] += 1.0;
    }
    return new double[] {
      vInv[0] * t[0] + vInv[1] * t[1] + vInv[2] * t[2],
      vInv[3] * t[0] + vInv[4] * t[1] + vInv[5] * t[2],
      vInv[6] * t[0] + vInv[7] * t[1] + vInv[8] * t[2],
      rvec[0],
      rvec[1],
      rvec[2]
    };
  }

  private static double[] mul3(double[] a, double[] b) {
    double[] c = new double[9];
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        c[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
      }
    }
    return c;
  }

  private static double[] normalized(double w, double x, double y, double z) {
    double norm = Math.sqrt(w * w + x * x + y * y + z * z);
    if (norm == 0.0) {
      return new double[] {1, 0, 0, 0};
    }
    return new double[] {w / norm, x / norm, y / norm, z / norm};
  }

  private static double[] normalized(double[] q) {
    return normalized(q[0], q[1], q[2], q[3]);
  }

  private static double[] multiply(double[] a, double[] b) {
    double dot = a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
    double crossX = a[2] * b[3] - b[2] * a[3];
    double crossY = b[1] * a[3] - a[1] * b[3];
    double crossZ = a[1] * b[2] - b[1] * a[2];
    return new double[] {
      a[0] * b[0] - dot,
      a[0] * b[1] + b[0] * a[1] + crossX,
      a[0] * b[2] + b[0] * a[2] + crossY,
      a[0] * b[3] + b[0] * a[3] + crossZ
    };
  }

  private static double[] inverse(double[] q) {
    double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    double n2 = norm * norm;
    return new double[] {q[0] / n2, -q[1] / n2, -q[2] / n2, -q[3] / n2};
  }

  private static double[] rotateVector(double[] q, double x, double y, double z) {
    double[] r = multiply(multiply(q, new double[] {0.0, x, y, z}), inverse(q));
    return new double[] {r[1], r[2], r[3]};
  }

  private static double[] rotationVector(double[] q) {
    double norm = Math.sqrt(q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    double scalar;
    if (norm < 1e-9) {
      scalar = 2.0 / q[0] - 2.0 / 3.0 * norm * norm / (q[0] * q[0] * q[0]);
    } else if (q[0] < 0.0) {
      scalar = 2.0 * Math.atan2(-norm, -q[0]) / norm;
    } else {
      scalar = 2.0 * Math.atan2(norm, q[0]) / norm;
    }
    return new double[] {scalar * q[1], scalar * q[2], scalar * q[3]};
  }

  private static double[] quaternionFromMatrix(double[] r) {
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
