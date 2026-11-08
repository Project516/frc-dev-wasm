package dev.project516.frcdevwasm.test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.imgproc.Imgproc;

/** WPILib's OpenCV jar is served stubbed: no native method is left, and the geometry calls give OpenCV's results. */
public final class OpenCvTest {
  private OpenCvTest() {}

  private static final double[] CAMERA = {600, 0, 320, 0, 600, 240, 0, 0, 1};
  private static final double[] DISTORTION = {0.1, -0.05, 0.001, -0.002, 0.01, 0, 0, 0};

  private static Mat camera() {
    Mat k = new Mat(3, 3, CvType.CV_64F);
    k.put(0, 0, CAMERA);
    return k;
  }

  private static MatOfDouble distortion(double[] coefficients) {
    MatOfDouble d = new MatOfDouble();
    d.fromArray(coefficients);
    return d;
  }

  private static MatOfPoint3f tag(double side) {
    double h = side / 2;
    return new MatOfPoint3f(new Point3(-h, h, 0), new Point3(h, h, 0), new Point3(h, -h, 0), new Point3(-h, -h, 0));
  }

  private static Mat vector(double... values) {
    Mat m = new Mat(3, 1, CvType.CV_64F);
    m.put(0, 0, values);
    return m;
  }

  private static double[] read(Mat m) {
    double[] out = new double[(int) m.total() * m.channels()];
    for (int r = 0; r < m.rows(); r++) {
      for (int c = 0; c < m.cols(); c++) {
        System.arraycopy(m.get(r, c), 0, out, (r * m.cols() + c) * m.channels(), m.channels());
      }
    }
    return out;
  }

  private static void near(double[] expected, double[] actual, double tolerance, String what) {
    Tests.eq(expected.length, actual.length, what + " length");
    for (int i = 0; i < expected.length; i++) {
      Tests.near(expected[i], actual[i], tolerance, what + "[" + i + "]");
    }
  }

  private static MatOfPoint2f project(MatOfPoint3f object, Mat rvec, Mat tvec, double[] distortion) {
    MatOfPoint2f image = new MatOfPoint2f();
    Calib3d.projectPoints(object, rvec, tvec, camera(), distortion(distortion), image);
    return image;
  }

