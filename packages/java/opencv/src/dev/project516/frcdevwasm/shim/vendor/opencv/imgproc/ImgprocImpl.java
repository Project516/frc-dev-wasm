package dev.project516.frcdevwasm.shim.vendor.opencv.imgproc;

import dev.project516.frcdevwasm.shim.vendor.opencv.core.Linear;
import dev.project516.frcdevwasm.shim.vendor.opencv.core.MatImpl;
import dev.project516.frcdevwasm.shim.vendor.opencv.core.MatImpl.Data;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The org.opencv.imgproc.Imgproc functions that geometry code calls: bounding boxes, hulls, areas and
 * perspective transforms, and the color conversions between gray, BGR and RGB. Drawing and filtering
 * functions do nothing.
 */
public final class ImgprocImpl {
  private ImgprocImpl() {}

  /** Points of a one-column or one-row Mat of two channels, or a Mat of two columns. */
  private static double[][] points(long handle) {
    return MatImpl.points(MatImpl.data(handle), 2);
  }

  public static double[] boundingRect_0(long points) {
    double[][] p = points(points);
    if (p.length == 0) {
      return new double[] {0, 0, 0, 0};
    }
    double minX = Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;
    for (double[] q : p) {
      minX = Math.min(minX, q[0]);
      minY = Math.min(minY, q[1]);
      maxX = Math.max(maxX, q[0]);
      maxY = Math.max(maxY, q[1]);
    }
    int x = (int) Math.floor(minX);
    int y = (int) Math.floor(minY);
    return new double[] {x, y, (int) Math.floor(maxX) - x + 1, (int) Math.floor(maxY) - y + 1};
  }

  public static double contourArea_0(long contour, boolean oriented) {
    double[][] p = points(contour);
    double sum = 0;
    for (int i = 0; i < p.length; i++) {
      double[] previous = p[(i + p.length - 1) % p.length];
      sum += previous[0] * p[i][1] - p[i][0] * previous[1];
    }
    sum *= 0.5;
    return oriented ? sum : Math.abs(sum);
  }

  public static double contourArea_1(long contour) {
    return contourArea_0(contour, false);
  }

  /** Indices of the convex hull in counter-clockwise order for an upward y axis, from the leftmost point. */
  private static int[] hull(double[][] p, boolean clockwise) {
    Integer[] order = new Integer[p.length];
    for (int i = 0; i < order.length; i++) {
      order[i] = i;
    }
    Arrays.sort(order, (a, b) -> p[a][0] != p[b][0] ? Double.compare(p[a][0], p[b][0]) : p[a][1] != p[b][1] ? Double.compare(p[a][1], p[b][1]) : Integer.compare(a, b));
    List<Integer> chain = new ArrayList<>();
    for (int pass = 0; pass < 2; pass++) {
      int base = chain.size();
      for (int k = 0; k < order.length; k++) {
        int i = order[pass == 0 ? k : order.length - 1 - k];
        while (chain.size() >= base + 2 && cross(p[chain.get(chain.size() - 2)], p[chain.get(chain.size() - 1)], p[i]) <= 0) {
          chain.remove(chain.size() - 1);
        }
        chain.add(i);
      }
      chain.remove(chain.size() - 1);
    }
    if (chain.isEmpty() && order.length > 0) {
      chain.add(order[0]);
    }
    // Duplicate points would repeat an index
    List<Integer> unique = new ArrayList<>();
    for (int i : chain) {
      boolean seen = false;
      for (int j : unique) {
        seen |= p[i][0] == p[j][0] && p[i][1] == p[j][1];
      }
      if (!seen) {
        unique.add(i);
      }
    }
    int[] out = new int[unique.size()];
    for (int k = 0; k < out.length; k++) {
      out[k] = unique.get(clockwise ? out.length - 1 - k : k);
    }
    return out;
  }

  private static double cross(double[] o, double[] a, double[] b) {
    return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  }

  public static void convexHull_0(long points, long hull, boolean clockwise) {
    int[] indices = hull(points(points), clockwise);
    Data out = new Data();
    out.alloc(indices.length, 1, MatImpl.CV_32S);
    for (int i = 0; i < indices.length; i++) {
      out.set(i, indices[i]);
    }
    MatImpl.data(hull).share(out);
  }

  public static void convexHull_2(long points, long hull) {
    convexHull_0(points, hull, false);
  }

  /** Center x and y, width, height and angle in degrees, the angle in (0, 90] as OpenCV 4.5.1 and later give it. */
  public static double[] minAreaRect_0(long points) {
    double[][] p = points(points);
    if (p.length == 0) {
      return new double[] {0, 0, 0, 0, 0};
    }
    int[] corners = hull(p, false);
    if (corners.length == 1) {
      return new double[] {p[corners[0]][0], p[corners[0]][1], 0, 0, 0};
    }
    double bestArea = Double.MAX_VALUE;
    double[] best = null;
    for (int i = 0; i < corners.length; i++) {
      double[] a = p[corners[i]];
      double[] b = p[corners[(i + 1) % corners.length]];
      double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
      if (length == 0) {
        continue;
      }
      double ux = (b[0] - a[0]) / length;
      double uy = (b[1] - a[1]) / length;
      double minU = Double.MAX_VALUE;
      double maxU = -Double.MAX_VALUE;
      double minV = Double.MAX_VALUE;
      double maxV = -Double.MAX_VALUE;
      for (int k : corners) {
        double u = p[k][0] * ux + p[k][1] * uy;
        double v = -p[k][0] * uy + p[k][1] * ux;
        minU = Math.min(minU, u);
        maxU = Math.max(maxU, u);
        minV = Math.min(minV, v);
        maxV = Math.max(maxV, v);
      }
      double area = (maxU - minU) * (maxV - minV);
      if (area < bestArea) {
        bestArea = area;
        double cu = (minU + maxU) / 2;
        double cv = (minV + maxV) / 2;
        best = new double[] {cu * ux - cv * uy, cu * uy + cv * ux, maxU - minU, maxV - minV, Math.toDegrees(Math.atan2(uy, ux))};
      }
    }
    if (best == null) {
      return new double[] {p[0][0], p[0][1], 0, 0, 0};
    }
    double angle = best[4];
    int quarters = (int) Math.floor(angle / 90);
    angle -= 90.0 * quarters;
    double width = best[2];
    double height = best[3];
    boolean swap = quarters % 2 != 0;
    if (angle == 0) {
      angle = 90;
      swap = !swap;
    }
    return new double[] {best[0], best[1], swap ? height : width, swap ? width : height, angle};
  }

