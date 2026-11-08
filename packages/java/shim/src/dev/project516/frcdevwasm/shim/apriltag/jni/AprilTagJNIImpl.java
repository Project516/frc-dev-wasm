package dev.project516.frcdevwasm.shim.apriltag.jni;

import edu.wpi.first.apriltag.AprilTagDetection;
import edu.wpi.first.apriltag.AprilTagDetector;
import edu.wpi.first.apriltag.AprilTagPoseEstimate;
import edu.wpi.first.math.geometry.Quaternion;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation3d;
import edu.wpi.first.util.RawFrame;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java stand-in for the apriltag JNI library. Detector configuration and families are
 * tracked, but detect finds no tags: the image buffer is a native pointer and the quad detector
 * is not ported. Pose estimation is a port of the C code. The tag image generators leave the
 * frame untouched.
 */
public final class AprilTagJNIImpl {
  private AprilTagJNIImpl() {}

  private static final class Detector {
    final AprilTagDetector.Config config = new AprilTagDetector.Config();
    final AprilTagDetector.QuadThresholdParameters qtp = new AprilTagDetector.QuadThresholdParameters();
    final Set<String> families = new LinkedHashSet<>();
  }

  private static final Map<Long, Detector> detectors = new HashMap<>();
  private static long nextHandle = 1;

  private static synchronized Detector detector(long det) {
    if (det == 0) {
      throw new NullPointerException("det cannot be null");
    }
    Detector d = detectors.get(det);
    if (d == null) {
      throw new IllegalArgumentException("invalid detector handle");
    }
    return d;
  }

  public static synchronized long createDetector() {
    long handle = nextHandle++;
    detectors.put(handle, new Detector());
    return handle;
  }

  public static synchronized void destroyDetector(long det) {
    detectors.remove(det);
  }

  public static synchronized void setDetectorConfig(long det, AprilTagDetector.Config config) {
    AprilTagDetector.Config c = detector(det).config;
    AprilTagDetector.Config src = config != null ? config : new AprilTagDetector.Config();
    c.numThreads = src.numThreads;
    c.quadDecimate = src.quadDecimate;
    c.quadSigma = src.quadSigma;
    c.refineEdges = src.refineEdges;
    c.decodeSharpening = src.decodeSharpening;
    c.debug = src.debug;
  }

  public static synchronized AprilTagDetector.Config getDetectorConfig(long det) {
    AprilTagDetector.Config c = detector(det).config;
    AprilTagDetector.Config out = new AprilTagDetector.Config();
    out.numThreads = c.numThreads;
    out.quadDecimate = c.quadDecimate;
    out.quadSigma = c.quadSigma;
    out.refineEdges = c.refineEdges;
    out.decodeSharpening = c.decodeSharpening;
    out.debug = c.debug;
    return out;
  }

  public static synchronized void setDetectorQTP(
      long det, AprilTagDetector.QuadThresholdParameters params) {
    AprilTagDetector.QuadThresholdParameters q = detector(det).qtp;
    AprilTagDetector.QuadThresholdParameters src =
        params != null ? params : new AprilTagDetector.QuadThresholdParameters();
    q.minClusterPixels = src.minClusterPixels;
    q.maxNumMaxima = src.maxNumMaxima;
    q.criticalAngle = src.criticalAngle;
    q.maxLineFitMSE = src.maxLineFitMSE;
    q.minWhiteBlackDiff = src.minWhiteBlackDiff;
    q.deglitch = src.deglitch;
  }

  public static synchronized AprilTagDetector.QuadThresholdParameters getDetectorQTP(long det) {
    AprilTagDetector.QuadThresholdParameters q = detector(det).qtp;
    AprilTagDetector.QuadThresholdParameters out = new AprilTagDetector.QuadThresholdParameters();
    out.minClusterPixels = q.minClusterPixels;
    out.maxNumMaxima = q.maxNumMaxima;
    out.criticalAngle = q.criticalAngle;
    out.maxLineFitMSE = q.maxLineFitMSE;
    out.minWhiteBlackDiff = q.minWhiteBlackDiff;
    out.deglitch = q.deglitch;
    return out;
  }

  public static synchronized boolean addFamily(long det, String fam, int bitsCorrected) {
    Detector d = detector(det);
    if (fam == null) {
      throw new NullPointerException("fam cannot be null");
    }
    if (!fam.equals("tag16h5") && !fam.equals("tag36h11")) {
      return false;
    }
    d.families.add(fam);
    return true;
  }

  public static synchronized void removeFamily(long det, String fam) {
    Detector d = detector(det);
    if (fam == null) {
      throw new NullPointerException("fam cannot be null");
    }
    d.families.remove(fam);
  }

  public static synchronized void clearFamilies(long det) {
    detector(det).families.clear();
  }

  public static synchronized AprilTagDetection[] detect(
      long det, int width, int height, int stride, long bufAddr) {
    detector(det);
    if (bufAddr == 0) {
      throw new NullPointerException("bufAddr cannot be null");
    }
    return new AprilTagDetection[0];
  }

  private static void checkHomography(double[] homography) {
    if (homography == null) {
      throw new NullPointerException("homography cannot be null");
    }
    if (homography.length != 9) {
      throw new IllegalArgumentException("homography array must be size 9");
    }
  }

  private static void checkCorners(double[] corners) {
    if (corners == null) {
      throw new NullPointerException("corners cannot be null");
    }
    if (corners.length != 8) {
      throw new IllegalArgumentException("corners array must be size 8");
    }
  }

  private static Transform3d toTransform(AprilTagPose.Pose pose) {
    if (pose.r == null || pose.t == null) {
      return new Transform3d();
    }
    double[] q = AprilTagPose.quaternion(AprilTagPose.orthogonalize(pose.r));
    return new Transform3d(
        new Translation3d(pose.t[0], pose.t[1], pose.t[2]),
        new Rotation3d(new Quaternion(q[0], q[1], q[2], q[3])));
  }

  public static Transform3d estimatePoseHomography(
      double[] homography, double tagSize, double fx, double fy, double cx, double cy) {
    checkHomography(homography);
    return toTransform(AprilTagPose.fromHomography(homography, tagSize, fx, fy, cx, cy));
  }

  public static AprilTagPoseEstimate estimatePoseOrthogonalIteration(
      double[] homography,
      double[] corners,
      double tagSize,
      double fx,
      double fy,
      double cx,
      double cy,
      int nIters) {
    checkHomography(homography);
    checkCorners(corners);
    AprilTagPose.Solutions s =
        AprilTagPose.orthogonalIteration(homography, corners, tagSize, fx, fy, cx, cy, nIters, 1e-7);
    return new AprilTagPoseEstimate(toTransform(s.pose1), toTransform(s.pose2), s.err1, s.err2);
  }

  public static Transform3d estimatePose(
      double[] homography,
      double[] corners,
      double tagSize,
      double fx,
      double fy,
      double cx,
      double cy) {
    checkHomography(homography);
    checkCorners(corners);
    return toTransform(AprilTagPose.best(homography, corners, tagSize, fx, fy, cx, cy));
  }

  public static void generate16h5AprilTagImage(RawFrame frameObj, long frame, int id) {
    if (frame == 0) {
      throw new NullPointerException("frame is null");
    }
  }

  public static void generate36h11AprilTagImage(RawFrame frameObj, long frame, int id) {
    if (frame == 0) {
      throw new NullPointerException("frame is null");
    }
  }
}