  public static void run(Tests t) {
    t.test("the OpenCV jar has no native methods", () -> {
      ClassLoader loader = Mat.class.getClassLoader();
      int classes = 0;
      File[] jars = new File(System.getProperty("frcdevwasm.runtime"), "lib").listFiles((dir, name) -> name.startsWith("opencv-java-"));
      Tests.check(jars != null && jars.length == 1, "expected one opencv-java jar");
      try (JarFile jf = new JarFile(jars[0])) {
        for (JarEntry e : Collections.list(jf.entries())) {
          if (!e.getName().endsWith(".class")) {
            continue;
          }
          Class<?> c = Class.forName(e.getName().substring(0, e.getName().length() - 6).replace('/', '.'), false, loader);
          for (Method m : c.getDeclaredMethods()) {
            Tests.check(!Modifier.isNative(m.getModifiers()), c.getName() + "." + m.getName() + " is still native");
          }
          classes++;
        }
      }
      Tests.check(classes > 100, "expected the OpenCV classes, found " + classes);
    });

    t.test("Mats keep their shape, type and elements", () -> {
      Mat grid = new Mat(2, 3, CvType.CV_64F);
      grid.put(0, 0, 1, 2, 3, 4, 5, 6);
      Mat columns = grid.submat(org.opencv.core.Range.all(), new org.opencv.core.Range(1, 3));
      Tests.eq(2, columns.rows(), "Range.all keeps every row");
      near(new double[] {2, 3, 5, 6}, read(columns), 0, "columns 1 and 2");
      Mat m = new Mat(2, 3, CvType.CV_32FC2);
      Tests.eq(2, m.rows(), "rows");
      Tests.eq(3, m.cols(), "cols");
      Tests.eq(2, m.channels(), "channels");
      Tests.eq(6L, m.total(), "total");
      m.put(1, 2, 1.5, -2.5);
      near(new double[] {1.5, -2.5}, m.get(1, 2), 0, "pixel");
      Tests.check(m.get(2, 0) == null, "out of range");
      Mat bytes = Mat.zeros(2, 2, CvType.CV_8UC1);
      bytes.put(0, 1, 200.0);
      Tests.eq(200.0, bytes.get(0, 1)[0], "byte");
      Mat doubled = new Mat();
      bytes.convertTo(doubled, CvType.CV_32F, 2);
      Tests.eq(400.0, doubled.get(0, 1)[0], "converted");
      Tests.check(Mat.eye(3, 3, CvType.CV_64F).dot(Mat.ones(3, 3, CvType.CV_64F)) == 3, "eye dot ones");
      Mat copy = m.clone();
      m.release();
      Tests.check(m.empty() && !copy.empty(), "release leaves the clone");
      Tests.check(copy.dataAddr() != 0 && m.dataAddr() == 0, "data address only with data");
      MatOfPoint2f points = new MatOfPoint2f(new Point(1, 2), new Point(3, 4));
      Tests.eq(2, points.toArray().length, "point vector");
      Tests.eq(4.0, points.toArray()[1].y, "point y");
    });

    t.test("cvtColor converts between gray, BGR and RGB", () -> {
      Mat bgr = new Mat(1, 2, CvType.CV_8UC3);
      bgr.put(0, 0, 10, 200, 50, 0, 0, 255);
      Mat gray = new Mat();
      Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
      Tests.eq(CvType.CV_8UC1, gray.type(), "gray type");
      near(new double[] {133, 76}, read(gray), 0, "gray");
      Mat rgba = new Mat();
      Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA);
      near(new double[] {50, 200, 10, 255, 255, 0, 0, 255}, read(rgba), 0, "rgba");
      Mat back = new Mat();
      Imgproc.cvtColor(gray, back, Imgproc.COLOR_GRAY2BGR);
      near(new double[] {133, 133, 133, 76, 76, 76}, read(back), 0, "gray to bgr");
    });

    t.test("zero Mats share writes and convert to black", () -> {
      Mat zeros = Mat.zeros(2, 2, CvType.CV_8UC1);
      Mat shared = new Mat();
      zeros.assignTo(shared);
      shared.put(1, 1, 7.0);
      Tests.eq(7.0, zeros.get(1, 1)[0], "write through the shared Mat");
      Tests.eq(0.0, zeros.reshape(1, 4).get(0, 0)[0], "reshaped");
      Mat black = new Mat();
      Imgproc.cvtColor(Mat.zeros(2, 2, CvType.CV_8UC1), black, Imgproc.COLOR_GRAY2BGR);
      Tests.eq(CvType.CV_8UC3, black.type(), "black type");
      near(new double[12], read(black), 0, "black");
      Mat alpha = new Mat();
      Imgproc.cvtColor(Mat.zeros(1, 1, CvType.CV_8UC1), alpha, Imgproc.COLOR_GRAY2BGRA);
      near(new double[] {0, 0, 0, 255}, read(alpha), 0, "alpha");
      Mat part = Mat.zeros(3, 3, CvType.CV_8UC1).submat(1, 3, 1, 3);
      Tests.eq(2, part.rows(), "zero submat rows");
      Tests.eq(0.0, part.get(1, 1)[0], "zero submat");
    });

