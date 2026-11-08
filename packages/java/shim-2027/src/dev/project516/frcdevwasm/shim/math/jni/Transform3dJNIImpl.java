package dev.project516.frcdevwasm.shim.math.jni;

/** Transform3d.Log of wpimath (Section 3.2 of https://ethaneade.org/lie.pdf). */
public final class Transform3dJNIImpl {
  private Transform3dJNIImpl() {}

  /** Returns the twist {dx, dy, dz, rx, ry, rz}. */
  public static double[] log(
      double relX, double relY, double relZ, double relQw, double relQx, double relQy, double relQz) {
    double[] rvec = Quat.rotationVector(Quat.normalized(relQw, relQx, relQy, relQz));

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
    double[] omegaSq = Quat.mul3(omega, omega);
    double[] vInv = new double[9];
    for (int i = 0; i < 9; i++) {
      vInv[i] = -0.5 * omega[i] + c * omegaSq[i];
    }
    for (int i = 0; i < 9; i += 4) {
      vInv[i] += 1.0;
    }
    return new double[] {
      vInv[0] * relX + vInv[1] * relY + vInv[2] * relZ,
      vInv[3] * relX + vInv[4] * relY + vInv[5] * relZ,
      vInv[6] * relX + vInv[7] * relY + vInv[8] * relZ,
      rvec[0],
      rvec[1],
      rvec[2]
    };
  }
}
