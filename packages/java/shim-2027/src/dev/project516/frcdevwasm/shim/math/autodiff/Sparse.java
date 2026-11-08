package dev.project516.frcdevwasm.shim.math.autodiff;

import java.util.Arrays;
import org.wpilib.math.autodiff.NativeSparseTriplets;

/**
 * A compressed column matrix like Eigen::SparseMatrix: row indices sorted within each column, and
 * explicit zeros kept, so triplet output matches the native library's.
 */
public final class Sparse {
  public final int rows;
  public final int cols;
  public final int[] colStart;
  public final int[] rowIndex;
  public final double[] values;

  public Sparse(int rows, int cols, int[] colStart, int[] rowIndex, double[] values) {
    this.rows = rows;
    this.cols = cols;
    this.colStart = colStart;
    this.rowIndex = rowIndex;
    this.values = values;
  }

  public static Sparse empty(int rows, int cols) {
    return new Sparse(rows, cols, new int[cols + 1], new int[0], new double[0]);
  }

  public int nonZeros() {
    return colStart[cols];
  }

  /** Eigen's setFromTriplets: sorted, duplicates summed in insertion order. */
  public static Sparse fromTriplets(Triplets t, int rows, int cols) {
    int n = t.size;
    int[] start = new int[cols + 1];
    for (int i = 0; i < n; i++) {
      start[t.cols[i] + 1]++;
    }
    for (int c = 0; c < cols; c++) {
      start[c + 1] += start[c];
    }
    int[] fill = Arrays.copyOf(start, cols);
    int[] ri = new int[n];
    double[] v = new double[n];
    for (int i = 0; i < n; i++) {
      int at = fill[t.cols[i]]++;
      ri[at] = t.rows[i];
      v[at] = t.values[i];
    }
    boolean merged = false;
    for (int c = 0; c < cols; c++) {
      for (int i = start[c] + 1; i < start[c + 1]; i++) {
        int r = ri[i];
        double x = v[i];
        int j = i - 1;
        while (j >= start[c] && ri[j] > r) {
          ri[j + 1] = ri[j];
          v[j + 1] = v[j];
          j--;
        }
        ri[j + 1] = r;
        v[j + 1] = x;
        merged |= j >= start[c] && ri[j] == r;
      }
    }
    Sparse s = new Sparse(rows, cols, start, ri, v);
    return merged ? s.sumDuplicates() : s;
  }

  private Sparse sumDuplicates() {
    int[] start = new int[cols + 1];
    int[] ri = new int[rowIndex.length];
    double[] v = new double[values.length];
    int n = 0;
    for (int c = 0; c < cols; c++) {
      start[c] = n;
      for (int i = colStart[c]; i < colStart[c + 1]; i++) {
        if (n > start[c] && ri[n - 1] == rowIndex[i]) {
          v[n - 1] += values[i];
        } else {
          ri[n] = rowIndex[i];
          v[n++] = values[i];
        }
      }
    }
    start[cols] = n;
    return new Sparse(rows, cols, start, Arrays.copyOf(ri, n), Arrays.copyOf(v, n));
  }

  /** Keeps entries on or below the diagonal. */
  public Sparse lower() {
    Triplets t = new Triplets(nonZeros());
    for (int c = 0; c < cols; c++) {
      for (int i = colStart[c]; i < colStart[c + 1]; i++) {
        if (rowIndex[i] >= c) {
          t.add(rowIndex[i], c, values[i]);
        }
      }
    }
    return fromTriplets(t, rows, cols);
  }

  public Sparse transpose() {
    Triplets t = new Triplets(nonZeros());
    for (int c = 0; c < cols; c++) {
      for (int i = colStart[c]; i < colStart[c + 1]; i++) {
        t.add(c, rowIndex[i], values[i]);
      }
    }
    return fromTriplets(t, cols, rows);
  }

  public Sparse scale(double s) {
    double[] v = new double[values.length];
    for (int i = 0; i < v.length; i++) {
      v[i] = s * values[i];
    }
    return new Sparse(rows, cols, colStart, rowIndex, v);
  }