    t.test("points project with the pinhole model and its distortion", () -> {
      MatOfPoint3f object = new MatOfPoint3f(new Point3(0, 0, 2), new Point3(0.5, -0.25, 2));
      near(new double[] {320, 240, 470, 165}, read(project(object, vector(0, 0, 0), vector(0, 0, 0), new double[8])), 1e-3, "no distortion");
      double x = 0.25;
      double y = -0.125;
      double r2 = x * x + y * y;
      double radial = 1 + 0.1 * r2 - 0.05 * r2 * r2 + 0.01 * r2 * r2 * r2;
      double xd = x * radial + 2 * 0.001 * x * y - 0.002 * (r2 + 2 * x * x);
      double yd = y * radial + 0.001 * (r2 + 2 * y * y) - 2 * 0.002 * x * y;
      near(new double[] {600 * xd + 320, 600 * yd + 240}, read(project(new MatOfPoint3f(new Point3(0.5, -0.25, 2)), vector(0, 0, 0), vector(0, 0, 0), DISTORTION)), 1e-3, "distorted");
      MatOfPoint2f distorted = project(object, vector(0, 0, 0), vector(0, 0, 0), DISTORTION);
      MatOfPoint2f undistorted = new MatOfPoint2f();
      Calib3d.undistortImagePoints(distorted, undistorted, camera(), distortion(DISTORTION));
      near(new double[] {320, 240, 470, 165}, read(undistorted), 1e-3, "undistorted");
    });

    t.test("contours give their box, hull and area", () -> {
      MatOfPoint2f square = new MatOfPoint2f(new Point(0, 0), new Point(10, 0), new Point(10, 4), new Point(0, 4), new Point(5, 2));
      Rect box = Imgproc.boundingRect(square);
      Tests.eq(0, box.x, "box x");
      Tests.eq(11, box.width, "box width");
      Tests.eq(5, box.height, "box height");
      MatOfInt hull = new MatOfInt();
      Imgproc.convexHull(new MatOfPoint(new Point(0, 0), new Point(10, 0), new Point(10, 4), new Point(0, 4), new Point(5, 2)), hull);
      Tests.eq(4, hull.toArray().length, "hull corners");
      MatOfPoint outline = new MatOfPoint(new Point(0, 0), new Point(10, 0), new Point(10, 4), new Point(0, 4));
      Tests.eq(40.0, Imgproc.contourArea(outline), "area");
      double angle = Math.toRadians(30);
      List<Point> turned = new ArrayList<>();
      for (double[] p : new double[][] {{0, 0}, {6, 0}, {6, 2}, {0, 2}}) {
        turned.add(new Point(5 + p[0] * Math.cos(angle) - p[1] * Math.sin(angle), 5 + p[0] * Math.sin(angle) + p[1] * Math.cos(angle)));
      }
      RotatedRect rect = Imgproc.minAreaRect(new MatOfPoint2f(turned.toArray(new Point[0])));
      Tests.near(12, rect.size.area(), 1e-6, "rotated area");
      Tests.near(Math.max(rect.size.width, rect.size.height), 6, 1e-6, "rotated length");
      Tests.check(rect.angle > 0 && rect.angle <= 90, "angle in (0, 90]");
      MatOfPoint2f cloud = new MatOfPoint2f(new Point(1, 5), new Point(3, 7));
      Mat mean = new Mat();
      Core.reduce(cloud, mean, 0, Core.REDUCE_AVG);
      near(new double[] {2, 6}, read(mean), 1e-6, "mean");
      Mat scaled = new Mat();
      Core.multiply(cloud, new org.opencv.core.Scalar(2, 3), scaled);
      Core.subtract(scaled, new org.opencv.core.Scalar(1, 1), scaled);
      near(new double[] {1, 14, 5, 20}, read(scaled), 1e-6, "scaled and shifted");
    });

