package dev.project516.frcdevwasm.shim.math.jni;

/** Twist3d.Exp of wpimath (Section 3.2 of https://ethaneade.org/lie.pdf). */
public final class Twist3dJNIImpl {
  private Twist3dJNIImpl() {}

  /** Returns the transform {x, y, z, qw, qx, qy, qz}. */
  public static double[] exp(
      double twistDx,
      double twistDy,
      double twistDz,
      double twistRx,
      double twistRy,
      double twistRz) {
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
    double[] omegaSq = Quat.mul3(omega, omega);
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
    double[] q = Quat.normalized(Quat.quaternionFromMatrix(r));
    return new double[] {
      v[0] * twistDx + v[1] * twistDy + v[2] * twistDz,
      v[3] * twistDx + v[4] * twistDy + v[5] * twistDz,
      v[6] * twistDx + v[7] * twistDy + v[8] * twistDz,
      q[0],
      q[1],
      q[2],
      q[3]
    };
  }
}
