package dev.project516.frcdevwasm.shim.math.jni;

/** Quaternion {w, x, y, z} and 3x3 row-major matrix helpers for the Lie group natives. */
final class Quat {
  private Quat() {}

  static double[] mul3(double[] a, double[] b) {
    double[] c = new double[9];
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        c[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
      }
    }
    return c;
  }

  static double[] normalized(double w, double x, double y, double z) {
    double norm = Math.sqrt(w * w + x * x + y * y + z * z);
    if (norm == 0.0) {
      return new double[] {1, 0, 0, 0};
    }
    return new double[] {w / norm, x / norm, y / norm, z / norm};
  }

  static double[] normalized(double[] q) {
    return normalized(q[0], q[1], q[2], q[3]);
  }

  static double[] multiply(double[] a, double[] b) {
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

  static double[] inverse(double[] q) {
    double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    double n2 = norm * norm;
    return new double[] {q[0] / n2, -q[1] / n2, -q[2] / n2, -q[3] / n2};
  }

  static double[] rotateVector(double[] q, double x, double y, double z) {
    double[] r = multiply(multiply(q, new double[] {0.0, x, y, z}), inverse(q));
    return new double[] {r[1], r[2], r[3]};
  }

  static double[] rotationVector(double[] q) {
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

  static double[] quaternionFromMatrix(double[] r) {
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