    t.test("solvePnP finds the pose of a tag and its mirror pose", () -> {
      MatOfPoint3f object = tag(0.1651);
      Mat rvec = vector(0.3, -0.4, 0.1);
      Mat tvec = vector(0.2, -0.1, 2.5);
      MatOfPoint2f image = project(object, rvec, tvec, DISTORTION);
      List<Mat> rvecs = new ArrayList<>();
      List<Mat> tvecs = new ArrayList<>();
      Mat error = Mat.zeros(2, 1, CvType.CV_32F);
      int solutions = Calib3d.solvePnPGeneric(object, image, camera(), distortion(DISTORTION), rvecs, tvecs, false, Calib3d.SOLVEPNP_IPPE_SQUARE, new Mat(), new Mat(), error);
      Tests.eq(2, solutions, "solutions");
      near(read(rvec), read(rvecs.get(0)), 1e-4, "rotation");
      near(read(tvec), read(tvecs.get(0)), 1e-4, "translation");
      float[] errors = new float[2];
      error.get(0, 0, errors);
      Tests.check(errors[0] < 1e-3 && errors[1] > 10 * errors[0], "the second pose fits worse: " + errors[0] + " " + errors[1]);
      Tests.check(Math.abs(read(tvecs.get(1))[0] - 0.2) > 1e-6 || Math.abs(read(rvecs.get(1))[0] - 0.3) > 1e-3, "the second pose differs");
    });

    t.test("solvePnP is reliable on random views of tags", () -> {
      java.util.Random random = new java.util.Random(7);
      MatOfDouble none = distortion(new double[8]);
      double worstShift = 0;
      double worstAngle = 0;
      for (int trial = 0; trial < 300; trial++) {
        boolean single = trial % 2 == 0;
        // Tags seen from the camera at the origin of its frame, each tilted up to 70 degrees away from facing it
        List<double[]> corners = new ArrayList<>();
        double[] first = null;
        for (int tag = 0; tag < (single ? 1 : 2 + random.nextInt(3)); tag++) {
          double depth = 1.5 + 4 * random.nextDouble();
          double[] center = {(random.nextDouble() - 0.5) * depth * 0.6, (random.nextDouble() - 0.5) * depth * 0.4, depth};
          double tilt = Math.toRadians(70) * random.nextDouble();
          double around = 2 * Math.PI * random.nextDouble();
          double[] normal = {Math.sin(tilt) * Math.cos(around), Math.sin(tilt) * Math.sin(around), -Math.cos(tilt)};
          double[] u = {-normal[1], normal[0], 0};
          double length = Math.hypot(u[0], u[1]);
          u = length < 1e-9 ? new double[] {1, 0, 0} : new double[] {u[0] / length, u[1] / length, 0};
          double[] v = {normal[1] * u[2] - normal[2] * u[1], normal[2] * u[0] - normal[0] * u[2], normal[0] * u[1] - normal[1] * u[0]};
          if (first == null) {
            first = new double[] {u[0], v[0], normal[0], u[1], v[1], normal[1], u[2], v[2], normal[2], center[0], center[1], center[2]};
          }
          for (double[] c : new double[][] {{-0.0825, 0.0825}, {0.0825, 0.0825}, {0.0825, -0.0825}, {-0.0825, -0.0825}}) {
            corners.add(new double[] {center[0] + c[0] * u[0] + c[1] * v[0], center[1] + c[0] * u[1] + c[1] * v[1], center[2] + c[0] * u[2] + c[1] * v[2]});
          }
        }
        // The pose to find: the tag's own frame, or a world frame placed at random around the camera
        Mat truthRotation = new Mat(3, 3, CvType.CV_64F);
        double[] shift;
        if (single) {
          truthRotation.put(0, 0, java.util.Arrays.copyOf(first, 9));
          shift = new double[] {first[9], first[10], first[11]};
        } else {
          Calib3d.Rodrigues(vector(random.nextGaussian() * 0.8, random.nextGaussian() * 0.8, random.nextGaussian() * 0.8), truthRotation);
          shift = new double[] {random.nextGaussian(), random.nextGaussian(), 3 + 3 * random.nextDouble()};
        }
        double[] r = read(truthRotation);
        List<Point3> object = new ArrayList<>();
        for (int i = 0; i < corners.size(); i++) {
          if (single) {
            object.add(new Point3(i == 0 || i == 3 ? -0.0825 : 0.0825, i < 2 ? 0.0825 : -0.0825, 0));
          } else {
            // x_camera = R x_world + shift, so x_world = R^T (x_camera - shift)
            double[] d = {corners.get(i)[0] - shift[0], corners.get(i)[1] - shift[1], corners.get(i)[2] - shift[2]};
            object.add(new Point3(r[0] * d[0] + r[3] * d[1] + r[6] * d[2], r[1] * d[0] + r[4] * d[1] + r[7] * d[2], r[2] * d[0] + r[5] * d[1] + r[8] * d[2]));
          }
        }
        MatOfPoint3f points = new MatOfPoint3f(object.toArray(new Point3[0]));
        Mat truthVector = new Mat();
        Calib3d.Rodrigues(truthRotation, truthVector);
        MatOfPoint2f image = new MatOfPoint2f();
        Calib3d.projectPoints(points, truthVector, vector(shift), camera(), none, image);
        List<Mat> rvecs = new ArrayList<>();
        List<Mat> tvecs = new ArrayList<>();
        int solutions = Calib3d.solvePnPGeneric(points, image, camera(), none, rvecs, tvecs, false, single ? Calib3d.SOLVEPNP_IPPE_SQUARE : Calib3d.SOLVEPNP_SQPNP, new Mat(), new Mat(), new Mat());
        Tests.check(solutions >= (single ? 2 : 1), "trial " + trial + " found " + solutions + " poses for " + corners.size() / 4 + " tags");
        double[] got = read(tvecs.get(0));
        worstShift = Math.max(worstShift, Math.hypot(Math.hypot(got[0] - shift[0], got[1] - shift[1]), got[2] - shift[2]));
        // Compared as matrices, since a rotation of about 180 degrees has two rotation vectors
        Mat gotRotation = new Mat();
        Calib3d.Rodrigues(rvecs.get(0), gotRotation);
        double[] gotMatrix = read(gotRotation);
        double difference = 0;
        for (int i = 0; i < 9; i++) {
          difference += Math.pow(gotMatrix[i] - r[i], 2);
        }
        worstAngle = Math.max(worstAngle, Math.sqrt(difference));
      }
      Tests.check(worstShift < 1e-3, "worst translation error " + worstShift);
      Tests.check(worstAngle < 1e-3, "worst rotation error " + worstAngle);
    });

