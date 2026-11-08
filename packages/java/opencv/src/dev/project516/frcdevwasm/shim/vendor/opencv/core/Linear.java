package dev.project516.frcdevwasm.shim.vendor.opencv.core;

/** Small dense linear algebra for the OpenCV functions. Matrices are arrays of rows. */
public final class Linear {
  private Linear() {}

  /** Solves a x = b by Gaussian elimination, or returns null when a is singular. */
  public static double[] solve(double[][] a, double[] b) {
    int n = b.length;
    double[][] m = new double[n][n + 1];
    for (int i = 0; i < n; i++) {
      System.arraycopy(a[i], 0, m[i], 0, n);
      m[i][n] = b[i];
    }
    for (int col = 0; col < n; col++) {
      int pivot = col;
      for (int r = col + 1; r < n; r++) {
        if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
          pivot = r;
        }
      }
      if (Math.abs(m[pivot][col]) < 1e-300) {
        return null;
      }
      double[] swap = m[col];
      m[col] = m[pivot];
      m[pivot] = swap;
      for (int r = col + 1; r < n; r++) {
        double f = m[r][col] / m[col][col];
        for (int c = col; c <= n; c++) {
          m[r][c] -= f * m[col][c];
        }
      }
    }
    double[] x = new double[n];
    for (int i = n - 1; i >= 0; i--) {
      double sum = m[i][n];
      for (int c = i + 1; c < n; c++) {
        sum -= m[i][c] * x[c];
      }
      x[i] = sum / m[i][i];
    }
    return x;
  }

  /** Eigenvalues and eigenvectors of a symmetric matrix by Jacobi rotations, smallest eigenvalue first. */
  public static final class Eigen {
    public final double[] values;
    /** vectors[k] belongs to values[k]. */
    public final double[][] vectors;

    Eigen(double[] values, double[][] vectors) {
      this.values = values;
      this.vectors = vectors;
    }
  }

  public static Eigen eigenSymmetric(double[][] source) {
    int n = source.length;
    double[][] a = new double[n][];
    double[][] v = new double[n][n];
    for (int i = 0; i < n; i++) {
      a[i] = source[i].clone();
      v[i][i] = 1;
    }
    for (int sweep = 0; sweep < 60; sweep++) {
      double off = 0;
      for (int p = 0; p < n; p++) {
        for (int q = p + 1; q < n; q++) {
          off += a[p][q] * a[p][q];
        }
      }
      if (off < 1e-30) {
        break;
      }
      for (int p = 0; p < n; p++) {
        for (int q = p + 1; q < n; q++) {
          if (Math.abs(a[p][q]) < 1e-300) {
            continue;
          }
          double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
          double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
          if (theta == 0) {
            t = 1;
          }
          double c = 1 / Math.sqrt(t * t + 1);
          double s = t * c;
          for (int k = 0; k < n; k++) {
            double akp = a[k][p];
            double akq = a[k][q];
            a[k][p] = c * akp - s * akq;
            a[k][q] = s * akp + c * akq;
          }
          for (int k = 0; k < n; k++) {
            double apk = a[p][k];
            double aqk = a[q][k];
            a[p][k] = c * apk - s * aqk;
            a[q][k] = s * apk + c * aqk;
          }
          for (int k = 0; k < n; k++) {
            double vkp = v[k][p];
            double vkq = v[k][q];
            v[k][p] = c * vkp - s * vkq;
            v[k][q] = s * vkp + c * vkq;
          }
        }
      }
    }
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    java.util.Arrays.sort(order, (x, y) -> Double.compare(a[x][x], a[y][y]));
    double[] values = new double[n];
    double[][] vectors = new double[n][n];
    for (int k = 0; k < n; k++) {
      values[k] = a[order[k]][order[k]];
      for (int i = 0; i < n; i++) {
        vectors[k][i] = v[i][order[k]];
      }
    }
    return new Eigen(values, vectors);
  }

  public static double[][] multiply(double[][] a, double[][] b) {
    double[][] c = new double[a.length][b[0].length];
    for (int i = 0; i < a.length; i++) {
      for (int k = 0; k < b.length; k++) {
        for (int j = 0; j < b[0].length; j++) {
          c[i][j] += a[i][k] * b[k][j];
        }
      }
    }
    return c;
  }

  public static double[] multiply(double[][] a, double[] x) {
    double[] y = new double[a.length];
    for (int i = 0; i < a.length; i++) {
      for (int k = 0; k < x.length; k++) {
        y[i] += a[i][k] * x[k];
      }
    }
    return y;
  }

  public static double[][] transpose(double[][] a) {
    double[][] t = new double[a[0].length][a.length];
    for (int i = 0; i < a.length; i++) {
      for (int j = 0; j < a[0].length; j++) {
        t[j][i] = a[i][j];
      }
    }
    return t;
  }

  public static double determinant3(double[][] m) {
    return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
        - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
        + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
  }

  /** Inverse of a 3x3 matrix, or null when it is singular. */
  public static double[][] inverse3(double[][] m) {
    double det = determinant3(m);
    if (Math.abs(det) < 1e-300) {
      return null;
    }
    double[][] r = new double[3][3];
    r[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det;
    r[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det;
    r[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det;
    r[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det;
    r[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det;
    r[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det;
    r[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det;
    r[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det;
    r[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det;
    return r;
  }

  /** The rotation nearest to m, by polar decomposition. */
  public static double[][] nearestRotation(double[][] m) {
    double[][] r = m;
    for (int i = 0; i < 50; i++) {
      double[][] inverseTranspose = inverse3(r);
      if (inverseTranspose == null) {
        return identity3();
      }
      inverseTranspose = transpose(inverseTranspose);
      double[][] next = new double[3][3];
      double change = 0;
      for (int a = 0; a < 3; a++) {
        for (int b = 0; b < 3; b++) {
          next[a][b] = 0.5 * (r[a][b] + inverseTranspose[a][b]);
          change += Math.abs(next[a][b] - r[a][b]);
        }
      }
      r = next;
      if (change < 1e-14) {
        break;
      }
    }
    return determinant3(r) < 0 ? identity3() : r;
  }

  public static double[][] identity3() {
    return new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
  }
}