  /** The 3x3 matrix that maps four source points to four destination points. */
  public static long getPerspectiveTransform_0(long src, long dst, int solveMethod) {
    double[][] s = points(src);
    double[][] d = points(dst);
    if (s.length < 4 || d.length < 4) {
      throw new IllegalArgumentException("getPerspectiveTransform needs four points");
    }
    double[][] a = new double[8][8];
    double[] b = new double[8];
    for (int i = 0; i < 4; i++) {
      a[i] = new double[] {s[i][0], s[i][1], 1, 0, 0, 0, -s[i][0] * d[i][0], -s[i][1] * d[i][0]};
      b[i] = d[i][0];
      a[i + 4] = new double[] {0, 0, 0, s[i][0], s[i][1], 1, -s[i][0] * d[i][1], -s[i][1] * d[i][1]};
      b[i + 4] = d[i][1];
    }
    double[] h = Linear.solve(a, b);
    Data out = new Data();
    out.alloc(3, 3, MatImpl.CV_64F);
    for (int i = 0; i < 9; i++) {
      out.set(i, h == null ? (i % 4 == 0 ? 1 : 0) : i < 8 ? h[i] : 1);
    }
    return MatImpl.register(out);
  }

  public static long getPerspectiveTransform_1(long src, long dst) {
    return getPerspectiveTransform_0(src, dst, 0);
  }

  // cvtColor codes 0 to 11 (COLOR_BGR2BGRA to COLOR_RGBA2GRAY): channels in and out
  private static final int[] FROM = {3, 4, 3, 4, 3, 4, 3, 3, 1, 1, 4, 4};
  private static final int[] TO = {4, 3, 4, 3, 3, 4, 1, 1, 3, 4, 1, 1};

  /** Codes 0 to 11 only, the conversions between gray, BGR and RGB with or without alpha. Other codes do nothing. */
  public static void cvtColor_0(long src, long dst, int code, int dstCn) {
    if (code < 0 || code >= FROM.length) {
      return;
    }
    Data s = MatImpl.data(src);
    if (s.channels() != FROM[code]) {
      throw new IllegalArgumentException("cvtColor code " + code + " needs " + FROM[code] + " channels, got " + s.channels());
    }
    double alpha = switch (s.depth()) {
      case MatImpl.CV_8U -> 255;
      case MatImpl.CV_16U -> 65535;
      case MatImpl.CV_32F -> 1;
      default -> throw new IllegalArgumentException("cvtColor needs 8U, 16U or 32F, got depth " + s.depth());
    };
    boolean swap = (code >= 2 && code <= 5) || code == 7 || code == 11;
    Data out = new Data();
    out.alloc(s.rows, s.cols, MatImpl.type(s.depth(), TO[code]));
    int in = FROM[code];
    int to = TO[code];
    // A black frame stays black unless the conversion adds alpha. PhotonVision's camera sim converts one per frame.
    boolean black = s.zero() && (to != 4 || in == 4);
    for (int i = 0; i < s.rows * s.cols && !black; i++) {
      if (in == 1) {
        double gray = s.get(i);
        for (int c = 0; c < 3; c++) {
          out.set(i * to + c, gray);
        }
      } else if (to == 1) {
        double b = s.get(i * in + (swap ? 2 : 0));
        double g = s.get(i * in + 1);
        double r = s.get(i * in + (swap ? 0 : 2));
        // OpenCV's fixed-point weights for integer images
        out.set(i, s.depth() == MatImpl.CV_32F
            ? 0.299 * r + 0.587 * g + 0.114 * b
            : ((long) r * 4899 + (long) g * 9617 + (long) b * 1868 + 8192) >> 14);
      } else {
        for (int c = 0; c < 3; c++) {
          out.set(i * to + c, s.get(i * in + (swap ? 2 - c : c)));
        }
      }
      if (to == 4) {
        out.set(i * to + 3, in == 4 ? s.get(i * in + 3) : alpha);
      }
    }
    MatImpl.data(dst).share(out);
  }

  public static void cvtColor_1(long src, long dst, int code) {
    cvtColor_0(src, dst, code, 0);
  }

  // OpenCV 4.13 (2027) adds a hint argument, which shifts the numbering

  public static void cvtColor_0(long src, long dst, int code, int dstCn, int hint) {
    cvtColor_0(src, dst, code, dstCn);
  }

  public static void cvtColor_1(long src, long dst, int code, int dstCn) {
    cvtColor_0(src, dst, code, dstCn);
  }

  public static void cvtColor_2(long src, long dst, int code) {
    cvtColor_0(src, dst, code, 0);
  }
}