    t.test("solvePnP finds the pose of tags on two walls", () -> {
      MatOfPoint3f object = new MatOfPoint3f(
          new Point3(0, 0, 0), new Point3(0.5, 0, 0), new Point3(0.5, 0.5, 0), new Point3(0, 0.5, 0),
          new Point3(1.5, 0.2, 0.8), new Point3(1.5, 0.7, 0.8), new Point3(1.5, 0.7, 1.3), new Point3(1.5, 0.2, 1.3));
      Mat rvec = vector(2.9, 0.2, -0.3);
      Mat tvec = vector(-0.5, 0.3, 3.0);
      MatOfPoint2f image = project(object, rvec, tvec, new double[8]);
      List<Mat> rvecs = new ArrayList<>();
      List<Mat> tvecs = new ArrayList<>();
      Mat error = new Mat();
      Calib3d.solvePnPGeneric(object, image, camera(), distortion(new double[8]), rvecs, tvecs, false, Calib3d.SOLVEPNP_SQPNP, new Mat(), new Mat(), error);
      near(read(tvec), read(tvecs.get(0)), 1e-4, "translation");
      near(read(rvec), read(rvecs.get(0)), 1e-4, "rotation");
      float[] errors = new float[1];
      error.get(0, 0, errors);
      Tests.check(errors[0] < 1e-3, "error " + errors[0]);
    });
  }
}