  /** diag(d) * this. */
  public Sparse scaleRows(double[] d) {
    double[] v = new double[values.length];
    for (int i = 0; i < v.length; i++) {
      v[i] = d[rowIndex[i]] * values[i];
    }
    return new Sparse(rows, cols, colStart, rowIndex, v);
  }

  /** this + other with the union of both patterns. */
  public Sparse plus(Sparse o) {
    Triplets t = new Triplets(nonZeros() + o.nonZeros());
    for (int c = 0; c < cols; c++) {
      int i = colStart[c];
      int j = o.colStart[c];
      int ie = colStart[c + 1];
      int je = o.colStart[c + 1];
      while (i < ie || j < je) {
        if (j >= je || (i < ie && rowIndex[i] < o.rowIndex[j])) {
          t.add(rowIndex[i], c, values[i]);
          i++;
        } else if (i >= ie || o.rowIndex[j] < rowIndex[i]) {
          t.add(o.rowIndex[j], c, o.values[j]);
          j++;
        } else {
          t.add(rowIndex[i], c, values[i] + o.values[j]);
          i++;
          j++;
        }
      }
    }
    return fromTriplets(t, rows, cols);
  }

  /** out = this * x. */
  public double[] times(double[] x) {
    double[] out = new double[rows];
    for (int c = 0; c < cols; c++) {
      double xc = x[c];
      for (int i = colStart[c]; i < colStart[c + 1]; i++) {
        out[rowIndex[i]] += values[i] * xc;
      }
    }
    return out;
  }

  /** out = thisᵀ * y. */
  public double[] transposeTimes(double[] y) {
    double[] out = new double[cols];
    for (int c = 0; c < cols; c++) {
      double sum = 0.0;
      for (int i = colStart[c]; i < colStart[c + 1]; i++) {
        sum += values[i] * y[rowIndex[i]];
      }
      out[c] = sum;
    }
    return out;
  }

  /** The dense column vector of an n x 1 matrix. */
  public double[] toDenseVector() {
    double[] out = new double[rows];
    for (int i = 0; i < colStart[1]; i++) {
      out[rowIndex[i]] = values[i];
    }
    return out;
  }

  public boolean allFinite() {
    for (double v : values) {
      if (!Double.isFinite(v)) {
        return false;
      }
    }
    return true;
  }

  /** The largest absolute value in each row. */
  public double[] rowInfNorms() {
    double[] norms = new double[rows];
    for (int i = 0; i < nonZeros(); i++) {
      norms[rowIndex[i]] = Math.max(norms[rowIndex[i]], Math.abs(values[i]));
    }
    return norms;
  }

  public NativeSparseTriplets triplets() {
    int n = nonZeros();
    int[] r = Arrays.copyOf(rowIndex, n);
    int[] c = new int[n];
    for (int col = 0; col < cols; col++) {
      Arrays.fill(c, colStart[col], colStart[col + 1], col);
    }
    return new NativeSparseTriplets(r, c, Arrays.copyOf(values, n));
  }

  /** A growable triplet list. */
  public static final class Triplets {
    int[] rows;
    int[] cols;
    double[] values;
    int size;

    public Triplets(int capacity) {
      capacity = Math.max(capacity, 4);
      rows = new int[capacity];
      cols = new int[capacity];
      values = new double[capacity];
    }

    public Triplets copy() {
      Triplets t = new Triplets(size + 4);
      System.arraycopy(rows, 0, t.rows, 0, size);
      System.arraycopy(cols, 0, t.cols, 0, size);
      System.arraycopy(values, 0, t.values, 0, size);
      t.size = size;
      return t;
    }

    public void add(int r, int c, double v) {
      if (size == rows.length) {
        int n = size * 2;
        rows = Arrays.copyOf(rows, n);
        cols = Arrays.copyOf(cols, n);
        values = Arrays.copyOf(values, n);
      }
      rows[size] = r;
      cols[size] = c;
      values[size++] = v;
    }
  }
}
